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

    assert "admin:appban:lookup" in keyboard_after_ban
    assert "admin:appsupport:0" in keyboard_after_ban
    lookup = bot.admin_app_user_lookup_text(54321)
    lookup_keyboard = _callback_data(bot.admin_app_user_lookup_keyboard(54321))
    assert device_id in lookup
    assert "54321" in lookup
    assert "⛔" in lookup
    assert "admin:appban:unban:54321:0" in lookup_keyboard


def test_apk_support_inbox_shows_ticket_telegram_id_and_full_mobile_id(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    device_id = "a" * 64
    ticket = sync_api.create_app_support_message(64444, device_id, "Solicito revisar el baneo")

    text = bot.admin_app_support_text(0)
    keyboard = _callback_data(bot.admin_app_support_keyboard(0))
    assert ticket["id"] in text
    assert "64444" in text
    assert device_id in text
    assert "Solicito revisar el baneo" in text
    assert "admin:appusers:0" in keyboard


def test_admin_searches_apk_user_by_telegram_id_and_gets_exclusive_action(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    monkeypatch.setattr(bot, "USERS_DATA_FILE", tmp_path / "users.json")
    device_id = "c" * 64
    assert sync_api.consume_pairing_code(sync_api.create_pairing_code(98765), device_id)
    monkeypatch.setattr(bot, "is_private_update", lambda _update: True)
    monkeypatch.setattr(bot, "is_owner", lambda _user_id: True)
    monkeypatch.setattr(bot, "is_user_blocked", lambda _user_id: False)
    monkeypatch.setattr(bot, "register_user", lambda _user: None)
    edits = []

    async def fake_edit(_message, text, **_kwargs):
        edits.append(text)

    monkeypatch.setattr(bot, "edit_query_message_text", fake_edit)

    class Query:
        data = "admin:appban:lookup"
        message = SimpleNamespace()

        async def answer(self, *_args, **_kwargs):
            return None

    owner = SimpleNamespace(id=90000, full_name="Owner", first_name="Owner")
    context = SimpleNamespace(user_data={}, application=SimpleNamespace())
    asyncio.run(bot.button_callback(SimpleNamespace(callback_query=Query(), effective_user=owner), context))
    assert context.user_data["admin_state"]["step"] == "admin_app_user_lookup"
    assert edits and "ᴛᴇʟᴇ" in edits[-1]

    replies = []

    async def reply_text(text, **kwargs):
        replies.append((text, kwargs))

    message = SimpleNamespace(text="98765", reply_text=reply_text)
    update = SimpleNamespace(
        effective_message=message,
        effective_user=owner,
        effective_chat=SimpleNamespace(type="private"),
    )

    async def membership(_update, _context):
        return True

    monkeypatch.setattr(bot, "ensure_membership", membership)
    asyncio.run(bot.handle_text(update, context))
    assert replies
    assert device_id in replies[-1][0]
    assert "98765" in replies[-1][0]
    assert "admin:appban:ban:98765:0" in _callback_data(replies[-1][1]["reply_markup"])
    assert not bot.is_user_blocked(98765)


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
