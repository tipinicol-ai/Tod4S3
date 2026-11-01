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
