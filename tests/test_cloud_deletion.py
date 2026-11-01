import asyncio
import json
from functools import wraps
from http.server import ThreadingHTTPServer
from threading import Thread
from types import SimpleNamespace
from unittest.mock import AsyncMock
from urllib.request import Request, urlopen

import bot
import sync_api
import web_store


BASE = "https://s3.todus.cu/stream"


def _run_async_test(function):
    @wraps(function)
    def wrapper(*args, **kwargs):
        return asyncio.run(function(*args, **kwargs))
    return wrapper


def _owned_url(user_id, name):
    return f"{BASE}/users/df/{user_id}/{name}"


def _history(monkeypatch, tmp_path, rows):
    path = tmp_path / "task_history.json"
    monkeypatch.setattr(bot, "HISTORY_DATA_FILE", str(path))
    bot._save_json(str(path), rows)
    return path


def _deliveries(monkeypatch, tmp_path):
    path = tmp_path / "sync_app.json"
    monkeypatch.setattr(sync_api, "STATE_FILE", path)
    return path


@_run_async_test
async def test_personal_clear_deletes_task_history_and_delivery_parts_then_clears_both(monkeypatch, tmp_path):
    first, second = _owned_url(42, "part%20one.bin"), _owned_url(42, "part-two.bin")
    _history(monkeypatch, tmp_path, {"42": [{"task_id": "t1", "urls": [first], "filename": "f"}]})
    _deliveries(monkeypatch, tmp_path)
    sync_api.record_delivery(42, "t1", "f", 10, [first, second])
    deleted = []
    monkeypatch.setattr(bot, "delete_from_todus_s3", deleted.append)

    total, removed, ok = await bot.clear_personal_cloud_history(42)

    assert (total, removed, ok) == (2, 2, True)
    assert set(deleted) == {first, second}
    assert bot.all_task_history() == {}
    assert sync_api.deliveries_for_user(42) == []


@_run_async_test
async def test_personal_clear_includes_completed_jobs_still_in_memory(monkeypatch, tmp_path):
    url = _owned_url(42, "job-part.bin")
    _history(monkeypatch, tmp_path, {})
    _deliveries(monkeypatch, tmp_path)
    job = SimpleNamespace(task_id="job-task", user_id=42, state="done", part_urls={0: url})
    manager = SimpleNamespace(lock=asyncio.Lock(), jobs={"job-task": job})
    application = SimpleNamespace(bot_data={"task_queue": manager})
    deleted = []
    monkeypatch.setattr(bot, "delete_from_todus_s3", deleted.append)

    total, removed, ok = await bot.clear_personal_cloud_history(42, application)

    assert (total, removed, ok) == (1, 1, True)
    assert deleted == [url]
    assert job.part_urls == {}


@_run_async_test
async def test_personal_clear_rejects_foreign_delivery_url_without_remote_or_store_changes(monkeypatch, tmp_path):
    foreign = _owned_url(41, "foreign.bin")
    _history(monkeypatch, tmp_path, {"42": [{"task_id": "t1", "urls": [_owned_url(42, "mine.bin")]}]})
    _deliveries(monkeypatch, tmp_path)
    sync_api.record_delivery(42, "t1", "f", 10, [foreign])
    deleted = []
    monkeypatch.setattr(bot, "delete_from_todus_s3", deleted.append)

    _, _, ok = await bot.clear_personal_cloud_history(42)

    assert not ok
    assert deleted == []
    assert bot.all_task_history()["42"]
    assert sync_api.deliveries_for_user(42)


@_run_async_test
async def test_personal_clear_remote_failure_keeps_both_stores(monkeypatch, tmp_path):
    part = _owned_url(42, "part.bin")
    _history(monkeypatch, tmp_path, {"42": [{"task_id": "t1", "urls": [part]}]})
    _deliveries(monkeypatch, tmp_path)
    sync_api.record_delivery(42, "t1", "f", 10, [part])

    def fail(_url):
        raise RuntimeError("mock remote failure")

    monkeypatch.setattr(bot, "delete_from_todus_s3", fail)
    _, _, ok = await bot.clear_personal_cloud_history(42)

    assert not ok
    assert bot.all_task_history()["42"]
    assert sync_api.deliveries_for_user(42)


@_run_async_test
async def test_global_clear_includes_history_deliveries_and_completed_job_parts(monkeypatch, tmp_path):
    history_url = _owned_url(42, "history.bin")
    delivery_url = _owned_url(42, "delivery.bin")
    job_url = _owned_url(43, "job.bin")
    _history(monkeypatch, tmp_path, {"42": [{"task_id": "t1", "urls": [history_url]}]})
    _deliveries(monkeypatch, tmp_path)
    sync_api.record_delivery(42, "t1", "f", 10, [delivery_url])
    job = SimpleNamespace(state="done", user_id=43, part_urls={0: job_url})
    app = SimpleNamespace(bot_data={"task_queue": SimpleNamespace(jobs={"t2": job})})
    deleted = []
    monkeypatch.setattr(bot, "delete_from_todus_s3", deleted.append)

    total, removed, failed, affected = await bot.clear_all_registered_cloud_files(app)

    assert (total, removed, failed, affected) == (3, 3, 0, 1)
    assert set(deleted) == {history_url, delivery_url, job_url}
    assert bot.all_task_history() == {}
    assert sync_api.all_deliveries_snapshot() == {}
    assert job.part_urls == {}


@_run_async_test
async def test_global_remote_failure_preserves_task_delivery_and_job_references(monkeypatch, tmp_path):
    history_url = _owned_url(42, "history.bin")
    delivery_url = _owned_url(42, "delivery.bin")
    job_url = _owned_url(43, "job.bin")
    _history(monkeypatch, tmp_path, {"42": [{"task_id": "t1", "urls": [history_url]}]})
    _deliveries(monkeypatch, tmp_path)
    sync_api.record_delivery(42, "t1", "f", 10, [delivery_url])
    delivery_rows = sync_api.all_deliveries_snapshot()
    job = SimpleNamespace(state="done", user_id=43, part_urls={0: job_url})
    app = SimpleNamespace(bot_data={"task_queue": SimpleNamespace(jobs={"t2": job})})

    def fail(url):
        if url == delivery_url:
            raise RuntimeError("mock remote failure")

    monkeypatch.setattr(bot, "delete_from_todus_s3", fail)
    total, deleted, failed, affected = await bot.clear_all_registered_cloud_files(app)

    assert total == 3 and deleted == 2 and failed == 1 and affected == 0
    assert bot.all_task_history()["42"]
    assert sync_api.all_deliveries_snapshot() == delivery_rows
    assert job.part_urls == {0: job_url}


def test_global_delivery_clear_uses_expected_snapshot_and_preserves_new_rows(monkeypatch, tmp_path):
    _deliveries(monkeypatch, tmp_path)
    sync_api.record_delivery(42, "old", "f", 1, [_owned_url(42, "old.bin")])
    snapshot = sync_api.all_deliveries_snapshot()
    sync_api.record_delivery(43, "new", "f", 1, [_owned_url(43, "new.bin")])

    assert sync_api.clear_all_deliveries(snapshot) is None
    assert set(sync_api.all_deliveries_snapshot()) == {"42", "43"}


def test_android_delivery_delete_post_endpoint_uses_bearer_token_and_never_real_s3(monkeypatch, tmp_path):
    _deliveries(monkeypatch, tmp_path)
    url = _owned_url(42, "part.bin")
    sync_api.record_delivery(42, "endpoint-task", "f", 10, [url])
    monkeypatch.setattr(sync_api, "user_id_for_token", lambda token: 42 if token == "app-token" else None)
    deleted = []
    monkeypatch.setattr(web_store, "delete_from_todus_s3", deleted.append)
    server = ThreadingHTTPServer(("127.0.0.1", 0), web_store.StoreHandler)
    worker = Thread(target=server.serve_forever, daemon=True)
    worker.start()
    request = Request(
        f"http://127.0.0.1:{server.server_port}/api/app/deliveries/delete",
        data=json.dumps({"task_id": "endpoint-task"}).encode(),
        headers={"Authorization": "Bearer app-token", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=3) as response:
            payload = json.loads(response.read())
            assert response.status == 200
        assert payload == {"ok": True, "task_id": "endpoint-task", "deleted_parts": 1}
        assert deleted == [url]
        assert sync_api.deliveries_for_user(42) == []
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)


@_run_async_test
async def test_job_cloud_delete_callback_keeps_references_when_remote_delete_fails(monkeypatch, tmp_path):
    url = _owned_url(42, "job-part.bin")
    _history(monkeypatch, tmp_path, {"42": [{"task_id": "job-task", "urls": [url]}]})
    _deliveries(monkeypatch, tmp_path)
    sync_api.record_delivery(42, "job-task", "f", 10, [url])
    job = SimpleNamespace(
        task_id="job-task", user_id=42, state="done", part_urls={0: url},
        chat_id=42, error=None,
        set_state=lambda state: setattr(job, "state", state),
    )
    manager = SimpleNamespace(jobs={"job-task": job})
    user = SimpleNamespace(id=42)
    query = SimpleNamespace(data="j:job-task:cloud_delete", message=object(), answer=AsyncMock())
    update = SimpleNamespace(callback_query=query, effective_user=user)
    context = SimpleNamespace(application=SimpleNamespace(bot_data={"task_queue": manager}),
                               user_data={}, bot=object())
    monkeypatch.setattr(bot, "is_private_update", lambda _update: True)
    monkeypatch.setattr(bot, "is_user_blocked", lambda _user_id: False)
    monkeypatch.setattr(bot, "register_user", lambda _user: None)
    monkeypatch.setattr(bot, "user_has_required_memberships", AsyncMock(return_value=True))
    monkeypatch.setattr(bot, "delete_from_todus_s3", lambda _url: (_ for _ in ()).throw(RuntimeError("failed")))

    await bot.button_callback(update, context)

    assert job.part_urls == {0: url}
    assert job.state == "done"
    assert bot.all_task_history()["42"]
    assert sync_api.deliveries_for_user(42)
    query.answer.assert_awaited()
