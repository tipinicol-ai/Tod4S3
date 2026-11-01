import asyncio
from types import SimpleNamespace

import sync_api
import bot


def _callback_data(markup):
    return [button.callback_data for row in markup.inline_keyboard for button in row]


def test_app_management_is_a_new_admin_button():
    data = _callback_data(bot.admin_section_keyboard("users"))
    assert "admin:appusers:0" in data
    assert "admin:block" not in data
    assert "admin:unblock" not in data


def test_app_admin_list_shows_mobile_fingerprint_and_ban_controls(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    monkeypatch.setattr(bot, "USERS_DATA_FILE", tmp_path / "users.json")
    device_id = "f" * 64
    code = sync_api.create_pairing_code(54321)
    assert sync_api.consume_pairing_code(code, device_id)

    text = bot.admin_app_users_text(0)
    assert "54321" in text
    assert device_id in text
    assert "🟢" in text
    keyboard = _callback_data(bot.admin_app_users_keyboard(0))
    assert "admin:appban:ban:54321:0" in keyboard

    sync_api.ban_app_user(54321, admin_id=90000)
    text_after_ban = bot.admin_app_users_text(0)
    keyboard_after_ban = _callback_data(bot.admin_app_users_keyboard(0))
    assert "⛔" in text_after_ban
    assert "admin:appban:unban:54321:0" in keyboard_after_ban


def test_app_ban_button_works_for_owner_answers_once_and_never_blocks_telegram(
    monkeypatch, tmp_path
):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    monkeypatch.setattr(bot, "USERS_DATA_FILE", tmp_path / "users.json")
    monkeypatch.setattr(bot, "is_private_update", lambda _update: True)
    monkeypatch.setattr(bot, "is_user_blocked", lambda _user_id: False)
    monkeypatch.setattr(bot, "is_owner", lambda _user_id: True)
    monkeypatch.setattr(bot, "register_user", lambda _user: None)
    monkeypatch.setattr(
        bot, "set_user_blocked",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("Telegram bot ban invoked")),
    )
    monkeypatch.setattr(bot, "schedule_data_backup", lambda *_args: None)

    device_id = "9" * 64
    pairing_code = sync_api.create_pairing_code(900)
    assert sync_api.consume_pairing_code(pairing_code, device_id)

    class Query:
        data = "admin:appban:ban:900:0"

        def __init__(self):
            self.message = SimpleNamespace()
            self.answers = 0

        async def answer(self, *_args, **_kwargs):
            self.answers += 1
            if self.answers > 1:
                raise AssertionError("callback answered more than once")

    query = Query()
    update = SimpleNamespace(
        callback_query=query,
        effective_user=SimpleNamespace(id=900, full_name="Owner", first_name="Owner"),
    )
    context = SimpleNamespace(user_data={}, application=SimpleNamespace())
    edits = []

    async def fake_edit(_message, text, **_kwargs):
        edits.append(text)

    monkeypatch.setattr(bot, "edit_query_message_text", fake_edit)
    asyncio.run(bot.button_callback(update, context))

    assert query.answers == 1
    assert sync_api.is_app_user_banned(900, device_id) is True
    assert edits and "✅" in edits[-1]
    assert not bot.is_user_blocked(900)
