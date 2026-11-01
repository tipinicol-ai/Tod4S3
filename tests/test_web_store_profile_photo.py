from http.server import ThreadingHTTPServer
from threading import Thread
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import pytest

import web_store


def test_profile_photo_endpoint_requires_mobile_token_and_returns_owner_photo(monkeypatch):
    checked_users = []
    monkeypatch.setattr(
        web_store.sync_api,
        "user_id_for_token",
        lambda token: 4242 if token == "valid-mobile-token" else None,
    )
    monkeypatch.setattr(
        web_store,
        "_PROFILE_PHOTO_CHECKER",
        lambda user_id: checked_users.append(user_id) or b"\xff\xd8telegram-photo\xff\xd9",
    )
    server = ThreadingHTTPServer(("127.0.0.1", 0), web_store.StoreHandler)
    worker = Thread(target=server.serve_forever, daemon=True)
    worker.start()
    url = f"http://127.0.0.1:{server.server_port}/api/app/profile/photo"
    try:
        request = Request(url, headers={"Authorization": "Bearer valid-mobile-token"})
        with urlopen(request, timeout=3) as response:
            assert response.status == 200
            assert response.headers.get_content_type() == "image/jpeg"
            assert response.read() == b"\xff\xd8telegram-photo\xff\xd9"
        assert checked_users == [4242]

        with pytest.raises(HTTPError) as unauthorized:
            urlopen(url, timeout=3)
        assert unauthorized.value.code == 401
        assert checked_users == [4242]
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)
