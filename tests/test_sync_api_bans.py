import time

import sync_api


def test_ban_blocks_telegram_id_and_every_linked_device(monkeypatch, tmp_path):
    state_file = tmp_path / "sync_app.json"
    monkeypatch.setattr(sync_api, "STATE_FILE", state_file)

    code = sync_api.create_pairing_code(42001)
    device_id = "a" * 64
    token = sync_api.consume_pairing_code(code, device_id)
    assert token
    result = sync_api.ban_app_user(42001, admin_id=900)
    assert result["device_ids"] == [device_id]

    assert sync_api.is_app_user_banned(42001, "different-device")
    assert sync_api.is_app_user_banned(88888, device_id)
    assert sync_api.pairing_code_ban(sync_api.create_pairing_code(88888), device_id)["scope"] == "device"

    rows, count = sync_api.app_users_page()
    assert count == 1
    assert rows[0]["user_id"] == 42001
    assert rows[0]["device_ids"] == [device_id]
    assert rows[0]["banned"] is True
    assert "t4s3_" not in str(rows)


def test_unban_removes_only_that_users_device_association(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    device_id = "b" * 64
    for user_id in (42001, 42002):
        code = sync_api.create_pairing_code(user_id)
        assert sync_api.consume_pairing_code(code, device_id)
    for user_id in (42001, 42002):
        sync_api.ban_app_user(user_id, admin_id=900)

    result = sync_api.unban_app_user(42001, admin_id=900)
    assert result["changed"] is True
    assert sync_api.is_app_user_banned(42001) is False
    assert sync_api.is_app_user_banned(77777, device_id) is True

    saved = sync_api._read()
    assert [event["action"] for event in saved["app_ban_history"]] == ["ban", "ban", "unban"]
    assert saved["app_ban_history"][-1]["user_id"] == 42001
    assert saved["app_ban_history"][-1]["admin_id"] == 900
    assert saved["app_ban_history"][-1]["device_ids"] == [device_id]

    sync_api.unban_app_user(42002)
    assert sync_api.is_app_user_banned(77777, device_id) is False


def test_app_user_list_marks_recent_sessions_online(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    token = sync_api.consume_pairing_code(sync_api.create_pairing_code(333), "c" * 64)
    assert token
    rows, count = sync_api.app_users_page()
    assert count == 1
    assert rows[0]["online"] is True
    assert rows[0]["last_seen_at"] <= int(time.time())


def test_admin_account_and_shared_admin_device_remain_allowed(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    shared_device = "d" * 64
    for user_id in (900, 901):
        code = sync_api.create_pairing_code(user_id)
        assert sync_api.consume_pairing_code(code, shared_device)

    try:
        sync_api.ban_app_user(900, admin_id=900)
        assert False, "admin must not be able to ban their own account"
    except ValueError as error:
        assert str(error) == "protected_admin_id"

    result = sync_api.ban_app_user(901, admin_id=900)
    assert result["device_ids"] == []
    assert sync_api.is_app_user_banned(901) is True
    assert sync_api.is_app_user_banned(900, shared_device) is False
