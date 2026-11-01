"""Estado compartido y autenticación mínima para el cliente Tod4S3 Sync."""
from __future__ import annotations

import hashlib
import copy
import json
import os
import secrets
import tempfile
import threading
import time
from pathlib import Path
from typing import Any

DATA_DIR = Path(os.getenv("BOT_DATA_DIR", "/data"))
STATE_FILE = Path(os.getenv("SYNC_APP_STATE_FILE", str(DATA_DIR / "sync_app.json")))
_LOCK = threading.RLock()
_PAIRING_TTL = 10 * 60
_TOKEN_TTL = 365 * 24 * 60 * 60


def _default_state() -> dict[str, Any]:
    return {"pairings": {}, "tokens": {}, "deliveries": {}, "premium_activations": {}}


def _read() -> dict[str, Any]:
    try:
        raw = json.loads(STATE_FILE.read_text(encoding="utf-8"))
        if isinstance(raw, dict):
            state = _default_state()
            state.update({k: v for k, v in raw.items() if k in state})
            return state
    except (FileNotFoundError, OSError, json.JSONDecodeError):
        pass
    return _default_state()


def _write(state: dict[str, Any]) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix="sync_app_", suffix=".json", dir=str(STATE_FILE.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(state, handle, ensure_ascii=False, separators=(",", ":"))
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_name, STATE_FILE)
    finally:
        try:
            os.unlink(tmp_name)
        except FileNotFoundError:
            pass


def create_pairing_code(user_id: int) -> str:
    """Crea un código de un solo uso que caduca en diez minutos."""
    with _LOCK:
        state = _read()
        now = int(time.time())
        pairings = {
            code: item for code, item in state["pairings"].items()
            if isinstance(item, dict) and int(item.get("expires_at", 0)) > now
        }
        code = secrets.token_hex(4).upper()
        pairings[code] = {"user_id": int(user_id), "expires_at": now + _PAIRING_TTL}
        state["pairings"] = pairings
        _write(state)
        return code


def consume_pairing_code(code: str, device_id: str) -> str | None:
    """Intercambia el código por un token persistente del dispositivo."""
    code = str(code or "").strip().upper()
    device_id = str(device_id or "").strip()[:128]
    if not code or not device_id:
        return None
    with _LOCK:
        state = _read()
        item = state["pairings"].get(code)
        if not isinstance(item, dict) or int(item.get("expires_at", 0)) <= int(time.time()):
            state["pairings"].pop(code, None)
            _write(state)
            return None
        token = "t4s3_" + secrets.token_urlsafe(32)
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        state["tokens"][token_hash] = {
            "user_id": int(item["user_id"]),
            "device_id": device_id,
            "created_at": int(time.time()),
        }
        state["pairings"].pop(code, None)
        _write(state)
        return token


def revoke_token(token: str) -> bool:
    token_hash = hashlib.sha256(str(token or "").encode("utf-8")).hexdigest()
    with _LOCK:
        state = _read()
        removed = state["tokens"].pop(token_hash, None) is not None
        if removed:
            _write(state)
        return removed


def user_id_for_token(token: str) -> int | None:
    identity = app_identity_for_token(token)
    return identity["user_id"] if identity else None


def app_identity_for_token(token: str) -> dict[str, Any] | None:
    token_hash = hashlib.sha256(str(token or "").encode("utf-8")).hexdigest()
    with _LOCK:
        state = _read()
        item = state["tokens"].get(token_hash)
        if not isinstance(item, dict):
            return None
        if int(item.get("created_at", 0)) + _TOKEN_TTL <= int(time.time()):
            state["tokens"].pop(token_hash, None)
            _write(state)
            return None
        user_id = int(item.get("user_id", 0))
        device_id = str(item.get("device_id", "")).strip()[:128]
        if user_id <= 0 or not device_id:
            return None
        return {"user_id": user_id, "device_id": device_id}


def issue_premium_activation_token(user_id: int, expires_at: str | None = None) -> str:
    """Issue a one-user code; store only its digest and keep an existing device binding."""
    user_id = int(user_id)
    if user_id <= 0:
        raise ValueError("invalid_user_id")
    normalized_expiry = str(expires_at or "").strip()
    if normalized_expiry:
        from datetime import datetime, timezone
        expiry = datetime.fromisoformat(normalized_expiry)
        if expiry.tzinfo is None:
            expiry = expiry.replace(tzinfo=timezone.utc)
        if expiry <= datetime.now(timezone.utc):
            raise ValueError("activation_expired")
    code = "T4S3-" + secrets.token_hex(12).upper()
    digest = hashlib.sha256(code.encode("ascii")).hexdigest()
    with _LOCK:
        state = _read()
        records = state.get("premium_activations", {})
        if not isinstance(records, dict):
            records = {}
        previous = records.get(str(user_id), {})
        bound_device = str(previous.get("device_id", "")) if isinstance(previous, dict) else ""
        records[str(user_id)] = {
            "token_hash": digest,
            "user_id": user_id,
            "expires_at": normalized_expiry,
            "issued_at": int(time.time()),
            "device_id": bound_device,
            "verified_at": None,
        }
        state["premium_activations"] = records
        _write(state)
    return code


def revoke_premium_activation(user_id: int) -> bool:
    with _LOCK:
        state = _read()
        records = state.get("premium_activations", {})
        removed = isinstance(records, dict) and records.pop(str(int(user_id)), None) is not None
        if removed:
            state["premium_activations"] = records
            _write(state)
        return bool(removed)


def premium_activation_status(user_id: int, device_id: str = "", expires_at: str | None = None) -> str:
    """Return a non-secret status for admin/profile views."""
    with _LOCK:
        record = _read().get("premium_activations", {}).get(str(int(user_id)))
        if not isinstance(record, dict) or not record.get("token_hash"):
            return "missing"
        if str(record.get("expires_at", "")) != str(expires_at or ""):
            return "expired"
        bound_device = str(record.get("device_id", ""))
        if bound_device and device_id and not secrets.compare_digest(bound_device, device_id):
            return "other_device"
        return "verified" if bound_device and record.get("verified_at") else "pending"


def claim_premium_activation_token(token: str, user_id: int, device_id: str,
                                  expires_at: str | None = None) -> tuple[bool, str]:
    """Verify the admin code against an active Premium assignment and bind it to this device."""
    code = str(token or "").strip().upper()
    device_id = str(device_id or "").strip()[:128]
    if not code or not device_id or int(user_id) <= 0:
        return False, "invalid_request"
    digest = hashlib.sha256(code.encode("utf-8")).hexdigest()
    with _LOCK:
        state = _read()
        records = state.get("premium_activations", {})
        record = records.get(str(int(user_id))) if isinstance(records, dict) else None
        if not isinstance(record, dict) or not secrets.compare_digest(
                str(record.get("token_hash", "")), digest):
            return False, "invalid_token"
        token_expiry = str(record.get("expires_at", ""))
        current_expiry = str(expires_at or "")
        if token_expiry != current_expiry:
            return False, "plan_changed"
        if token_expiry:
            from datetime import datetime, timezone
            try:
                expiry = datetime.fromisoformat(token_expiry)
                if expiry.tzinfo is None:
                    expiry = expiry.replace(tzinfo=timezone.utc)
            except ValueError:
                return False, "activation_expired"
            if expiry <= datetime.now(timezone.utc):
                return False, "activation_expired"
        bound_device = str(record.get("device_id", ""))
        if bound_device and not secrets.compare_digest(bound_device, device_id):
            return False, "device_mismatch"
        record["device_id"] = device_id
        record["verified_at"] = record.get("verified_at") or int(time.time())
        records[str(int(user_id))] = record
        state["premium_activations"] = records
        _write(state)
        return True, "verified"


def record_delivery(user_id: int, task_id: str, filename: str, size: int,
                    parts: list[str], *, storage_backend: str = "todus_native",
                    remote_delete_supported: bool = False) -> None:
    """Publica una entrega reciente para el usuario vinculado."""
    if not parts:
        return
    with _LOCK:
        state = _read()
        key = str(int(user_id))
        deliveries = state["deliveries"].get(key, [])
        if not isinstance(deliveries, list):
            deliveries = []
        deliveries.insert(0, {
            "task_id": str(task_id),
            "source": "tod4s3_bot",
            "storage_backend": storage_backend if storage_backend in {"todus_native", "s3"} else "todus_native",
            "remote_delete_supported": bool(remote_delete_supported),
            "filename": str(filename or "archivo")[:180],
            "size": max(0, int(size or 0)),
            "parts": [str(url) for url in parts if str(url).startswith("https://")],
            "created_at": int(time.time()),
        })
        state["deliveries"][key] = deliveries[:30]
        _write(state)


def deliveries_for_token(token: str) -> list[dict[str, Any]] | None:
    user_id = user_id_for_token(token)
    if user_id is None:
        return None
    with _LOCK:
        state = _read()
        rows = state["deliveries"].get(str(user_id), [])
        if not isinstance(rows, list):
            return []
        return [
            ({**row, "source": "tod4s3_bot"} if "source" not in row else row)
            for row in rows
            if isinstance(row, dict) and row.get("source", "tod4s3_bot") == "tod4s3_bot"
        ]


def deliveries_for_user(user_id: int) -> list[dict[str, Any]]:
    """Returns a copy of this user's bot deliveries for server-side cleanup."""
    with _LOCK:
        state = _read()
        rows = state["deliveries"].get(str(int(user_id)), [])
        if not isinstance(rows, list):
            return []
        return [dict(row) for row in rows if isinstance(row, dict)
                and row.get("source", "tod4s3_bot") == "tod4s3_bot"]


def all_deliveries_snapshot() -> dict[str, list[dict[str, Any]]]:
    """Snapshot de las filas del bot en todos los namespaces de usuario."""
    with _LOCK:
        state = _read()
        result: dict[str, list[dict[str, Any]]] = {}
        deliveries = state.get("deliveries", {})
        if not isinstance(deliveries, dict):
            return result
        for user_key, rows in deliveries.items():
            if not isinstance(rows, list):
                continue
            selected = [copy.deepcopy(row) for row in rows if isinstance(row, dict)
                        and row.get("source", "tod4s3_bot") == "tod4s3_bot"]
            if selected:
                result[str(user_key)] = selected
        return result


def all_deliveries_match_snapshot(expected_rows: dict[str, list[dict[str, Any]]]) -> bool:
    """Comprueba que las filas del bot no cambiaron desde el snapshot."""
    with _LOCK:
        state = _read()
        current: dict[str, list[dict[str, Any]]] = {}
        deliveries = state.get("deliveries", {})
        if isinstance(deliveries, dict):
            for user_key, rows in deliveries.items():
                if not isinstance(rows, list):
                    continue
                selected = [row for row in rows if isinstance(row, dict)
                            and row.get("source", "tod4s3_bot") == "tod4s3_bot"]
                if selected:
                    current[str(user_key)] = selected
        return current == expected_rows


def clear_all_deliveries(expected_rows: dict[str, list[dict[str, Any]]]) -> int | None:
    """Borra solo filas del bot si el snapshot global sigue intacto; None indica concurrencia."""
    with _LOCK:
        state = _read()
        deliveries = state.get("deliveries", {})
        if not isinstance(deliveries, dict):
            deliveries = {}
            state["deliveries"] = deliveries
        current: dict[str, list[dict[str, Any]]] = {}
        for user_key, rows in deliveries.items():
            if not isinstance(rows, list):
                continue
            selected = [row for row in rows if isinstance(row, dict)
                        and row.get("source", "tod4s3_bot") == "tod4s3_bot"]
            if selected:
                current[str(user_key)] = selected
        if current != expected_rows:
            return None
        removed = sum(len(rows) for rows in current.values())
        if not removed:
            return 0
        for user_key, rows in list(deliveries.items()):
            kept = [row for row in rows if not (
                isinstance(row, dict) and row.get("source", "tod4s3_bot") == "tod4s3_bot"
            )] if isinstance(rows, list) else rows
            if kept:
                deliveries[user_key] = kept
            else:
                deliveries.pop(user_key, None)
        _write(state)
        return removed


def clear_deliveries_for_user(user_id: int,
                              expected_rows: list[dict[str, Any]] | None = None) -> int | None:
    """Remove only unchanged bot rows; return None if rows changed since remote deletion."""
    with _LOCK:
        state = _read()
        key = str(int(user_id))
        rows = state["deliveries"].get(key, [])
        if not isinstance(rows, list):
            return 0
        bot_rows = [row for row in rows if isinstance(row, dict)
                    and row.get("source", "tod4s3_bot") == "tod4s3_bot"]
        if expected_rows is not None and bot_rows != expected_rows:
            return None
        kept = [row for row in rows if not (
            isinstance(row, dict) and row.get("source", "tod4s3_bot") == "tod4s3_bot"
        )]
        removed = len(bot_rows)
        if removed:
            if kept:
                state["deliveries"][key] = kept
            else:
                state["deliveries"].pop(key, None)
            _write(state)
        return removed


def delivery_for_user(user_id: int, task_id: str) -> dict[str, Any] | None:
    """Devuelve una copia de una entrega cuyo propietario ya se verificó."""
    with _LOCK:
        state = _read()
        rows = state["deliveries"].get(str(int(user_id)), [])
        if not isinstance(rows, list):
            return None
        for row in rows:
            if isinstance(row, dict) and row.get("task_id") == task_id:
                return dict(row)
    return None


def has_delivery_without_task_id(user_id: int) -> bool:
    """Indica si hay entregas legacy que no pueden identificarse para borrado."""
    with _LOCK:
        state = _read()
        rows = state["deliveries"].get(str(int(user_id)), [])
        return isinstance(rows, list) and any(
            isinstance(row, dict)
            and row.get("source", "tod4s3_bot") == "tod4s3_bot"
            and (not isinstance(row.get("task_id"), str) or not row.get("task_id").strip())
            for row in rows
        )


def remove_delivery_for_user(user_id: int, task_id: str,
                             expected_row: dict[str, Any]) -> bool:
    """Elimina localmente la fila solo si no cambió durante el borrado remoto."""
    with _LOCK:
        state = _read()
        key = str(int(user_id))
        rows = state["deliveries"].get(key, [])
        if not isinstance(rows, list):
            return False
        for index, row in enumerate(rows):
            if isinstance(row, dict) and row.get("task_id") == task_id:
                if row != expected_row:
                    return False
                del rows[index]
                state["deliveries"][key] = rows
                _write(state)
                return True
    return False
