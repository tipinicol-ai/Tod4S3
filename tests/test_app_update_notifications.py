import asyncio
import threading
from types import SimpleNamespace

import bot
import sync_api


def _callback_data(markup):
    return [button.callback_data for row in markup.inline_keyboard for button in row]


def _update_block(ban_id="ban-abc123", user_id=87654):
    return {
        "ban_id": ban_id,
        "user_id": user_id,
        "device_id": "d" * 64,
        "version_code": 108,
        "version_name": "4.2.1",
        "reason": "Se rechazó la actualización requerida",
        "blocked_at": "2026-11-07T12:00:00Z",
    }


def test_app_update_blocks_panel_shows_device_reason_version_and_unban(monkeypatch):
    block = _update_block(user_id=None)
    monkeypatch.setattr(sync_api, "app_update_blocks_page", lambda page, page_size: ([block], 1), raising=False)

    text = bot.admin_app_update_blocks_text(0)
    keyboard = bot.admin_app_update_blocks_keyboard(0)

    assert "ban-abc123" in text
    assert "no vinculado" in text
    assert "d" * 64 in text
    flattened = " ".join(text.split())
    assert bot.sc("motivo") in flattened
    assert bot.sc("requerida") in flattened
    assert "4.2.1" in text and "108" in text
    assert "admin:updateban:u:ban-abc123:0" in _callback_data(keyboard)
    assert all(len(data.encode("utf-8")) <= 64 for data in _callback_data(keyboard))
    assert "admin:updateblocks:0" in _callback_data(bot.admin_app_users_keyboard(0))


def test_app_update_unban_callback_unbans_one_device_and_schedules_backup(monkeypatch):
    monkeypatch.setattr(bot, "is_private_update", lambda _update: True)
    monkeypatch.setattr(bot, "is_user_blocked", lambda _user_id: False)
    monkeypatch.setattr(bot, "is_admin", lambda _user_id: True)
    monkeypatch.setattr(bot, "is_owner", lambda _user_id: True)
    monkeypatch.setattr(bot, "register_user", lambda _user: None)
    blocks = [_update_block()]
    unbanned = []
    backups = []
    monkeypatch.setattr(sync_api, "app_update_blocks_page", lambda page, page_size: (blocks, len(blocks)), raising=False)

    def unban(ban_id, admin_id):
        unbanned.append((ban_id, admin_id))
        blocks.clear()
        return {"ban_id": ban_id, "device_id": "d" * 64}

    monkeypatch.setattr(sync_api, "unban_app_device_by_ban_id", unban, raising=False)
    monkeypatch.setattr(sync_api, "unban_app_user", lambda *_a, **_k: (_ for _ in ()).throw(AssertionError("account unban called")))
    monkeypatch.setattr(sync_api, "ban_app_user", lambda *_a, **_k: (_ for _ in ()).throw(AssertionError("account ban called")))
    monkeypatch.setattr(bot, "schedule_data_backup", lambda *args: backups.append(args))
    edits = []

    async def fake_edit(_message, text, **kwargs):
        edits.append((text, kwargs))

    monkeypatch.setattr(bot, "edit_query_message_text", fake_edit)

    class Query:
        data = "admin:updateban:u:ban-abc123:0"
        message = SimpleNamespace()

        async def answer(self, *_args, **_kwargs):
            pass

    update = SimpleNamespace(
        callback_query=Query(),
        effective_user=SimpleNamespace(id=10001, full_name="Admin", first_name="Admin"),
    )
    context = SimpleNamespace(user_data={}, application=SimpleNamespace())
    asyncio.run(bot.button_callback(update, context))

    assert unbanned == [("ban-abc123", 10001)]
    assert backups and "desbloqueo" in backups[0][1]
    assert edits and bot.sc("0 dispositivos") in " ".join(edits[-1][0].split())


def test_app_update_unban_callback_rejects_non_admin(monkeypatch):
    monkeypatch.setattr(bot, "is_private_update", lambda _update: True)
    monkeypatch.setattr(bot, "is_user_blocked", lambda _user_id: False)
    monkeypatch.setattr(bot, "is_admin", lambda _user_id: False)
    monkeypatch.setattr(bot, "is_owner", lambda _user_id: True)
    monkeypatch.setattr(bot, "register_user", lambda _user: None)
    monkeypatch.setattr(
        sync_api,
        "unban_app_device_by_ban_id",
        lambda *_a, **_k: (_ for _ in ()).throw(AssertionError("unauthorized unban")),
        raising=False,
    )

    class Query:
        data = "admin:updateban:u:ban-abc123"
        message = SimpleNamespace()
        answers = []

        async def answer(self, *args, **kwargs):
            self.answers.append((args, kwargs))

    query = Query()
    update = SimpleNamespace(
        callback_query=query,
        effective_user=SimpleNamespace(id=111, full_name="Not admin", first_name="Not admin"),
    )
    asyncio.run(bot.button_callback(update, SimpleNamespace(user_data={})))
    assert query.answers and query.answers[-1][1].get("show_alert") is True


def test_notifier_sends_to_every_admin_from_web_thread_and_keeps_unlinked_devices(monkeypatch):
    monkeypatch.setattr(bot, "ADMIN_IDS", {101, 202})
    sent = []

    class FakeBot:
        async def send_message(self, **kwargs):
            sent.append(kwargs)

    configured = []
    application = SimpleNamespace(bot=FakeBot())

    async def exercise():
        bot._register_app_update_ban_notifier(application, asyncio.get_running_loop(), configured.append)
        event = _update_block(user_id=None)
        worker = threading.Thread(target=configured[0], args=(event,))
        worker.start()
        worker.join()
        await asyncio.sleep(0.05)

    asyncio.run(exercise())

    assert {item["chat_id"] for item in sent} == {101, 202}
    for item in sent:
        assert "4.2.1" in item["text"]
        assert "108" in item["text"]
        flattened = " ".join(item["text"].split())
        assert bot.sc("motivo") in flattened
        assert bot.sc("requerida") in flattened
        assert "d" * 64 in item["text"]
        assert bot.sc("no vinculado") in item["text"]
        callbacks = _callback_data(item["reply_markup"])
        assert callbacks == ["admin:updateban:u:ban-abc123", "admin:updateban:k:ban-abc123"]
        assert all(len(data.encode("utf-8")) <= 64 for data in callbacks)
