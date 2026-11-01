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
        web_store.sync_api,
        "app_identity_for_token",
        lambda token: {"user_id": 4242, "device_id": "device-1"}
        if token == "valid-mobile-token" else None,
    )
    monkeypatch.setattr(web_store.sync_api, "premium_activation_status", lambda *args: "pending")
    monkeypatch.setattr(
        web_store,
        "_PROFILE_CHECKER",
        lambda user_id: {
            "premium": True,
            "plan": "premium",
            "expires": "31/12/2026",
            "expires_at": "2027-01-01T00:00:00+00:00",
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
                "premium_verified": False,
            }
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)


def test_profile_endpoint_exposes_validated_free_quota_metrics(monkeypatch):
    monkeypatch.setattr(web_store.sync_api, "user_id_for_token", lambda token: 4242 if token == "free-mobile" else None)
    monkeypatch.setattr(
        web_store.sync_api,
        "app_identity_for_token",
        lambda token: {"user_id": 4242, "device_id": "device-free"} if token == "free-mobile" else None,
    )
    monkeypatch.setattr(
        web_store,
        "_PROFILE_CHECKER",
        lambda user_id: {
            "premium": False,
            "plan": "free",
            "expires": "",
            "expires_at": "",
            "days_remaining": None,
            "quota": {
                "limit_bytes": 1024,
                "used_bytes": 256,
                "remaining_bytes": 768,
                "percent_used": 25,
                "reset_in_seconds": 3600,
            },
        },
    )
    server = ThreadingHTTPServer(("127.0.0.1", 0), web_store.StoreHandler)
    worker = Thread(target=server.serve_forever, daemon=True)
    worker.start()
    url = f"http://127.0.0.1:{server.server_port}/api/app/profile"
    try:
        request = Request(url, headers={"Authorization": "Bearer free-mobile"})
        with urlopen(request, timeout=3) as response:
            payload = json.loads(response.read())
        assert payload["quota"] == {
            "limit_bytes": 1024,
            "used_bytes": 256,
            "remaining_bytes": 768,
            "percent_used": 25,
            "reset_in_seconds": 3600,
        }
        assert payload["premium"] is False
        assert payload["premium_verified"] is False
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)
