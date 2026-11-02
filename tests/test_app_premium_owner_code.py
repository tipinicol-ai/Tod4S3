import bot


def test_creator_owner_receives_permanent_app_premium_token(monkeypatch):
    captured = {}
    monkeypatch.setattr(bot, "is_owner", lambda user_id: int(user_id) == 7777)
    monkeypatch.setattr(bot, "get_user_plan_entry", lambda _user_id: {"plan": "owner"})

    def issue(user_id, expires_at):
        captured["user_id"] = user_id
        captured["expires_at"] = expires_at
        return "T4S3-CREATOR-TEST"

    monkeypatch.setattr(bot.sync_api, "issue_premium_activation_token", issue)

    assert bot.issue_app_premium_activation_code(7777) == "T4S3-CREATOR-TEST"
    assert captured == {"user_id": 7777, "expires_at": ""}


def test_non_premium_non_owner_does_not_receive_app_token(monkeypatch):
    monkeypatch.setattr(bot, "is_owner", lambda _user_id: False)
    monkeypatch.setattr(bot, "get_user_plan_entry", lambda _user_id: {"plan": "free"})

    assert bot.issue_app_premium_activation_code(42) is None
