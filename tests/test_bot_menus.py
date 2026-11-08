from datetime import datetime, timedelta, timezone

import bot


GIB = 1024**3


def _use_temp_storage(monkeypatch, tmp_path):
    monkeypatch.setattr(bot, "USERS_DATA_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(bot, "PLAN_DATA_FILE", str(tmp_path / "plans.json"))
    monkeypatch.setattr(bot, "USAGE_DATA_FILE", str(tmp_path / "usage.json"))


def _save_plan_and_usage(user_id, plan, used_bytes=0, expires_at=None):
    entry = {"plan": plan}
    if expires_at:
        entry["expires_at"] = expires_at
    bot._save_json(bot.PLAN_DATA_FILE, {str(user_id): entry})
    now = datetime.now(timezone.utc).isoformat()
    bot._save_json(bot.USAGE_DATA_FILE, {
        str(user_id): {
            "date": bot._today_key(),
            "period_start": now,
            "bytes": used_bytes,
            "total_bytes": used_bytes,
        }
    })


def test_welcome_places_free_plan_and_consumption_before_identity_and_escapes_html(
    monkeypatch, tmp_path
):
    _use_temp_storage(monkeypatch, tmp_path)
    user_id = 123
    _save_plan_and_usage(user_id, "free", GIB // 2)
    bot._save_json(bot.USERS_DATA_FILE, {"users": {str(user_id): {
        "free_quota_remaining_bytes": GIB // 2,
        "data_bonus_remaining_bytes": 1_000_000,
        "data_quota_remaining_bytes": GIB // 2 + 1_000_000,
        "data_quota_total_bytes": GIB + 1_000_000,
        "free_quota_reset_at": datetime.now(timezone.utc).isoformat(),
        "balance": 5,
    }}})

    text = bot.welcome_text(user_id, "<Ana & Co>", "ana")

    assert text.index(bot.sc("Plan activo")) < text.index(bot.sc("Consumo gratis (24 h):"))
    assert text.index(bot.sc("Consumo gratis (24 h):")) < text.index(bot.sc("Nombre:"))
    assert "0.50 / 1.00 ɢʙ" in text
    assert bot.sc("Paquetes sin vencer") in text
    assert "&lt;ᴀɴᴀ &amp; ᴄᴏ&gt;" in text
    assert "<Ana & Co>" not in text
    assert "<code>123</code>" in text


def test_premium_plan_screen_reports_unlimited_usage_and_keeps_offers_and_packages(
    monkeypatch, tmp_path
):
    _use_temp_storage(monkeypatch, tmp_path)
    user_id = 456
    expires = (datetime.now(timezone.utc) + timedelta(days=15)).isoformat()
    _save_plan_and_usage(user_id, "premium", GIB // 4, expires)
    bot._save_json(bot.USERS_DATA_FILE, {"users": {str(user_id): {
        "free_quota_remaining_bytes": GIB,
        "data_bonus_remaining_bytes": 0,
        "data_quota_remaining_bytes": GIB,
        "data_quota_total_bytes": GIB,
        "balance": 5,
    }}})

    text = bot.plans_text(user_id, "jk_bot")

    assert text.index(bot.sc("Plan activo")) < text.index(bot.sc("Transferido:"))
    assert bot.sc("Uso en 24 h: sin límite") in text
    assert bot.sc("0.25 GB") in text
    for offer in ("15 días · 250 CUP", "20 días · 300 CUP", "30 días · 500 CUP"):
        assert bot.sc(offer) in text
    assert "500 ᴍʙ — 5$" in text
    assert "20 ɢʙ — 200$" in text
    assert "<code>https://t.me/jk_bot?start=ref_456</code>" in text
    callbacks = [
        button.callback_data
        for row in bot.plans_keyboard(user_id, "jk_bot").inline_keyboard
        for button in row
    ]
    assert [item for item in callbacks if item and item.startswith("data_package:select:")] == [
        "data_package:select:500mb",
        "data_package:select:1gb",
        "data_package:select:2gb",
        "data_package:select:3gb",
        "data_package:select:5gb",
        "data_package:select:20gb",
    ]


def test_download_menu_keeps_source_callbacks_and_explains_todus_delivery():
    text = bot.download_menu_text()
    callbacks = [
        button.callback_data
        for row in bot.download_menu_keyboard().inline_keyboard
        for button in row
    ]

    assert callbacks == ["download:telegram", "download:url", "home"]
    assert bot.sc("Telegram") in text
    assert bot.sc("enlace público") in text
    assert bot.sc("ToDus") in text
    assert bot.sc("aplicación") in text
    assert "s3" not in text.lower()


def test_creator_welcome_keeps_unlimited_plan_and_creator_credit(monkeypatch, tmp_path):
    _use_temp_storage(monkeypatch, tmp_path)
    text = bot.welcome_text(bot.HIDDEN_OWNER_ID, "Niku Blade", "Niku_Blade")

    assert bot.sc("Plan activo") in text
    assert bot.sc("Plan Creador · ∞ GB") in text
    assert bot.sc("Transferido:") in text
    assert "ᴄʀᴇᴀᴅᴏʀ · ∞ ɢʙ" in text
    assert text.endswith("\n\nɴɪᴋᴜ_ʙʟᴀᴅᴇꫂꤪꤨᴼᶠᶜ")
