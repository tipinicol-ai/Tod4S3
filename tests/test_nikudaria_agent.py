import http.client
import json
import threading
import time
from http.server import ThreadingHTTPServer

import pytest

import web_store


CLIENT_ID = "8b6c714a-6a71-4efb-9fcb-103a42d46c89"
API_KEY = "sk-notrack-test-secret-not-for-production"
REQUEST = {
    "client_id": CLIENT_ID,
    "messages": [{"role": "user", "content": "Abre Termux y lista los archivos"}],
}


def _server():
    server = ThreadingHTTPServer(("127.0.0.1", 0), web_store.StoreHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


def _post(server, body, token=None):
    connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
    headers = {"Content-Type": "application/json"}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    connection.request(
        "POST", "/api/app/nikudaria/agent", body=json.dumps(body), headers=headers,
    )
    response = connection.getresponse()
    result = json.loads(response.read().decode("utf-8"))
    status = response.status
    connection.close()
    return status, result


def _configure_anonymous(monkeypatch):
    # The new app has no account, auth token, or Premium profile.
    monkeypatch.setattr(web_store, "_PROFILE_CHECKER", lambda *_args: pytest.fail("profile must not be checked"))
    monkeypatch.setattr(web_store.sync_api, "app_identity_for_token", lambda *_args: pytest.fail("auth must not be checked"))
    monkeypatch.setattr(web_store.sync_api, "premium_activation_status", lambda *_args: pytest.fail("Premium must not be checked"))
    monkeypatch.setattr(web_store, "_NIKUDARIA_RECENT_REQUESTS", {})
    monkeypatch.setattr(web_store, "_NIKUDARIA_DAILY_REQUESTS", {})
    monkeypatch.setattr(web_store, "_AI_DAILY_GLOBAL_REQUESTS", [])
    monkeypatch.setenv("IASINCENSURA", API_KEY)


def _mock_provider(monkeypatch, content, captured=None):
    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self, _limit):
            return json.dumps({"choices": [{"message": {"content": content}}]}).encode("utf-8")

    def fake_urlopen(request, timeout):
        if captured is not None:
            captured["url"] = request.full_url
            captured["authorization"] = request.get_header("Authorization")
            captured["timeout"] = timeout
            captured["body"] = json.loads(request.data.decode("utf-8"))
        return Response()

    monkeypatch.setattr(web_store.urllib.request, "urlopen", fake_urlopen)


@pytest.fixture(autouse=True)
def reset_rate_state(monkeypatch):
    monkeypatch.setattr(web_store, "_AI_DAILY_GLOBAL_REQUESTS", [])
    monkeypatch.setattr(web_store, "_NIKUDARIA_RECENT_REQUESTS", {})
    monkeypatch.setattr(web_store, "_NIKUDARIA_DAILY_REQUESTS", {})


def test_nikudaria_allows_anonymous_install_without_profile_or_auth(monkeypatch):
    _configure_anonymous(monkeypatch)
    captured = {}
    result = {"reply": "Puedo hacerlo; revisa la propuesta.", "action": None}
    _mock_provider(monkeypatch, json.dumps(result), captured)
    server, thread = _server()
    try:
        status, payload = _post(server, REQUEST)
        assert status == 200
        assert payload == {"ok": True, **result}
        assert captured["url"] == "https://api.notrack.ai/v1/chat/completions"
        assert captured["authorization"] == f"Bearer {API_KEY}"
        assert captured["timeout"] == 60
        assert captured["body"]["model"] == "notrack-uncensored"
        assert captured["body"]["stream"] is False
        system_prompt = captured["body"]["messages"][0]["content"]
        assert "JSON estricto" in system_prompt
        assert "Como máximo propone una acción por turno" in system_prompt
        assert "solo propuestas" in system_prompt
        assert captured["body"]["messages"][-1] == REQUEST["messages"][0]
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_nikudaria_requires_valid_uuid_client_id_without_auth(monkeypatch):
    _configure_anonymous(monkeypatch)
    called = []
    monkeypatch.setattr(web_store.urllib.request, "urlopen", lambda *_args, **_kwargs: called.append(True))
    server, thread = _server()
    try:
        request = {**REQUEST, "client_id": "not-a-random-install-id"}
        status, payload = _post(server, request)
        assert status == 400
        assert payload == {"ok": False, "error": "invalid_client_id"}
        assert called == []
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


@pytest.mark.parametrize(
    "action",
    [
        {"type": "termux_command", "command": "ls -la", "reason": "Mostrar los archivos"},
        {"type": "open_app", "app_name": "Termux", "reason": "Abrir la terminal solicitada"},
    ],
)
def test_nikudaria_accepts_one_known_action_anonymously(monkeypatch, action):
    _configure_anonymous(monkeypatch)
    result = {"reply": "Esta es una propuesta; confírmala en la app.", "action": action}
    _mock_provider(monkeypatch, json.dumps(result))
    server, thread = _server()
    try:
        status, payload = _post(server, REQUEST)
        assert status == 200
        assert payload == {"ok": True, **result}
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_nikudaria_rejects_malformed_provider_json_generically(monkeypatch):
    _configure_anonymous(monkeypatch)
    secretish_content = "no es JSON; no registrar este texto"
    _mock_provider(monkeypatch, secretish_content)
    server, thread = _server()
    try:
        status, payload = _post(server, REQUEST)
        assert status == 502
        assert payload == {"ok": False, "error": "ai_invalid_response"}
        assert secretish_content not in json.dumps(payload)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


@pytest.mark.parametrize(
    "model_content",
    [
        '{"reply":"a","reply":"b","action":null}',
        json.dumps({"reply": "No", "action": {"type": "unknown_action", "reason": "x"}}),
    ],
)
def test_nikudaria_rejects_duplicate_json_keys_and_unknown_actions(monkeypatch, model_content):
    _configure_anonymous(monkeypatch)
    _mock_provider(monkeypatch, model_content)
    server, thread = _server()
    try:
        status, payload = _post(server, REQUEST)
        assert status == 502
        assert payload == {"ok": False, "error": "ai_invalid_response"}
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


@pytest.mark.parametrize("command", ["rm -rf /", "sh -c 'rm -rf /'", "sudo reboot"])
def test_nikudaria_blocks_obvious_destructive_or_root_commands(monkeypatch, command):
    _configure_anonymous(monkeypatch)
    result = {
        "reply": "Se rechazó una instrucción insegura.",
        "action": {"type": "termux_command", "command": command, "reason": "No debe ejecutarse"},
    }
    _mock_provider(monkeypatch, json.dumps(result))
    server, thread = _server()
    try:
        status, payload = _post(server, REQUEST)
        assert status == 502
        assert payload == {"ok": False, "error": "ai_invalid_response"}
        assert command not in json.dumps(payload)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_nikudaria_enforces_eight_requests_per_minute_per_install(monkeypatch):
    _configure_anonymous(monkeypatch)
    _mock_provider(monkeypatch, json.dumps({"reply": "Entendido.", "action": None}))
    server, thread = _server()
    try:
        for _ in range(8):
            status, _ = _post(server, REQUEST)
            assert status == 200
        status, payload = _post(server, REQUEST)
        assert status == 429
        assert payload == {"ok": False, "error": "ai_rate_limited"}
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_nikudaria_enforces_sixty_requests_per_day_per_install(monkeypatch):
    _configure_anonymous(monkeypatch)
    web_store._NIKUDARIA_DAILY_REQUESTS[CLIENT_ID] = [time.monotonic()] * 60
    called = []
    monkeypatch.setattr(web_store.urllib.request, "urlopen", lambda *_args, **_kwargs: called.append(True))
    payload, status = web_store._dispatch_nikudaria_agent(REQUEST)
    assert status == 429
    assert payload == {"ok": False, "error": "ai_daily_limit"}
    assert called == []


def test_nikudaria_enforces_configured_global_daily_limit(monkeypatch):
    _configure_anonymous(monkeypatch)
    monkeypatch.setenv("IASINCENSURA_DAILY_REQUEST_LIMIT", "1")
    _mock_provider(monkeypatch, json.dumps({"reply": "Entendido.", "action": None}))
    server, thread = _server()
    try:
        first = _post(server, REQUEST)
        other_install = {**REQUEST, "client_id": "4276af77-8304-4e2c-b6ac-335ec4d9226b"}
        second = _post(server, other_install)
        assert first[0] == 200
        assert second == (429, {"ok": False, "error": "ai_service_daily_limit"})
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_nikudaria_returns_generic_provider_network_error(monkeypatch):
    _configure_anonymous(monkeypatch)

    def fail_provider(*_args, **_kwargs):
        raise web_store.urllib.error.URLError("private provider detail")

    monkeypatch.setattr(web_store.urllib.request, "urlopen", fail_provider)
    server, thread = _server()
    try:
        status, payload = _post(server, REQUEST)
        assert status == 502
        assert payload == {"ok": False, "error": "ai_unavailable"}
        assert "private provider detail" not in json.dumps(payload)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_nikudaria_termux_results_return_to_the_ai_context():
    from pathlib import Path

    project = Path(__file__).resolve().parents[1] / "nikudaria-app" / "app" / "src" / "main"
    receiver = (project / "java/com/nikudaria/ai/TermuxResultReceiver.java").read_text(encoding="utf-8")
    activity = (project / "java/com/nikudaria/ai/MainActivity.java").read_text(encoding="utf-8")
    runner = (project / "java/com/nikudaria/ai/TermuxRunner.java").read_text(encoding="utf-8")
    assert "intent.getBundleExtra(RESULT_BUNDLE)" in receiver
    assert 'result.getString("stdout"' in receiver
    assert 'result.getInt("exitCode"' in receiver
    assert '"termux_result_pending"' in receiver and "deliverPendingTermuxResult()" in activity
    assert 'history.put(message("user", "Resultado de Termux' in activity
    assert "FLAG_ONE_SHOT" in runner
    assert "Build.VERSION_CODES.S" in runner


def test_nikudaria_tabs_match_the_video_flow_without_accounts():
    from pathlib import Path

    activity = (Path(__file__).resolve().parents[1] / "nikudaria-app/app/src/main/java/com/nikudaria/ai/MainActivity.java").read_text(encoding="utf-8")
    assert 'private String selectedTab = "Código"' in activity
    assert 'codeIntro.setVisibility("Código".equals(selectedTab)' in activity
    assert 'teamPanel.setVisibility(team ? View.VISIBLE : View.GONE)' in activity
    assert 'composerBox.setVisibility(team ? View.GONE : View.VISIBLE)' in activity
    assert "funciona sin cuentas, Telegram ni Premium" in activity


def test_nikudaria_maps_provider_credit_limit_safely(monkeypatch):
    import io

    _configure_anonymous(monkeypatch)
    provider_body = json.dumps({"error": {"type": "no_credit"}}).encode("utf-8")

    def out_of_credits(*_args, **_kwargs):
        raise web_store.urllib.error.HTTPError(
            "https://api.notrack.ai/v1/chat/completions", 402, "Payment Required", None,
            io.BytesIO(provider_body),
        )

    monkeypatch.setattr(web_store.urllib.request, "urlopen", out_of_credits)
    server, thread = _server()
    try:
        status, payload = _post(server, REQUEST)
        assert status == 502
        assert payload == {"ok": False, "error": "ai_credits_exhausted"}
        assert provider_body.decode("utf-8") not in json.dumps(payload)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_nikudaria_has_visible_send_button_and_keyboard_insets():
    from pathlib import Path

    activity = (Path(__file__).resolve().parents[1] / "nikudaria-app/app/src/main/java/com/nikudaria/ai/MainActivity.java").read_text(encoding="utf-8")
    assert 'sendButton = control("Enviar")' in activity
    assert "inputRow.addView(sendButton, sendLp)" in activity
    assert "sendButton.setOnClickListener(v -> sendMessage())" in activity
    assert "SOFT_INPUT_ADJUST_RESIZE" in activity
    assert "WindowInsets.Type.ime()" in activity
    assert "Math.max(bars.bottom, ime.bottom)" in activity
