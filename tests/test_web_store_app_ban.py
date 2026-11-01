import http.client
import json
import threading
from http.server import ThreadingHTTPServer
from unittest.mock import patch

import web_store


def _server():
    server = ThreadingHTTPServer(("127.0.0.1", 0), web_store.StoreHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


def _request(server, method, path, body=None, token="mobile-token"):
    connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
    headers = {"Authorization": f"Bearer {token}"}
    encoded = json.dumps(body) if body is not None else None
    if body is not None:
        headers["Content-Type"] = "application/json"
    connection.request(method, path, body=encoded, headers=headers)
    response = connection.getresponse()
    payload = json.loads(response.read().decode("utf-8"))
    status = response.status
    connection.close()
    return status, payload


def test_banned_app_gets_specific_profile_gate(monkeypatch):
    monkeypatch.setattr(
        web_store.sync_api, "app_identity_for_token",
        lambda token: {"user_id": 12345, "device_id": "device-hash"} if token == "mobile-token" else None,
    )
    monkeypatch.setattr(web_store.sync_api, "app_access_ban", lambda uid, did: {"scope": "telegram"})
    monkeypatch.setattr(web_store, "_PROFILE_CHECKER", lambda uid: (_ for _ in ()).throw(AssertionError("must not load profile")))
    server, thread = _server()
    try:
        status, payload = _request(server, "GET", "/api/app/profile")
        assert status == 403
        assert payload == {"ok": False, "error": "app_banned"}
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_ban_blocks_task_action_but_allows_session_revocation(monkeypatch):
    monkeypatch.setattr(
        web_store.sync_api, "app_identity_for_token",
        lambda token: {"user_id": 12345, "device_id": "device-hash"} if token == "mobile-token" else None,
    )
    monkeypatch.setattr(web_store.sync_api, "app_access_ban", lambda uid, did: {"scope": "device"})
    bridge = []
    web_store.configure_app_task_bridge(lambda *args: (bridge.append(args) or {"ok": True}, 202))
    monkeypatch.setattr(web_store, "revoke_token", lambda token: token == "mobile-token")
    server, thread = _server()
    try:
        status, payload = _request(server, "POST", "/api/app/download", {"url": "https://example.com/file"})
        assert status == 403
        assert payload["error"] == "app_banned"
        assert bridge == []

        status, payload = _request(server, "POST", "/api/app/revoke", {})
        assert status == 200
        assert payload["ok"] is True
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
        web_store.configure_app_task_bridge(None)


def test_pairing_attempt_on_banned_device_returns_app_banned(monkeypatch):
    monkeypatch.setattr(web_store.sync_api, "pairing_code_ban", lambda code, device: {"scope": "device"})
    server, thread = _server()
    try:
        status, payload = _request(
            server, "POST", "/api/app/pair",
            {"code": "ABCDEF12", "device_id": "device-hash"}, token="",
        )
        assert status == 403
        assert payload["error"] == "app_banned"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_banned_app_can_send_support_ticket_to_bot_inbox(monkeypatch):
    identity = {"user_id": 12345, "device_id": "device-hash"}
    monkeypatch.setattr(web_store.sync_api, "app_identity_for_token", lambda token: identity if token == "mobile-token" else None)
    monkeypatch.setattr(
        web_store.sync_api, "app_access_ban",
        lambda *_args: (_ for _ in ()).throw(AssertionError("support must remain available to banned users")),
    )
    created = []
    monkeypatch.setattr(
        web_store.sync_api, "create_app_support_message",
        lambda user_id, device_id, message: (
            created.append((user_id, device_id, message))
            or {"id": "A1B2C3", "user_id": user_id, "device_id": device_id, "message": message}
        ),
    )
    backup_reasons = []
    web_store.configure_app_backup_notifier(backup_reasons.append)
    server, thread = _server()
    try:
        status, payload = _request(server, "POST", "/api/app/support", {"message": "Quiero solicitar revisión"})
        assert status == 201
        assert payload == {"ok": True, "ticket_id": "A1B2C3"}
        assert created == [(12345, "device-hash", "Quiero solicitar revisión")]
        assert backup_reasons == ["mensaje de soporte APK"]
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
        web_store.configure_app_backup_notifier(None)


def test_banned_device_without_token_can_send_support_with_pairing_code(monkeypatch):
    monkeypatch.setattr(web_store.sync_api, "app_identity_for_token", lambda _token: None)
    monkeypatch.setattr(
        web_store.sync_api, "support_identity_from_pairing",
        lambda code, device: {"user_id": 54321, "device_id": device} if code == "A1B2C3D4" else None,
    )
    created = []
    monkeypatch.setattr(
        web_store.sync_api, "create_app_support_message",
        lambda user_id, device_id, message: (
            created.append((user_id, device_id, message))
            or {"id": "TICKET2", "user_id": user_id, "device_id": device_id, "message": message}
        ),
    )
    server, thread = _server()
    try:
        status, payload = _request(
            server, "POST", "/api/app/support",
            {"message": "No puedo entrar", "code": "A1B2C3D4", "device_id": "device-hash"},
        )
        assert status == 201
        assert payload["ticket_id"] == "TICKET2"
        assert created == [(54321, "device-hash", "No puedo entrar")]
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_real_banned_pairing_code_creates_support_ticket_without_issuing_token(monkeypatch, tmp_path):
    monkeypatch.setattr(web_store.sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    code = web_store.sync_api.create_pairing_code(24680)
    device_id = "b" * 64
    web_store.sync_api.ban_app_user(24680, admin_id=900)
    server, thread = _server()
    try:
        status, payload = _request(
            server, "POST", "/api/app/support",
            {"message": "Solicito revisar el bloqueo", "code": code, "device_id": device_id},
        )
        assert status == 201
        assert payload["ticket_id"]
        assert web_store.sync_api.consume_pairing_code(code, device_id) is None
        ticket = web_store.sync_api.app_support_message_by_id(payload["ticket_id"])
        assert ticket["user_id"] == 24680
        assert ticket["device_id"] == device_id
        assert ticket["message"] == "Solicito revisar el bloqueo"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
