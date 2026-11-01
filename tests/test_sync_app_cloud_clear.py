import asyncio

import bot


class FakeManager:
    def __init__(self):
        self.lock = asyncio.Lock()
        self.jobs = {}


class FakeApplication:
    def __init__(self):
        self.bot_data = {"task_queue": FakeManager()}


def test_android_cloud_clear_deduplicates_owned_urls_and_clears_records(monkeypatch):
    deleted = []
    cleared = []
    monkeypatch.setattr(bot, "get_task_history", lambda user_id, limit: [
        {"urls": ["https://s3.todus.cu/stream/users/df/42/a.bin"]},
    ])
    monkeypatch.setattr(bot.sync_api, "deliveries_for_user", lambda user_id: [
        {"parts": ["https://s3.todus.cu/stream/users/df/42/a.bin",
                    "https://s3.todus.cu/stream/users/df/42/b.bin"]},
    ])
    monkeypatch.setattr(bot, "delete_from_todus_s3", deleted.append)
    monkeypatch.setattr(bot, "clear_task_history", lambda user_id: cleared.append(user_id) or True)
    monkeypatch.setattr(bot.sync_api, "clear_deliveries_for_user", lambda user_id: 1)
    monkeypatch.setattr(bot, "schedule_data_backup", lambda *args, **kwargs: None)

    result, status = asyncio.run(bot.sync_app_task_request(
        FakeApplication(), "clear_cloud", 42, {}
    ))

    assert status == 200
    assert result == {"ok": True, "deleted_files": 2, "deleted_records": 2}
    assert deleted == [
        "https://s3.todus.cu/stream/users/df/42/a.bin",
        "https://s3.todus.cu/stream/users/df/42/b.bin",
    ]
    assert cleared == [42]


def test_android_cloud_clear_keeps_history_when_any_delete_fails(monkeypatch):
    cleared = []
    monkeypatch.setattr(bot, "get_task_history", lambda user_id, limit: [
        {"urls": ["https://s3.todus.cu/stream/users/df/42/a.bin"]},
    ])
    monkeypatch.setattr(bot.sync_api, "deliveries_for_user", lambda user_id: [])

    def fail_delete(_url):
        raise RuntimeError("simulated delete failure")

    monkeypatch.setattr(bot, "delete_from_todus_s3", fail_delete)
    monkeypatch.setattr(bot, "clear_task_history", lambda user_id: cleared.append(user_id) or True)
    monkeypatch.setattr(bot, "schedule_data_backup", lambda *args, **kwargs: None)

    result, status = asyncio.run(bot.sync_app_task_request(
        FakeApplication(), "clear_cloud", 42, {}
    ))

    assert status == 502
    assert result["error"] == "cloud_delete_failed"
    assert result["failed_files"] == 1
    assert cleared == []
