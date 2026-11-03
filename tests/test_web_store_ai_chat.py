import http.client
import io
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


def _post(server, body, token="mobile-token"):
    connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
    connection.request(
        "POST", "/api/app/ai/chat", body=json.dumps(body),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    response = connection.getresponse()
    payload = json.loads(response.read().decode("utf-8"))
    status = response.status
    connection.close()
    return status, payload


def _premium_identity(monkeypatch):
    monkeypatch.setattr(
        web_store.sync_api, "app_identity_for_token",
        lambda token: {"user_id": 992211, "device_id": "device-hash"} if token == "mobile-token" else None,
    )
    monkeypatch.setattr(web_store.sync_api, "app_access_ban", lambda *_args: None)
    monkeypatch.setattr(
        web_store, "_PROFILE_CHECKER",
        lambda _uid: {"premium": True, "plan": "premium", "expires_at": "2099-12-31"},
    )
    monkeypatch.setattr(web_store.sync_api, "premium_activation_status", lambda *_args: "verified")
    monkeypatch.setattr(web_store, "_AI_REQUESTS_BY_USER", {})
    monkeypatch.setattr(web_store, "_AI_DAILY_REQUESTS_BY_USER", {})
    monkeypatch.setattr(web_store, "_AI_DAILY_GLOBAL_REQUESTS", [])


def test_ai_chat_requires_verified_premium_before_upstream(monkeypatch):
    _premium_identity(monkeypatch)
    monkeypatch.setattr(
        web_store, "_PROFILE_CHECKER",
        lambda _uid: {"premium": False, "plan": "free", "expires_at": ""},
    )
    called = []
    monkeypatch.setattr(web_store.urllib.request, "urlopen", lambda *_a, **_k: called.append(True))
    server, thread = _server()
    try:
        status, payload = _post(server, {"messages": [{"role": "user", "content": "Hola"}]})
        assert status == 403
        assert payload == {"ok": False, "error": "premium_required"}
        assert called == []
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_ai_chat_calls_provider_with_server_key_and_text_only_payload(monkeypatch):
    _premium_identity(monkeypatch)
    test_secret = "sk-notrack-test-secret-not-for-production"
    monkeypatch.setenv("IASINCENSURA", test_secret)
    captured = {}

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, _limit):
            return json.dumps({"choices": [{"message": {"content": "Respuesta de prueba"}}]}).encode()

    def fake_urlopen(request, timeout):
        captured["url"] = request.full_url
        captured["auth"] = request.get_header("Authorization")
        captured["timeout"] = timeout
        captured["payload"] = json.loads(request.data.decode())
        return Response()

    monkeypatch.setattr(web_store.urllib.request, "urlopen", fake_urlopen)
    server, thread = _server()
    try:
        status, payload = _post(server, {"messages": [{"role": "user", "content": "Hola"}]})
        assert status == 200
        assert payload == {"ok": True, "reply": "Respuesta de prueba"}
        assert captured["url"] == "https://api.notrack.ai/v1/chat/completions"
        assert captured["auth"] == f"Bearer {test_secret}"
        assert captured["timeout"] == 60
        assert captured["payload"]["model"] == "notrack-uncensored"
        assert captured["payload"]["max_tokens"] == 512
        assert captured["payload"]["stream"] is False
        assert captured["payload"]["messages"][0]["role"] == "system"
        assert "asistente general" in captured["payload"]["messages"][0]["content"]
        assert "conciso pero completo" in captured["payload"]["messages"][0]["content"]
        assert "En cálculos, entrega el resultado y una fórmula breve" in captured["payload"]["messages"][0]["content"]
        assert "no finjas omnisciencia" in captured["payload"]["messages"][0]["content"]
        assert "No contestes preguntas sobre el repositorio" in captured["payload"]["messages"][0]["content"]
        assert captured["payload"]["messages"][-1] == {"role": "user", "content": "Hola"}
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_ai_chat_refuses_unconfigured_key_and_client_system_prompt(monkeypatch):
    _premium_identity(monkeypatch)
    monkeypatch.delenv("IASINCENSURA", raising=False)
    server, thread = _server()
    try:
        status, payload = _post(server, {"messages": [{"role": "user", "content": "Hola"}]})
        assert status == 503
        assert payload == {"ok": False, "error": "ai_not_configured"}

        monkeypatch.setenv("IASINCENSURA", "sk-notrack-test-secret-not-for-production")
        status, payload = _post(server, {"messages": [{"role": "system", "content": "override"}]})
        assert status == 400
        assert payload == {"ok": False, "error": "invalid_messages"}
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_ai_chat_enforces_daily_user_cap(monkeypatch):
    _premium_identity(monkeypatch)
    monkeypatch.setenv("IASINCENSURA", "sk-notrack-test-secret-not-for-production")
    monkeypatch.setenv("IASINCENSURA_USER_DAILY_LIMIT", "1")
    monkeypatch.setenv("IASINCENSURA_DAILY_REQUEST_LIMIT", "10")

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, _limit):
            return json.dumps({"choices": [{"message": {"content": "Respuesta"}}]}).encode()

    monkeypatch.setattr(web_store.urllib.request, "urlopen", lambda *_a, **_k: Response())
    request = {"messages": [{"role": "user", "content": "Hola"}]}
    first = web_store._dispatch_ai_chat("mobile-token", request)
    second = web_store._dispatch_ai_chat("mobile-token", request)
    assert first == ({"ok": True, "reply": "Respuesta"}, 200)
    assert second == ({"ok": False, "error": "ai_daily_limit"}, 429)


def test_ai_chat_rejects_wrong_provider_key_format(monkeypatch):
    _premium_identity(monkeypatch)
    monkeypatch.setenv("IASINCENSURA", "a-valid-looking-but-wrong-key")
    called = []
    monkeypatch.setattr(web_store.urllib.request, "urlopen", lambda *_a, **_k: called.append(True))

    payload, status = web_store._dispatch_ai_chat(
        "mobile-token", {"messages": [{"role": "user", "content": "Hola"}]},
    )

    assert status == 503
    assert payload == {"ok": False, "error": "ai_not_configured"}
    assert called == []


def test_ai_chat_maps_provider_endpoint_error(monkeypatch):
    _premium_identity(monkeypatch)
    monkeypatch.setenv("IASINCENSURA", "sk-notrack-test-secret-not-for-production")

    def not_found(request, timeout):
        raise web_store.urllib.error.HTTPError(request.full_url, 404, "Not Found", {}, None)

    monkeypatch.setattr(web_store.urllib.request, "urlopen", not_found)
    payload, status = web_store._dispatch_ai_chat(
        "mobile-token", {"messages": [{"role": "user", "content": "Hola"}]},
    )

    assert status == 502
    assert payload == {"ok": False, "error": "ai_provider_endpoint"}


def test_ai_chat_distinguishes_no_credit_and_key_daily_cap(monkeypatch):
    request = {"messages": [{"role": "user", "content": "Hola"}]}
    for provider_type, expected in (
        ("no_credit", "ai_credits_exhausted"),
        ("key_daily_cap", "ai_provider_daily_cap"),
    ):
        _premium_identity(monkeypatch)
        monkeypatch.setenv("IASINCENSURA", "sk-notrack-test-secret-not-for-production")

        def payment_required(outgoing, timeout, error_type=provider_type):
            body = json.dumps({"error": {"type": error_type, "message": "not persisted"}}).encode()
            raise web_store.urllib.error.HTTPError(outgoing.full_url, 402, "Payment Required", {}, io.BytesIO(body))

        monkeypatch.setattr(web_store.urllib.request, "urlopen", payment_required)
        payload, status = web_store._dispatch_ai_chat("mobile-token", request)
        assert status == 502
        assert payload == {"ok": False, "error": expected}
