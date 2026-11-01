import sync_api
import web_store
import json
from http.server import ThreadingHTTPServer
from threading import Thread
from urllib.request import Request, urlopen


def test_deliveries_only_return_tod4s3_bot_source(tmp_path, monkeypatch):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    monkeypatch.setattr(sync_api, "user_id_for_token", lambda _token: 42)

    sync_api.record_delivery(
        42,
        "task-1",
        "archivo.bin",
        4,
        ["https://s3.todus.cu/stream/parte1"],
    )

    state = sync_api._read()
    state["deliveries"]["42"].insert(
        0,
        {
            "task_id": "private-chat-item",
            "source": "other_source",
            "filename": "privado.bin",
            "size": 9,
            "parts": ["https://example.invalid/private"],
        },
    )
    sync_api._write(state)

    rows = sync_api.deliveries_for_token("irrelevant-test-token")
    assert len(rows) == 1
    assert rows[0]["task_id"] == "task-1"
    assert rows[0]["source"] == "tod4s3_bot"


def test_clear_deliveries_for_user_removes_only_bot_rows(tmp_path, monkeypatch):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    sync_api.record_delivery(42, "task-1", "archivo.bin", 4, ["https://s3.todus.cu/stream/parte1"])
    state = sync_api._read()
    state["deliveries"]["42"].append({
        "task_id": "foreign-source",
        "source": "other_source",
        "parts": ["https://example.invalid/not-owned"],
    })
    sync_api._write(state)

    assert len(sync_api.deliveries_for_user(42)) == 1
    assert sync_api.clear_deliveries_for_user(42) == 1
    rows = sync_api._read()["deliveries"]["42"]
    assert len(rows) == 1
    assert rows[0]["source"] == "other_source"


def _prepare_delivery(tmp_path, monkeypatch, parts):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    monkeypatch.setattr(sync_api, "user_id_for_token", lambda _token: 42)
    sync_api.record_delivery(42, "task-delete", "archivo.bin", 8, parts)


def test_delete_delivery_success_removes_remote_parts_and_local_row(tmp_path, monkeypatch):
    urls = [
        "https://s3.todus.cu/stream/users/tg_42/telegram/parte-1.bin",
        "https://s3.todus.cu/stream/users/tg_42/telegram/parte-2.bin",
    ]
    _prepare_delivery(tmp_path, monkeypatch, urls)
    deleted = []
    monkeypatch.setattr(web_store, "delete_from_todus_s3", deleted.append)

    payload, status = web_store.delete_delivery_for_token("app-token", "task-delete")

    assert status == 200
    assert payload == {"ok": True, "task_id": "task-delete", "deleted_parts": 2}
    assert deleted == urls
    assert sync_api._read()["deliveries"]["42"] == []


def test_delete_delivery_rejects_url_outside_user_namespace(tmp_path, monkeypatch):
    url = "https://s3.todus.cu/stream/users/tg_41/telegram/foreign.bin"
    _prepare_delivery(tmp_path, monkeypatch, [url])
    deleted = []
    monkeypatch.setattr(web_store, "delete_from_todus_s3", deleted.append)

    payload, status = web_store.delete_delivery_for_token("app-token", "task-delete")

    assert status == 409
    assert payload["error"] == "invalid_delivery_url"
    assert deleted == []
    assert sync_api._read()["deliveries"]["42"]


def test_delete_delivery_failure_keeps_local_row_for_retry(tmp_path, monkeypatch):
    url = "https://s3.todus.cu/stream/users/tg_42/telegram/parte.bin"
    _prepare_delivery(tmp_path, monkeypatch, [url])

    def fail_delete(_url):
        raise RuntimeError("simulated ToDus failure")

    monkeypatch.setattr(web_store, "delete_from_todus_s3", fail_delete)
    payload, status = web_store.delete_delivery_for_token("app-token", "task-delete")

    assert status == 502
    assert payload["error"] == "delete_failed"
    assert sync_api._read()["deliveries"]["42"][0]["task_id"] == "task-delete"


def test_delete_delivery_rejects_legacy_row_without_task_id(tmp_path, monkeypatch):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    monkeypatch.setattr(sync_api, "user_id_for_token", lambda _token: 42)
    state = sync_api._read()
    state["deliveries"]["42"] = [{
        "source": "tod4s3_bot",
        "parts": ["https://s3.todus.cu/stream/users/tg_42/telegram/legacy.bin"],
    }]
    sync_api._write(state)
    deleted = []
    monkeypatch.setattr(web_store, "delete_from_todus_s3", deleted.append)

    payload, status = web_store.delete_delivery_for_token("app-token", "task-unknown")

    assert status == 409
    assert payload["error"] == "legacy_delivery_incomplete"
    assert "sin task_id" in payload["message"]
    assert deleted == []


def test_delete_delivery_accepts_legacy_row_without_source(tmp_path, monkeypatch):
    url = "https://s3.todus.cu/stream/users/tg_42/telegram/legacy.bin"
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    monkeypatch.setattr(sync_api, "user_id_for_token", lambda _token: 42)
    state = sync_api._read()
    state["deliveries"]["42"] = [{
        "task_id": "task-legacy-source",
        "filename": "archivo-antiguo.bin",
        "size": 8,
        "parts": [url],
    }]
    sync_api._write(state)
    deleted = []
    monkeypatch.setattr(web_store, "delete_from_todus_s3", deleted.append)

    payload, status = web_store.delete_delivery_for_token("app-token", "task-legacy-source")

    assert status == 200
    assert payload["deleted_parts"] == 1
    assert deleted == [url]
    assert sync_api._read()["deliveries"]["42"] == []


def test_global_delivery_snapshot_clear_is_compare_and_clear_and_preserves_other_sources(tmp_path, monkeypatch):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    sync_api.record_delivery(42, "task-1", "f", 1, ["https://s3.todus.cu/stream/users/tg_42/telegram/a.bin"])
    state = sync_api._read()
    state["deliveries"]["42"].append({"task_id": "other", "source": "other_source", "parts": ["private"]})
    sync_api._write(state)
    snapshot = sync_api.all_deliveries_snapshot()
    assert sync_api.clear_all_deliveries(snapshot) == 1
    assert sync_api.all_deliveries_snapshot() == {}
    remaining = sync_api._read()["deliveries"]["42"]
    assert remaining == [{"task_id": "other", "source": "other_source", "parts": ["private"]}]


def test_delete_delivery_http_post_endpoint_with_bearer_token_is_mocked(tmp_path, monkeypatch):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    monkeypatch.setattr(sync_api, "user_id_for_token", lambda token: 42 if token == "mobile" else None)
    url = "https://s3.todus.cu/stream/users/tg_42/telegram/part.bin"
    sync_api.record_delivery(42, "http-task", "f", 1, [url])
    deleted = []
    monkeypatch.setattr(web_store, "delete_from_todus_s3", deleted.append)
    server = ThreadingHTTPServer(("127.0.0.1", 0), web_store.StoreHandler)
    worker = Thread(target=server.serve_forever, daemon=True)
    worker.start()
    request = Request(
        f"http://127.0.0.1:{server.server_port}/api/app/deliveries/delete",
        data=json.dumps({"task_id": "http-task"}).encode(),
        headers={"Authorization": "Bearer mobile", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=3) as response:
            assert response.status == 200
            payload = json.loads(response.read())
        assert payload["deleted_parts"] == 1
        assert deleted == [url]
        assert sync_api.deliveries_for_user(42) == []
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)
