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
    headers = {"Authorization": f"Bearer {token}"} if token is not None else {}
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
        assert payload == {"ok": False, "error": "app_banned", "reason": "access_banned"}
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
        assert payload["reason"] == "access_banned"
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



def test_update_decline_three_strikes_notify_once_and_device_status_tracks_unban(monkeypatch, tmp_path):
    monkeypatch.setattr(web_store.sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    manifest = json.loads((web_store.WEB_ROOT / "android-update.json").read_text(encoding="utf-8"))
    device_id = "a" * 64
    notifications = []
    web_store.configure_app_update_ban_notifier(notifications.append)
    server, thread = _server()
    try:
        declines = []
        for number in range(1, 4):
            status, payload = _request(
                server, "POST", "/api/app/update-decline",
                {"device_id": device_id, "version_code": manifest["version_code"],
                 "version_name": manifest["version_name"], "request_id": f"decline-{number}"},
                token=None,
            )
            assert status == 200
            declines.append(payload)
        assert [item["count"] for item in declines] == [1, 2, 3]
        assert [item["blocked"] for item in declines] == [False, False, True]
        assert declines[2]["event"]["user_id"] is None
        assert len(notifications) == 1
        assert notifications[0]["ban_id"] == declines[2]["event"]["ban_id"]

        duplicate_status, duplicate = _request(
            server, "POST", "/api/app/update-decline",
            {"device_id": device_id, "version_code": manifest["version_code"],
             "version_name": manifest["version_name"], "request_id": "decline-3"}, token=None,
        )
        assert duplicate_status == 200
        assert duplicate["count"] == 3
        assert duplicate["event"] is None
        assert len(notifications) == 1

        status, device_status = _request(
            server, "POST", "/api/app/device-status", {"device_id": device_id}, token=None,
        )
        assert status == 200 and device_status["banned"] is True
        assert device_status["reason"] == "Este teléfono rechazó tres veces la actualización v" + manifest["version_name"] + " (código " + str(manifest["version_code"]) + ")."
        other_status, other_device = _request(
            server, "POST", "/api/app/device-status", {"device_id": "b" * 64}, token=None,
        )
        assert other_status == 200 and other_device["banned"] is False

        ban_id = declines[2]["event"]["ban_id"]
        assert web_store.sync_api.unban_app_device_by_ban_id(ban_id, admin_id=900)["changed"] is True
        status, after_unban = _request(
            server, "POST", "/api/app/device-status", {"device_id": device_id}, token=None,
        )
        assert status == 200 and after_unban == {"ok": True, "banned": False}
        state = web_store.sync_api._read()
        assert state["app_bans"]["users"] == {}
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
        web_store.configure_app_update_ban_notifier(None)


def test_update_decline_rejects_bad_device_version_and_token_mismatch(monkeypatch, tmp_path):
    monkeypatch.setattr(web_store.sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    manifest = json.loads((web_store.WEB_ROOT / "android-update.json").read_text(encoding="utf-8"))
    user_device = "c" * 64
    token = web_store.sync_api.consume_pairing_code(
        web_store.sync_api.create_pairing_code(511), user_device,
    )
    assert token
    server, thread = _server()
    try:
        base = {"device_id": user_device, "version_code": manifest["version_code"],
                "version_name": manifest["version_name"], "request_id": "bad-check"}
        status, payload = _request(
            server, "POST", "/api/app/update-decline", {**base, "device_id": "not-a-device-id"}, token=None,
        )
        assert status == 400 and payload["error"] == "invalid_device_id"
        status, payload = _request(
            server, "POST", "/api/app/update-decline", {**base, "version_code": manifest["version_code"] - 1},
            token=None,
        )
        assert status == 409 and payload["error"] == "version_not_published"
        assert web_store.sync_api._read()["app_update_rejections"]["counters"] == {}

        status, payload = _request(server, "POST", "/api/app/update-decline", base, token=token)
        assert status == 200 and payload["count"] == 1
        status, payload = _request(
            server, "POST", "/api/app/update-decline", {**base, "device_id": "d" * 64}, token=token,
        )
        assert status == 403 and payload["error"] == "device_mismatch"
        status, payload = _request(server, "POST", "/api/app/device-status", {"device_id": user_device}, token="bad-token")
        assert status == 401 and payload["error"] == "unauthorized"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
