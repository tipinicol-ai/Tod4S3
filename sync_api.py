"""Estado compartido y autenticación mínima para el cliente Tod4S3 Sync."""
from __future__ import annotations

import hashlib
import copy
import json
import os
import re
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
_TOKEN_TTL = 365 * 24 * 60 * 60  # caduca tras un año sin actividad, no desde el pairing inicial


def _default_state() -> dict[str, Any]:
    return {"pairings": {}, "tokens": {}, "deliveries": {}, "premium_activations": {},
            "app_bans": {"users": {}, "devices": {}}, "app_ban_history": [],
            "app_support_messages": []}


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
        user_id = int(item["user_id"])
        if _app_access_ban_in_state(state, user_id, device_id):
            return None
        state["tokens"][token_hash] = {
            "user_id": user_id,
            "device_id": device_id,
            "created_at": int(time.time()),
            "last_seen_at": int(time.time()),
        }
        state["pairings"].pop(code, None)
        _write(state)
        return token


def support_identity_from_pairing(code: str, device_id: str) -> dict[str, Any] | None:
    """Authenticate a support request from a valid, unconsumed app pairing code."""
    code = str(code or "").strip().upper()
    device_id = str(device_id or "").strip()[:128]
    if not re.fullmatch(r"[0-9A-F]{8}", code) or not device_id or any(ord(char) < 32 for char in device_id):
        return None
    with _LOCK:
        state = _read()
        item = state.get("pairings", {}).get(code)
        if not isinstance(item, dict) or int(item.get("expires_at", 0) or 0) <= int(time.time()):
            return None
        try:
            user_id = int(item.get("user_id", 0))
        except (TypeError, ValueError):
            return None
        if user_id <= 0:
            return None
        return {"user_id": user_id, "device_id": device_id}


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
        now = int(time.time())
        created_at = int(item.get("created_at", 0) or 0)
        last_seen_at = int(item.get("last_seen_at", 0) or created_at)
        if last_seen_at + _TOKEN_TTL <= now:
            state["tokens"].pop(token_hash, None)
            _write(state)
            return None
        user_id = int(item.get("user_id", 0))
        device_id = str(item.get("device_id", "")).strip()[:128]
        if user_id <= 0 or not device_id:
            return None
        if now - int(item.get("last_seen_at", 0) or 0) >= 60:
            item["last_seen_at"] = now
            _write(state)
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


def _app_access_ban_in_state(state: dict[str, Any], user_id: int,
                             device_id: str = "") -> dict[str, Any] | None:
    bans = state.get("app_bans", {})
    if not isinstance(bans, dict):
        return None
    user_bans = bans.get("users", {})
    device_bans = bans.get("devices", {})
    entry = user_bans.get(str(int(user_id))) if isinstance(user_bans, dict) else None
    if isinstance(entry, dict):
        return {"scope": "telegram", "banned_at": int(entry.get("banned_at", 0) or 0)}
    device_id = str(device_id or "").strip()[:128]
    entry = device_bans.get(device_id) if device_id and isinstance(device_bans, dict) else None
    if isinstance(entry, dict):
        owners = entry.get("user_ids", [])
        if not owners or str(int(user_id)) in {str(owner) for owner in owners} or device_id:
            return {"scope": "device", "banned_at": int(entry.get("banned_at", 0) or 0)}
    return None


def app_access_ban(user_id: int, device_id: str = "") -> dict[str, Any] | None:
    """Return a non-secret ban marker if either Telegram ID or device is blocked."""
    with _LOCK:
        return _app_access_ban_in_state(_read(), int(user_id), device_id)


def is_app_user_banned(user_id: int, device_id: str = "") -> bool:
    return app_access_ban(user_id, device_id) is not None


def pairing_code_ban(code: str, device_id: str) -> dict[str, Any] | None:
    """Check a pairing attempt before issuing a token, including device-only bans."""
    code = str(code or "").strip().upper()
    device_id = str(device_id or "").strip()[:128]
    if not code or not device_id:
        return None
    with _LOCK:
        state = _read()
        pairing = state.get("pairings", {}).get(code)
        if not isinstance(pairing, dict) or int(pairing.get("expires_at", 0)) <= int(time.time()):
            return None
        return _app_access_ban_in_state(state, int(pairing.get("user_id", 0)), device_id)


def ban_app_user(user_id: int, admin_id: int = 0) -> dict[str, Any]:
    """Ban a Telegram user and every mobile fingerprint currently linked to it."""
    user_id = int(user_id)
    admin_id = int(admin_id or 0)
    if user_id <= 0:
        raise ValueError("invalid_user_id")
    now = int(time.time())
    user_key = str(user_id)
    with _LOCK:
        state = _read()
        bans = state.get("app_bans", {})
        if not isinstance(bans, dict):
            bans = {"users": {}, "devices": {}}
        users = bans.get("users", {})
        devices = bans.get("devices", {})
        if not isinstance(users, dict):
            users = {}
        if not isinstance(devices, dict):
            devices = {}
        users[user_key] = {"banned_at": now, "banned_by": admin_id}
        device_ids: set[str] = set()
        for record in state.get("tokens", {}).values():
            if not isinstance(record, dict):
                continue
            owner = int(record.get("user_id", 0) or 0)
            device_id = str(record.get("device_id", "")).strip()[:128]
            if not device_id:
                continue
            if owner == user_id:
                device_ids.add(device_id)
        for device_id in device_ids:
            record = devices.get(device_id, {})
            record = dict(record) if isinstance(record, dict) else {}
            owners = {str(owner) for owner in record.get("user_ids", [])}
            owners.add(user_key)
            record.update({"user_ids": sorted(owners), "banned_at": now, "banned_by": admin_id})
            devices[device_id] = record
        bans["users"] = users
        bans["devices"] = devices
        state["app_bans"] = bans
        history = state.get("app_ban_history", [])
        history = history if isinstance(history, list) else []
        history.append({
            "action": "ban",
            "user_id": user_id,
            "device_ids": sorted(device_ids),
            "at": now,
            "admin_id": admin_id,
        })
        state["app_ban_history"] = history[-5000:]
        _write(state)
        return {"user_id": user_id, "device_ids": sorted(device_ids), "banned_at": now}


def unban_app_user(user_id: int, admin_id: int = 0) -> dict[str, Any]:
    """Remove this Telegram ban and its device associations, preserving shared bans."""
    user_id = int(user_id)
    admin_id = int(admin_id or 0)
    if user_id <= 0:
        raise ValueError("invalid_user_id")
    user_key = str(user_id)
    with _LOCK:
        state = _read()
        bans = state.get("app_bans", {})
        if not isinstance(bans, dict):
            return {"user_id": user_id, "changed": False, "device_ids": []}
        users = bans.get("users", {})
        devices = bans.get("devices", {})
        if not isinstance(users, dict):
            users = {}
        if not isinstance(devices, dict):
            devices = {}
        changed = users.pop(user_key, None) is not None
        affected: list[str] = []
        for device_id, entry in list(devices.items()):
            if not isinstance(entry, dict):
                continue
            owners = {str(owner) for owner in entry.get("user_ids", [])}
            if user_key not in owners:
                continue
            affected.append(str(device_id))
            owners.discard(user_key)
            changed = True
            if owners:
                entry["user_ids"] = sorted(owners)
                devices[device_id] = entry
            else:
                devices.pop(device_id, None)
        if changed:
            bans["users"] = users
            bans["devices"] = devices
            state["app_bans"] = bans
            history = state.get("app_ban_history", [])
            history = history if isinstance(history, list) else []
            history.append({
                "action": "unban",
                "user_id": user_id,
                "device_ids": sorted(affected),
                "at": int(time.time()),
                "admin_id": admin_id,
            })
            state["app_ban_history"] = history[-5000:]
            _write(state)
        return {"user_id": user_id, "changed": changed, "device_ids": sorted(affected)}


def app_users_page(page: int = 0, page_size: int = 3) -> tuple[list[dict[str, Any]], int]:
    """Return users paired to the Android app plus retained bans, without token secrets."""
    page = max(0, int(page))
    page_size = max(1, min(20, int(page_size)))
    now = int(time.time())
    with _LOCK:
        state = _read()
        bans = state.get("app_bans", {})
        bans = bans if isinstance(bans, dict) else {}
        banned_users = bans.get("users", {})
        banned_devices = bans.get("devices", {})
        banned_users = banned_users if isinstance(banned_users, dict) else {}
        banned_devices = banned_devices if isinstance(banned_devices, dict) else {}
        users: dict[int, dict[str, Any]] = {}
        tokens = state.get("tokens", {})
        if isinstance(tokens, dict):
            for record in tokens.values():
                if not isinstance(record, dict):
                    continue
                try:
                    user_id = int(record.get("user_id", 0))
                    created = int(record.get("created_at", 0) or 0)
                    seen = int(record.get("last_seen_at", created) or created)
                except (TypeError, ValueError):
                    continue
                if user_id <= 0 or created + _TOKEN_TTL <= now:
                    continue
                entry = users.setdefault(user_id, {"user_id": user_id, "device_ids": set(), "last_seen_at": 0})
                device_id = str(record.get("device_id", "")).strip()[:128]
                if device_id:
                    entry["device_ids"].add(device_id)
                entry["last_seen_at"] = max(entry["last_seen_at"], seen)
        for user_key, record in banned_users.items():
            try:
                user_id = int(user_key)
            except (TypeError, ValueError):
                continue
            users.setdefault(user_id, {"user_id": user_id, "device_ids": set(), "last_seen_at": 0})
        for device_id, record in banned_devices.items():
            if not isinstance(record, dict):
                continue
            for owner in record.get("user_ids", []):
                try:
                    user_id = int(owner)
                except (TypeError, ValueError):
                    continue
                entry = users.setdefault(user_id, {"user_id": user_id, "device_ids": set(), "last_seen_at": 0})
                entry["device_ids"].add(str(device_id))
        rows = []
        for user_id, entry in users.items():
            devices = sorted(entry["device_ids"])
            banned_device_ids = [device_id for device_id in devices if device_id in banned_devices]
            last_seen = int(entry["last_seen_at"])
            rows.append({
                "user_id": user_id,
                "device_ids": devices,
                "last_seen_at": last_seen,
                "online": bool(last_seen and now - last_seen <= 180),
                "banned": str(user_id) in banned_users or bool(banned_device_ids),
                "telegram_banned": str(user_id) in banned_users,
                "banned_device_ids": banned_device_ids,
            })
        rows.sort(key=lambda row: (row["last_seen_at"], row["user_id"]), reverse=True)
        total = len(rows)
        selected = rows[page * page_size:(page + 1) * page_size]
        return selected, total


def app_user_info(user_id: int) -> dict[str, Any]:
    """Return APK-only access/device details for an exact Telegram ID lookup."""
    user_id = int(user_id)
    if user_id <= 0:
        raise ValueError("invalid_user_id")
    now = int(time.time())
    user_key = str(user_id)
    with _LOCK:
        state = _read()
        bans = state.get("app_bans", {})
        bans = bans if isinstance(bans, dict) else {}
        banned_users = bans.get("users", {})
        banned_devices = bans.get("devices", {})
        banned_users = banned_users if isinstance(banned_users, dict) else {}
        banned_devices = banned_devices if isinstance(banned_devices, dict) else {}
        device_ids: set[str] = set()
        last_seen = 0
        tokens = state.get("tokens", {})
        if isinstance(tokens, dict):
            for record in tokens.values():
                if not isinstance(record, dict):
                    continue
                try:
                    if int(record.get("user_id", 0)) != user_id:
                        continue
                    created = int(record.get("created_at", 0) or 0)
                    seen = int(record.get("last_seen_at", created) or created)
                except (TypeError, ValueError):
                    continue
                if created + _TOKEN_TTL <= now:
                    continue
                device_id = str(record.get("device_id", "")).strip()[:128]
                if device_id:
                    device_ids.add(device_id)
                last_seen = max(last_seen, seen)
        for device_id, record in banned_devices.items():
            if isinstance(record, dict) and user_key in {str(owner) for owner in record.get("user_ids", [])}:
                device_ids.add(str(device_id))
        banned_device_ids = sorted(device_id for device_id in device_ids if device_id in banned_devices)
        telegram_banned = user_key in banned_users
        return {
            "user_id": user_id,
            "device_ids": sorted(device_ids),
            "last_seen_at": last_seen,
            "banned": telegram_banned or bool(banned_device_ids),
            "telegram_banned": telegram_banned,
            "banned_device_ids": banned_device_ids,
            "registered": bool(device_ids or last_seen),
        }


def create_app_support_message(user_id: int, device_id: str, message: str) -> dict[str, Any]:
    """Store an APK support ticket. Callers authenticate the app token first."""
    user_id = int(user_id)
    device_id = str(device_id or "").strip()[:128]
    text = "".join(char for char in str(message or "") if char in "\n\t" or ord(char) >= 32 and ord(char) != 127).strip()
    if user_id <= 0 or not device_id:
        raise ValueError("invalid_support_identity")
    if not text or len(text) > 2000:
        raise ValueError("invalid_support_message")
    now = int(time.time())
    with _LOCK:
        state = _read()
        tickets = state.get("app_support_messages", [])
        tickets = tickets if isinstance(tickets, list) else []
        recent = sum(1 for item in tickets if isinstance(item, dict)
                     and int(item.get("user_id", 0) or 0) == user_id
                     and now - int(item.get("created_at", 0) or 0) < 3600)
        if recent >= 5:
            raise ValueError("support_rate_limited")
        ticket_id = secrets.token_hex(6).upper()
        ticket = {
            "id": ticket_id,
            "user_id": user_id,
            "device_id": device_id,
            "message": text,
            "created_at": now,
            "status": "open",
        }
        tickets.append(ticket)
        state["app_support_messages"] = tickets[-5000:]
        _write(state)
        return copy.deepcopy(ticket)


def app_support_messages_page(page: int = 0, page_size: int = 3) -> tuple[list[dict[str, Any]], int]:
    """Return a newest-first support inbox for the Telegram bot's APK admin panel."""
    page = max(0, int(page))
    page_size = max(1, min(10, int(page_size)))
    with _LOCK:
        state = _read()
        tickets = state.get("app_support_messages", [])
        tickets = [copy.deepcopy(item) for item in tickets if isinstance(item, dict)] if isinstance(tickets, list) else []
        tickets.sort(key=lambda item: (int(item.get("created_at", 0) or 0), str(item.get("id", ""))), reverse=True)
        total = len(tickets)
        return tickets[page * page_size:(page + 1) * page_size], total


def app_support_message_by_id(ticket_id: str) -> dict[str, Any] | None:
    ticket_id = str(ticket_id or "").strip().upper()
    if not ticket_id:
        return None
    with _LOCK:
        state = _read()
        tickets = state.get("app_support_messages", [])
        if not isinstance(tickets, list):
            return None
        for ticket in tickets:
            if isinstance(ticket, dict) and str(ticket.get("id", "")).upper() == ticket_id:
                return copy.deepcopy(ticket)
    return None
