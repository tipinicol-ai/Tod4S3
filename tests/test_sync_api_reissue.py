import sync_api


def test_reissued_premium_code_can_move_to_new_device(tmp_path, monkeypatch):
    monkeypatch.setattr(sync_api, "STATE_FILE", tmp_path / "sync_app.json")
    expiry = "2099-07-01T12:00:00+00:00"
    first_code = sync_api.issue_premium_activation_token(42, expiry)
    assert sync_api.claim_premium_activation_token(first_code, 42, "old-device", expiry) == (True, "verified")
    second_code = sync_api.issue_premium_activation_token(42, expiry)
    assert sync_api.claim_premium_activation_token(second_code, 42, "new-apk-device", expiry) == (True, "verified")
    assert sync_api.premium_activation_status(42, "new-apk-device", expiry) == "verified"
