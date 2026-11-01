import json
from http.server import ThreadingHTTPServer
from threading import Thread
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import pytest

import web_store


def test_cloud_clear_route_requires_mobile_token_and_dispatches_owner(monkeypatch):
    calls = []
    monkeypatch.setattr(
        web_store.sync_api,
        "user_id_for_token",
        lambda token: 4242 if token == "valid-mobile-token" else None,
    )
    web_store.configure_app_task_bridge(
        lambda action, user_id, payload: calls.append((action, user_id, payload))
        or ({"ok": True, "deleted_files": 3, "deleted_records": 2}, 200)
    )
    server = ThreadingHTTPServer(("127.0.0.1", 0), web_store.StoreHandler)
    worker = Thread(target=server.serve_forever, daemon=True)
    worker.start()
    url = f"http://127.0.0.1:{server.server_port}/api/app/cloud/clear"
    try:
        request = Request(
            url,
            data=b"{}",
            headers={"Authorization": "Bearer valid-mobile-token", "Content-Type": "application/json"},
            method="POST",
        )
        with urlopen(request, timeout=3) as response:
            assert response.status == 200
            assert json.loads(response.read()) == {
                "ok": True, "deleted_files": 3, "deleted_records": 2
            }
        assert calls == [("clear_cloud", 4242, {})]

        invalid = Request(url, data=b"{}", headers={"Content-Type": "application/json"}, method="POST")
        with pytest.raises(HTTPError) as unauthorized:
            urlopen(invalid, timeout=3)
        assert unauthorized.value.code == 401
        assert len(calls) == 1
    finally:
        web_store.configure_app_task_bridge(None)
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)
