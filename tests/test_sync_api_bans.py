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


def test_admin_owner_can_be_banned_from_apk_including_its_device(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    owner_device = "d" * 64
    code = sync_api.create_pairing_code(900)
    assert sync_api.consume_pairing_code(code, owner_device)

    result = sync_api.ban_app_user(900, admin_id=900)
    assert result["device_ids"] == [owner_device]
    assert sync_api.is_app_user_banned(900, owner_device) is True


def test_lookup_by_telegram_id_returns_full_apk_device_and_ban_status(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    device_id = "7" * 64
    assert sync_api.consume_pairing_code(sync_api.create_pairing_code(7100), device_id)
    sync_api.ban_app_user(7100, admin_id=900)

    info = sync_api.app_user_info(7100)
    assert info["user_id"] == 7100
    assert info["device_ids"] == [device_id]
    assert info["banned"] is True
    assert info["banned_device_ids"] == [device_id]
    assert sync_api.app_user_info(7101)["banned"] is False


def test_app_support_messages_are_ticketed_by_authenticated_identity_and_page_newest_first(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    device_id = "8" * 64
    first = sync_api.create_app_support_message(8100, device_id, "El bloqueo parece un error")
    monkeypatch.setattr(sync_api.time, "time", lambda: first["created_at"] + 1)
    second = sync_api.create_app_support_message(8101, "9" * 64, "No me cargan los archivos")

    page, total = sync_api.app_support_messages_page(0, 1)
    assert total == 2
    assert page[0]["id"] == second["id"]
    assert page[0]["user_id"] == 8101
    assert sync_api.app_support_message_by_id(first["id"])["device_id"] == device_id
    saved = sync_api._read()
    assert len(saved["app_support_messages"]) == 2


def test_banned_user_can_authenticate_support_with_unconsumed_pairing_code(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    code = sync_api.create_pairing_code(81200)
    device_id = "e" * 64
    sync_api.ban_app_user(81200, admin_id=900)

    assert sync_api.consume_pairing_code(code, device_id) is None
    assert sync_api.support_identity_from_pairing(code, device_id) == {
        "user_id": 81200,
        "device_id": device_id,
    }



def test_update_declines_block_only_device_on_third_distinct_request(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    device_id = "a" * 64
    first_token = sync_api.consume_pairing_code(sync_api.create_pairing_code(71001), device_id)
    second_token = sync_api.consume_pairing_code(sync_api.create_pairing_code(71002), device_id)
    assert first_token and second_token

    first = sync_api.record_app_update_rejection(71001, device_id, 79, "1.5.34", "decline-1")
    second = sync_api.record_app_update_rejection(71002, device_id, 79, "1.5.34", "decline-2")
    third = sync_api.record_app_update_rejection(71001, device_id, 79, "1.5.34", "decline-3")
    assert [first["count"], second["count"], third["count"]] == [1, 2, 3]
    assert [first["blocked"], second["blocked"], third["blocked"]] == [False, False, True]
    assert third["event"] == {
        "ban_id": third["event"]["ban_id"], "user_id": 71001, "device_id": device_id,
        "version_code": 79, "version_name": "1.5.34",
        "reason": "Este teléfono rechazó tres veces la actualización v1.5.34 (código 79).",
    }
    assert sync_api.app_access_ban(71001, device_id)["scope"] == "device"
    assert sync_api.app_access_ban(71002, device_id)["scope"] == "device"
    assert sync_api.app_access_ban(71001, "b" * 64) is None
    saved = sync_api._read()
    assert saved["app_bans"]["users"] == {}
    assert saved["app_bans"]["devices"][device_id]["user_ids"] == ["71001", "71002"]


def test_update_decline_request_id_is_idempotent_and_release_version_starts_at_one(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    device_id = "c" * 64
    first = sync_api.record_app_update_rejection(None, device_id, 79, "1.5.34", "same-request")
    duplicate = sync_api.record_app_update_rejection(None, device_id, 79, "1.5.34", "same-request")
    second = sync_api.record_app_update_rejection(None, device_id, 79, "1.5.34", "second-request")
    next_release = sync_api.record_app_update_rejection(None, device_id, 80, "1.5.35", "new-release")
    assert first["count"] == duplicate["count"] == 1
    assert duplicate["event"] is None
    assert second["count"] == 2
    assert next_release["count"] == 1
    assert next_release["blocked"] is False


def test_update_device_unban_uses_ban_id_and_is_listed(monkeypatch, tmp_path):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    device_id = "d" * 64
    for number in range(3):
        result = sync_api.record_app_update_rejection(82001, device_id, 79, "1.5.34", f"request-{number}")
    ban_id = result["event"]["ban_id"]
    rows, total = sync_api.app_update_blocks_page(0, 10)
    assert total == 1
    assert rows[0]["ban_id"] == ban_id
    assert rows[0]["blocked_at"]
    assert sync_api.unban_app_device_by_ban_id("not-the-ban") == {
        "ban_id": "not-the-ban", "changed": False,
    }
    assert sync_api.unban_app_device_by_ban_id(ban_id, admin_id=900)["changed"] is True
    assert sync_api.app_access_ban(None, device_id) is None
    rows, total = sync_api.app_update_blocks_page(0, 10)
    assert total == 0
    reset = sync_api.record_app_update_rejection(None, device_id, 79, "1.5.34", "after-admin-unban")
    assert reset["count"] == 1
    assert reset["blocked"] is False
    assert sync_api.unban_app_device_by_ban_id(ban_id)["changed"] is False
