"""Servidor estático de la tienda ToDus S3 para el dominio generado por Railway."""
from __future__ import annotations

import mimetypes
import json
import os
import re
import time
import urllib.error
import urllib.request
from html import escape
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import BoundedSemaphore, Lock, Thread
from typing import Callable
from urllib.parse import parse_qs, unquote, urlsplit
import sync_api
import todus_s3_storage
from sync_api import consume_pairing_code, deliveries_for_token, revoke_token
from todus_s3_storage import delete_from_todus_s3

WEB_ROOT = Path(__file__).resolve().parent / "web"
SITE_STARTED_AT = time.time()
_VISITOR_LOCK = Lock()
_VISITOR_LAST_SEEN: dict[str, float] = {}
_TOTAL_VISITS = 0
_MAX_VISITORS = 5000
_VISITOR_RE = re.compile(r"^[A-Za-z0-9-]{8,96}$")
_DEFAULT_ORIGIN = "https://tod4s3-production.up.railway.app"
_PROFILE_CHECKER: Callable[[int], dict] | None = None
_PROFILE_PHOTO_CHECKER: Callable[[int], bytes | None] | None = None
_APP_TASK_BRIDGE: Callable[[str, int, dict], tuple[dict, int]] | None = None
_APP_BACKUP_NOTIFIER: Callable[[str], None] | None = None
_APP_TASK_BRIDGE_LOCK = Lock()
_AI_RATE_LOCK = Lock()
_AI_REQUESTS_BY_USER: dict[int, list[float]] = {}
_AI_DAILY_REQUESTS_BY_USER: dict[int, list[float]] = {}
_AI_DAILY_GLOBAL_REQUESTS: list[float] = []
_AI_GATEWAY_SEMAPHORE = BoundedSemaphore(2)


def _dispatch_ai_chat(token: str, body: dict) -> tuple[dict, int]:
    """Proxy text-only AI chat for currently verified app Premium users."""
    identity, error, status = _authorize_app_token(token)
    if error:
        return error, status
    user_id = int(identity["user_id"])
    checker = _PROFILE_CHECKER
    if checker is None:
        return {"ok": False, "error": "profile_unavailable"}, 503
    try:
        profile = checker(user_id)
        if not isinstance(profile, dict):
            raise ValueError("invalid_profile")
        plan = str(profile.get("plan", ""))
        premium = profile.get("premium") is True
        verified = plan == "owner" or (
            premium and sync_api.premium_activation_status(
                user_id, str(identity.get("device_id", "")), profile.get("expires_at", ""),
            ) == "verified"
        )
    except Exception:
        return {"ok": False, "error": "profile_unavailable"}, 503
    if not verified or (plan != "owner" and (not premium or plan != "premium")):
        return {"ok": False, "error": "premium_required"}, 403

    api_key = os.getenv("IASINCENSURA", "").strip()
    if not api_key:
        return {"ok": False, "error": "ai_not_configured"}, 503
    if len(api_key) < 24 or any(char.isspace() for char in api_key):
        return {"ok": False, "error": "ai_not_configured"}, 503

    messages = body.get("messages")
    if not isinstance(messages, list) or not 1 <= len(messages) <= 12:
        return {"ok": False, "error": "invalid_messages"}, 400
    clean_messages: list[dict[str, str]] = []
    total_chars = 0
    for item in messages:
        if not isinstance(item, dict) or item.get("role") not in {"user", "assistant"}:
            return {"ok": False, "error": "invalid_messages"}, 400
        content = item.get("content")
        if not isinstance(content, str):
            return {"ok": False, "error": "invalid_messages"}, 400
        content = content.strip()
        if not content or len(content) > 4000:
            return {"ok": False, "error": "invalid_messages"}, 400
        total_chars += len(content)
        if total_chars > 16000:
            return {"ok": False, "error": "conversation_too_long"}, 413
        clean_messages.append({"role": item["role"], "content": content})
    if clean_messages[-1]["role"] != "user":
        return {"ok": False, "error": "invalid_messages"}, 400

    now = time.monotonic()
    with _AI_RATE_LOCK:
        recent = [seen for seen in _AI_REQUESTS_BY_USER.get(user_id, []) if now - seen < 60]
        if len(recent) >= 8:
            return {"ok": False, "error": "ai_rate_limited"}, 429
        try:
            daily_limit = max(1, min(100_000, int(os.getenv("IASINCENSURA_USER_DAILY_LIMIT", "80"))))
        except ValueError:
            daily_limit = 80
        daily_user = [seen for seen in _AI_DAILY_REQUESTS_BY_USER.get(user_id, []) if now - seen < 86_400]
        if len(daily_user) >= daily_limit:
            return {"ok": False, "error": "ai_daily_limit"}, 429
        try:
            global_limit = max(1, min(100_000, int(os.getenv("IASINCENSURA_DAILY_REQUEST_LIMIT", "1000"))))
        except ValueError:
            global_limit = 1000
        daily_global = [seen for seen in _AI_DAILY_GLOBAL_REQUESTS if now - seen < 86_400]
        if len(daily_global) >= global_limit:
            return {"ok": False, "error": "ai_service_daily_limit"}, 429
        recent.append(now)
        daily_user.append(now)
        daily_global.append(now)
        _AI_REQUESTS_BY_USER[user_id] = recent
        _AI_DAILY_REQUESTS_BY_USER[user_id] = daily_user
        _AI_DAILY_GLOBAL_REQUESTS[:] = daily_global
        if len(_AI_REQUESTS_BY_USER) > 5000:
            oldest_user = min(
                (key for key in _AI_DAILY_REQUESTS_BY_USER if key != user_id),
                key=lambda key: max(_AI_DAILY_REQUESTS_BY_USER[key], default=now),
                default=None,
            )
            if oldest_user is not None:
                _AI_DAILY_REQUESTS_BY_USER.pop(oldest_user, None)
                _AI_REQUESTS_BY_USER.pop(oldest_user, None)
            _AI_DAILY_REQUESTS_BY_USER[user_id] = daily_user

    request_messages = [{
        "role": "system",
        "content": "Eres AiTod4S3, un asistente de texto útil y claro. Responde en el idioma del usuario. No afirmes que puedes ver su cuenta, archivos ni datos de Tod4S3 a menos que el usuario los describa.",
    }, *clean_messages]
    request_body = json.dumps({
        "model": "notrack-uncensored",
        "messages": request_messages,
        "max_tokens": 512,
        "temperature": 0.7,
        "stream": False,
    }, separators=(",", ":")).encode("utf-8")
    outgoing = urllib.request.Request(
        "https://api.iasincensura.ai/v1/chat/completions",
        data=request_body,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    if not _AI_GATEWAY_SEMAPHORE.acquire(blocking=False):
        return {"ok": False, "error": "ai_busy"}, 429
    try:
        with urllib.request.urlopen(outgoing, timeout=60) as response:
            provider_data = json.loads(response.read(1_000_000).decode("utf-8"))
    except urllib.error.HTTPError as exc:
        provider_error = {
            401: "ai_provider_auth", 402: "ai_credits_exhausted",
            429: "ai_busy", 503: "ai_unavailable",
        }.get(exc.code, "ai_provider_error" if exc.code < 500 else "ai_unavailable")
        return {"ok": False, "error": provider_error}, (429 if exc.code == 429 else 503)
    except (TimeoutError, urllib.error.URLError, OSError, ValueError, json.JSONDecodeError):
        return {"ok": False, "error": "ai_unavailable"}, 502
    finally:
        _AI_GATEWAY_SEMAPHORE.release()

    try:
        answer = provider_data["choices"][0]["message"]["content"]
        if not isinstance(answer, str) or not answer.strip():
            raise ValueError("empty_answer")
    except (KeyError, IndexError, TypeError, ValueError):
        return {"ok": False, "error": "ai_invalid_response"}, 502
    # Do not persist or log prompts, replies, provider response bodies, or the API key.
    return {"ok": True, "reply": answer.strip()}, 200


def configure_app_task_bridge(bridge: Callable[[str, int, dict], tuple[dict, int]] | None) -> None:
    """Conecta las rutas móviles con el worker, respetando su event loop."""
    global _APP_TASK_BRIDGE
    with _APP_TASK_BRIDGE_LOCK:
        _APP_TASK_BRIDGE = bridge


def configure_app_backup_notifier(notifier: Callable[[str], None] | None) -> None:
    """Conecta cambios de soporte APK con el backup cifrado del bot."""
    global _APP_BACKUP_NOTIFIER
    _APP_BACKUP_NOTIFIER = notifier


def _dispatch_app_task(token: str, action: str, payload: dict) -> tuple[dict, int]:
    identity, error, status = _authorize_app_token(token)
    if error:
        return error, status
    user_id = int(identity["user_id"])
    if action == "enqueue":
        url = payload.get("url")
        if (not isinstance(url, str) or not url.strip() or len(url.strip()) > 2048
                or any(ord(char) < 32 for char in url)):
            return {"ok": False, "error": "invalid_url"}, 400
        payload = {"url": url.strip()}
    elif action == "cancel":
        task_id = payload.get("task_id")
        if not isinstance(task_id, str) or not task_id.strip() or len(task_id) > 200:
            return {"ok": False, "error": "invalid_task_id"}, 400
        payload = {"task_id": task_id.strip()}
    elif action == "delete_delivery":
        task_id = payload.get("task_id")
        if not isinstance(task_id, str) or not task_id.strip() or len(task_id) > 200:
            return {"ok": False, "error": "invalid_task_id"}, 400
        payload = {"task_id": task_id.strip()}
    elif action == "clear_cloud":
        payload = {}
    elif action != "list":
        return {"ok": False, "error": "invalid_action"}, 400
    with _APP_TASK_BRIDGE_LOCK:
        bridge = _APP_TASK_BRIDGE
    if bridge is None:
        return {"ok": False, "error": "task_service_unavailable"}, 503
    try:
        result = bridge(action, user_id, payload)
    except TimeoutError:
        return {"ok": False, "error": "task_api_timeout"}, 504
    except Exception:
        return {"ok": False, "error": "task_api_unavailable"}, 503
    if (not isinstance(result, tuple) or len(result) != 2
            or not isinstance(result[0], dict) or not isinstance(result[1], int)):
        return {"ok": False, "error": "task_api_unavailable"}, 503
    return result


def _authorize_app_token(token: str) -> tuple[dict | None, dict | None, int]:
    """Validate a mobile session and apply both Telegram-ID and device bans."""
    identity = sync_api.app_identity_for_token(token)
    if identity is None:
        # Compatibility with narrow test/provider adapters exposing only user_id.
        user_id = sync_api.user_id_for_token(token)
        if user_id is None:
            return None, {"ok": False, "error": "unauthorized"}, 401
        identity = {"user_id": int(user_id), "device_id": ""}
    ban = sync_api.app_access_ban(int(identity["user_id"]), str(identity.get("device_id", "")))
    if ban:
        return identity, {"ok": False, "error": "app_banned"}, 403
    return identity, None, 200


def _validated_delivery_url(url: str, user_id: int) -> bool:
    """Check that a saved part belongs to this user's configured ToDus namespace."""
    if not isinstance(url, str) or not url:
        return False
    try:
        prefix = os.getenv("TODUS_S3_UPLOAD_PREFIX", "users/df").strip("/") or "users/df"
        todus_s3_storage.user_object_key_from_url(url, user_id, object_prefix=prefix)
    except (TypeError, ValueError):
        return False
    return True


def _delivery_cloud_deletable(row: dict, user_id: int) -> bool:
    """Whether every part is an S3 object in this authenticated user's namespace."""
    backend = row.get("storage_backend")
    if backend and backend != "s3":
        return False
    parts = row.get("parts")
    return bool(isinstance(parts, list) and parts
                and all(_validated_delivery_url(part, user_id) for part in parts))


def _delivery_todus_deletable(row: dict) -> bool:
    parts = row.get("parts")
    return bool(row.get("storage_backend") == "todus_native"
                and row.get("remote_delete_supported") is True
                and isinstance(row.get("task_id"), str) and row["task_id"].strip()
                and isinstance(parts, list) and parts)


def delete_delivery_for_token(token: str, task_id: str) -> tuple[dict, int]:
    """Delete an owned delivery; network calls occur outside sync_api's lock."""
    identity, error, status = _authorize_app_token(token)
    if error:
        return error, status
    user_id = int(identity["user_id"])
    if not isinstance(task_id, str) or not task_id.strip() or len(task_id) > 200:
        return {"ok": False, "error": "invalid_task_id"}, 400
    task_id = task_id.strip()
    row = sync_api.delivery_for_user(user_id, task_id)
    if row is None:
        if sync_api.has_delivery_without_task_id(user_id):
            return {"ok": False, "error": "legacy_delivery_incomplete",
                    "message": "Hay una entrega antigua sin task_id; no se intentó borrar ninguna parte."}, 409
        return {"ok": False, "error": "delivery_not_found"}, 404
    if row.get("source", "tod4s3_bot") != "tod4s3_bot":
        return {"ok": False, "error": "delivery_source_not_supported",
                "message": "Esta entrega no fue creada por el bot y no se puede borrar desde aquí."}, 409
    parts = row.get("parts")
    if row.get("task_id") != task_id or not isinstance(parts, list) or not parts:
        return {"ok": False, "error": "legacy_delivery_incomplete",
                "message": "La entrega no contiene un task_id o partes válidas; no se intentó borrar."}, 409
    if not _delivery_cloud_deletable(row, user_id):
        if _delivery_todus_deletable(row):
            return _dispatch_app_task(token, "delete_delivery", {"task_id": task_id})
        return {"ok": False, "error": "delivery_not_deletable",
                "message": "La entrega no es un objeto Nube S3 propio; no se borró ningún archivo."}, 409
    unique_parts = list(dict.fromkeys(parts))
    try:
        for url in unique_parts:
            delete_from_todus_s3(url)
    except Exception:
        # Do not expose storage responses, URLs, credentials, or exception text.
        return {"ok": False, "error": "delete_failed",
                "message": "No se pudieron borrar todas las partes; la entrega se conserva para reintentar."}, 502
    if not sync_api.remove_delivery_for_user(user_id, task_id, row):
        return {"ok": False, "error": "delivery_changed",
                "message": "La entrega cambió durante el borrado; la fila se conserva."}, 409
    return {"ok": True, "task_id": task_id, "deleted_parts": len(unique_parts)}, 200


def _safe_site_origin(handler: "StoreHandler") -> str:
    """Obtiene un origen seguro sin reflejar un Host arbitrario en HTML."""
    configured = os.getenv("PUBLIC_SITE_URL", "").strip().rstrip("/")
    if configured:
        configured = configured.split("#", 1)[0].rstrip("/")
        if re.fullmatch(r"https://[A-Za-z0-9.-]+(?::\d+)?", configured):
            return configured
    forwarded_proto = handler.headers.get("X-Forwarded-Proto", "https").split(",", 1)[0].strip().lower()
    proto = forwarded_proto if forwarded_proto in {"http", "https"} else "https"
    host = handler.headers.get("Host", "").split(",", 1)[0].strip()
    if re.fullmatch(r"[A-Za-z0-9.-]+(?::\d{1,5})?", host):
        return escape(f"{proto}://{host}", quote=True)
    return _DEFAULT_ORIGIN


class StoreHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(WEB_ROOT), **kwargs)

    def do_GET(self):  # noqa: N802
        parsed = urlsplit(unquote(self.path))
        path = parsed.path
        if path == "/api/app/profile/photo":
            authorization = self.headers.get("Authorization", "")
            token = authorization[7:].strip() if authorization.lower().startswith("bearer ") else ""
            identity, error, status = _authorize_app_token(token)
            if error:
                self._json_response(error, status)
                return
            user_id = int(identity["user_id"])
            checker = _PROFILE_PHOTO_CHECKER
            if checker is None:
                self._json_response({"ok": False, "error": "profile_photo_unavailable"}, 503)
                return
            try:
                photo = checker(user_id)
            except Exception:
                photo = None
            if not isinstance(photo, (bytes, bytearray)) or not photo:
                self._json_response({"ok": False, "error": "profile_photo_not_found"}, 404)
                return
            if len(photo) > 1024 * 1024:
                self._json_response({"ok": False, "error": "profile_photo_too_large"}, 413)
                return
            body = bytes(photo)
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Cache-Control", "private, max-age=3600")
            self.send_header("Vary", "Authorization")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/app/profile":
            authorization = self.headers.get("Authorization", "")
            token = authorization[7:].strip() if authorization.lower().startswith("bearer ") else ""
            identity, error, status = _authorize_app_token(token)
            if error:
                self._json_response(error, status)
                return
            user_id = int(identity["user_id"])
            checker = _PROFILE_CHECKER
            if checker is None:
                self._json_response({"ok": False, "error": "profile_unavailable"}, 503)
                return
            try:
                profile = checker(user_id)
                if (not isinstance(profile, dict)
                        or not isinstance(profile.get("premium"), bool)
                        or not isinstance(profile.get("plan"), str)
                        or not profile["plan"]):
                    raise ValueError("invalid_profile")
            except Exception:
                self._json_response({"ok": False, "error": "profile_unavailable"}, 503)
                return
            premium_verified = profile.get("plan") == "owner" or (
                profile.get("premium") is True
                and sync_api.premium_activation_status(
                    user_id, identity["device_id"], profile.get("expires_at", ""),
                ) == "verified"
            )
            expires = profile.get("expires", "")
            if not isinstance(expires, str):
                expires = ""
            remaining = profile.get("days_remaining")
            if isinstance(remaining, bool) or not isinstance(remaining, int) or remaining < 0:
                remaining = None
            payload = {
                "premium": profile["premium"],
                "plan": profile["plan"],
                "expires": expires[:32],
                "days_remaining": remaining,
                "premium_verified": premium_verified,
            }
            quota = profile.get("quota")
            if isinstance(quota, dict):
                limit = quota.get("limit_bytes")
                used = quota.get("used_bytes")
                quota_remaining = quota.get("remaining_bytes")
                percent = quota.get("percent_used")
                reset_in = quota.get("reset_in_seconds")
                if (all(isinstance(value, int) and not isinstance(value, bool) and value >= 0
                        for value in (limit, used, quota_remaining, percent, reset_in))
                        and limit > 0 and percent <= 100):
                    payload["quota"] = {
                        "limit_bytes": limit,
                        "used_bytes": min(used, limit),
                        "remaining_bytes": min(quota_remaining, limit),
                        "percent_used": percent,
                        "reset_in_seconds": reset_in,
                    }
            self._json_response(payload)
            return
        if path == "/api/app/deliveries":
            token = self.headers.get("Authorization", "")
            token = token[7:].strip() if token.lower().startswith("bearer ") else ""
            identity, error, status = _authorize_app_token(token)
            if error:
                self._json_response(error, status)
                return
            deliveries = deliveries_for_token(token)
            user_id = int(identity["user_id"])
            if deliveries is None:
                self._json_response({"ok": False, "error": "unauthorized"}, 401)
            else:
                for row in deliveries:
                    row["cloud_deletable"] = _delivery_cloud_deletable(row, user_id)
                    row["todus_deletable"] = _delivery_todus_deletable(row)
                self._json_response({"ok": True, "deliveries": deliveries})
            return
        if path == "/api/app/tasks":
            authorization = self.headers.get("Authorization", "")
            token = authorization[7:].strip() if authorization.lower().startswith("bearer ") else ""
            payload, status = _dispatch_app_task(token, "list", {})
            self._json_response(payload, status)
            return
        if path == "/api/site-stats":
            visitor = parse_qs(parsed.query).get("visitor", [""])[0][:96]
            if not _VISITOR_RE.fullmatch(visitor):
                visitor = ""
            now = time.time()
            global _TOTAL_VISITS, _VISITOR_LAST_SEEN
            with _VISITOR_LOCK:
                if visitor and visitor not in _VISITOR_LAST_SEEN:
                    _TOTAL_VISITS += 1
                if visitor:
                    _VISITOR_LAST_SEEN[visitor] = now
                cutoff = now - 90
                active = sum(1 for seen in _VISITOR_LAST_SEEN.values() if seen >= cutoff)
                # Keep the in-memory table bounded on a long-running service.
                _VISITOR_LAST_SEEN = {key: seen for key, seen in _VISITOR_LAST_SEEN.items() if seen >= cutoff}
                if len(_VISITOR_LAST_SEEN) > _MAX_VISITORS:
                    oldest = sorted(_VISITOR_LAST_SEEN, key=_VISITOR_LAST_SEEN.get)
                    for key in oldest[:len(_VISITOR_LAST_SEEN) - _MAX_VISITORS]:
                        _VISITOR_LAST_SEEN.pop(key, None)
                payload = {
                    "total_visits": _TOTAL_VISITS,
                    "online_now": active,
                    "uptime_seconds": max(0, int(now - SITE_STARTED_AT)),
                }
            body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path in {"/health", "/healthz"}:
            body = b'{"ok":true,"service":"toduss3store"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/manus-routes.json":
            self.path = "/manus-routes.json"
        elif path == "/":
            origin = _safe_site_origin(self)
            body = (WEB_ROOT / "index.html").read_bytes().replace(b"__SITE_ORIGIN__", origin.encode("utf-8"))
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        super().do_GET()

    def do_POST(self):  # noqa: N802
        parsed = urlsplit(unquote(self.path))
        if parsed.path not in {
            "/api/app/pair", "/api/app/revoke", "/api/app/deliveries/delete",
            "/api/app/download", "/api/app/tasks/cancel", "/api/app/cloud/clear",
            "/api/app/premium/claim", "/api/app/support", "/api/app/ai/chat",
        }:
            self._json_response({"ok": False, "error": "not_found"}, 404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            max_body_length = 65_536 if parsed.path == "/api/app/ai/chat" else 8192
            if length < 0 or length > max_body_length:
                raise ValueError("body_too_large")
            body = json.loads(self.rfile.read(length) or b"{}")
            if not isinstance(body, dict):
                raise ValueError("invalid_body")
        except (ValueError, TypeError, json.JSONDecodeError):
            self._json_response({"ok": False, "error": "invalid_body"}, 400)
            return
        if parsed.path == "/api/app/pair":
            code = body.get("code", "")
            device_id = body.get("device_id", "")
            if sync_api.pairing_code_ban(code, device_id):
                self._json_response({"ok": False, "error": "app_banned"}, 403)
                return
            token = consume_pairing_code(code, device_id)
            if not token:
                if sync_api.pairing_code_ban(code, device_id):
                    self._json_response({"ok": False, "error": "app_banned"}, 403)
                else:
                    self._json_response({"ok": False, "error": "invalid_or_expired_code"}, 401)
            else:
                self._json_response({"ok": True, "token": token})
            return
        token = self.headers.get("Authorization", "")
        token = token[7:].strip() if token.lower().startswith("bearer ") else ""
        if parsed.path == "/api/app/ai/chat":
            payload, status = _dispatch_ai_chat(token, body)
            self._json_response(payload, status)
            return
        if parsed.path == "/api/app/support":
            identity = sync_api.app_identity_for_token(token)
            if identity is None:
                identity = sync_api.support_identity_from_pairing(
                    body.get("code", ""), body.get("device_id", ""),
                )
            if identity is None:
                self._json_response({"ok": False, "error": "unauthorized_or_pairing_expired"}, 401)
                return
            try:
                ticket = sync_api.create_app_support_message(
                    int(identity["user_id"]), str(identity["device_id"]), body.get("message", ""),
                )
            except ValueError as exc:
                reason = str(exc)
                if reason == "support_rate_limited":
                    self._json_response({"ok": False, "error": reason}, 429)
                else:
                    self._json_response({"ok": False, "error": reason or "invalid_support_message"}, 400)
                return
            notifier = _APP_BACKUP_NOTIFIER
            if notifier is not None:
                try:
                    notifier("mensaje de soporte APK")
                except Exception:
                    pass
            self._json_response({"ok": True, "ticket_id": ticket["id"]}, 201)
            return
        if parsed.path != "/api/app/revoke":
            _, error, status = _authorize_app_token(token)
            if error:
                self._json_response(error, status)
                return
        if parsed.path == "/api/app/premium/claim":
            identity = sync_api.app_identity_for_token(token)
            checker = _PROFILE_CHECKER
            if identity is None:
                self._json_response({"ok": False, "error": "unauthorized"}, 401)
                return
            if checker is None:
                self._json_response({"ok": False, "error": "profile_unavailable"}, 503)
                return
            try:
                profile = checker(identity["user_id"])
                if (not isinstance(profile, dict) or profile.get("premium") is not True
                        or profile.get("plan") not in {"premium", "owner"}):
                    self._json_response({"ok": False, "error": "premium_not_active"}, 409)
                    return
                expires_at = profile.get("expires_at", "")
                ok, error = sync_api.claim_premium_activation_token(
                    body.get("token", ""), identity["user_id"],
                    identity["device_id"], expires_at,
                )
                if not ok:
                    status = 403 if error in {"invalid_token", "device_mismatch"} else 409
                    self._json_response({"ok": False, "error": error}, status)
                    return
            except Exception:
                self._json_response({"ok": False, "error": "profile_unavailable"}, 503)
                return
            self._json_response({
                "ok": True, "verified": True,
                "expires": str(profile.get("expires", ""))[:32],
            })
            return
        if parsed.path == "/api/app/download":
            payload, status = _dispatch_app_task(token, "enqueue", body)
            self._json_response(payload, status)
            return
        if parsed.path == "/api/app/tasks/cancel":
            payload, status = _dispatch_app_task(token, "cancel", body)
            self._json_response(payload, status)
            return
        if parsed.path == "/api/app/cloud/clear":
            payload, status = _dispatch_app_task(token, "clear_cloud", {})
            self._json_response(payload, status)
            return
        if parsed.path == "/api/app/deliveries/delete":
            task_id = body.get("task_id")
            user_id = sync_api.user_id_for_token(token)
            row = sync_api.delivery_for_user(user_id, task_id) if user_id is not None and isinstance(task_id, str) else None
            if _delivery_todus_deletable(row or {}):
                payload, status = _dispatch_app_task(token, "delete_delivery", {"task_id": task_id})
            else:
                payload, status = delete_delivery_for_token(token, task_id)
            self._json_response(payload, status)
            return
        if not revoke_token(token):
            self._json_response({"ok": False, "error": "unauthorized"}, 401)
        else:
            self._json_response({"ok": True})

    def do_OPTIONS(self):  # noqa: N802
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()

    def _json_response(self, payload: dict, status: int = 200):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_HEAD(self):  # noqa: N802
        self.do_GET()

    def log_message(self, format, *args):
        # Mantener los logs de Railway útiles sin registrar parámetros privados.
        print(f"[web] {self.address_string()} - {format % args}", flush=True)

    def end_headers(self):  # noqa: N802
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "strict-origin-when-cross-origin")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com; "
            "font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; media-src 'self'; "
            "connect-src 'self'; base-uri 'self'; form-action 'self'",
        )
        super().end_headers()


def start_store_server(
    profile_checker: Callable[[int], dict] | None = None,
    profile_photo_checker: Callable[[int], bytes | None] | None = None,
) -> Thread:
    global _PROFILE_CHECKER, _PROFILE_PHOTO_CHECKER
    _PROFILE_CHECKER = profile_checker
    _PROFILE_PHOTO_CHECKER = profile_photo_checker
    port = int(os.getenv("PORT", "8080"))
    server = ThreadingHTTPServer(("0.0.0.0", port), StoreHandler)
    thread = Thread(target=server.serve_forever, name="todus-store-http", daemon=True)
    thread.start()
    print(f"Tienda ToDus S3 disponible en el puerto {port}", flush=True)
    return thread
