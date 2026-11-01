import json
from http.server import ThreadingHTTPServer
from threading import Thread
from urllib.request import Request, urlopen

import web_store


def test_profile_endpoint_exposes_authoritative_premium_state(monkeypatch):
    monkeypatch.setattr(
        web_store.sync_api,
        "user_id_for_token",
        lambda token: 4242 if token == "valid-mobile-token" else None,
    )
    monkeypatch.setattr(
        web_store,
        "_PROFILE_CHECKER",
        lambda user_id: {
            "premium": True,
            "plan": "premium",
            "expires": "31/12/2026",
            "days_remaining": 61,
        },
    )
    server = ThreadingHTTPServer(("127.0.0.1", 0), web_store.StoreHandler)
    worker = Thread(target=server.serve_forever, daemon=True)
    worker.start()
    url = f"http://127.0.0.1:{server.server_port}/api/app/profile"
    try:
        request = Request(url, headers={"Authorization": "Bearer valid-mobile-token"})
        with urlopen(request, timeout=3) as response:
            assert response.status == 200
            assert json.loads(response.read()) == {
                "premium": True,
                "plan": "premium",
                "expires": "31/12/2026",
                "days_remaining": 61,
            }
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)
