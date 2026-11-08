import asyncio
import hashlib
from types import SimpleNamespace

import pytest

import bot


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("¿Tienes una aplicación?", "latest_apk"),
        ("Pásame el APK", "latest_apk"),
        ("Como descargo la aplicaion", "latest_apk"),
        ("el bot no responde", "bot_problem"),
        ("como me registro en el bot", "bot_registration"),
        ("como vinculo la APK con el bot", "linking"),
        ("Cómo inicio sesión en ToDus", "todus_login"),
        ("No me llega el código de ToDus", "todus_registration"),
        ("como descargo un archivo con el bot", "bot_download"),
        ("como descargo", "latest_apk"),
        ("tengo una duda", "bot_help"),
    ],
)
def test_support_classifier_handles_spanish_keywords_and_common_typos(text, expected):
    assert bot.classify_app_support_intent(text) == expected


def test_unrelated_text_does_not_trigger_support():
    assert bot.classify_app_support_intent("Buenas noches a todos") is None


def test_support_group_is_restricted_to_the_configured_group(monkeypatch):
    monkeypatch.setattr(bot, "APP_SUPPORT_GROUP", "@grupozonaoscura")
    target = SimpleNamespace(id=-100100, type="supergroup", username="grupozonaoscura")
    other = SimpleNamespace(id=-100200, type="supergroup", username="otrogrupo")
    private = SimpleNamespace(id=44, type="private", username=None)
    assert bot.is_app_support_group(target)
    assert not bot.is_app_support_group(other)
    assert not bot.is_app_support_group(private)


def test_support_group_can_be_configured_by_telegram_chat_id(monkeypatch):
    monkeypatch.setattr(bot, "APP_SUPPORT_GROUP", "-100100")
    target = SimpleNamespace(id=-100100, type="supergroup", username=None)
    assert bot.is_app_support_group(target)


def test_update_manifest_requires_exact_repository_file_and_hash():
    manifest = {
        "version_code": 79,
        "version_name": "1.5.34",
        "apk_url": "https://raw.githubusercontent.com/tipinicol-ai/Tod4S3/main/Tod4S3-Sync-v1.5.34.apk",
        "sha256": "a" * 64,
    }
    release = bot.validate_android_update_manifest(manifest)
    assert release["version_name"] == "1.5.34"
    assert release["filename"] == "Tod4S3-Sync-v1.5.34.apk"
    assert release["sha256"] == "a" * 64

    manifest["apk_url"] = "https://example.com/anything.apk"
    with pytest.raises(ValueError, match="repositorio oficial"):
        bot.validate_android_update_manifest(manifest)


def test_bounded_http_reader_collects_all_chunks_and_enforces_limit():
    class Content:
        def __init__(self, chunks):
            self.chunks = chunks

        async def iter_chunked(self, _size):
            for chunk in self.chunks:
                yield chunk

    response = SimpleNamespace(content=Content([b"first", b"-", b"second"]))
    assert asyncio.run(bot.read_bounded_http_body(response, 20)) == b"first-second"

    too_large = SimpleNamespace(content=Content([b"1234", b"5678"]))
    with pytest.raises(ValueError, match="tamaño permitido"):
        asyncio.run(bot.read_bounded_http_body(too_large, 7))


def test_release_caption_is_short_styled_and_contains_no_links_or_support_steps():
    caption = bot.app_release_caption("1.5.34")
    assert "1.5.34" in caption
    assert "📲 NUEVA ACTUALIZACIÓN DISPONIBLE" in caption
    assert "Tod4S3 Sync · v1.5.34" in caption
    assert "Pulsa el APK adjunto para descargarlo" in caption
    assert "no desinstales la app" in caption
    assert len(caption) < 250
    assert "github" not in caption.casefold()
    assert "http://" not in caption.casefold()
    assert "https://" not in caption.casefold()
    assert "/app" not in caption


def test_support_replies_warn_users_not_to_share_login_codes():
    text = bot.normalize_app_support_text(bot.app_support_answer("todus_registration"))
    assert "no compartas" in text
    assert "contrasena" in text
    assert "todus 5xxxxxxxx" in text


def test_group_release_is_sent_only_to_the_group(monkeypatch):
    monkeypatch.setattr(bot, "APP_SUPPORT_GROUP", "@grupozonaoscura")
    release_bytes = b"fake apk bytes"
    release = {
        "version_code": 79,
        "version_name": "1.5.34",
        "apk_url": "https://raw.githubusercontent.com/tipinicol-ai/Tod4S3/main/Tod4S3-Sync-v1.5.34.apk",
        "filename": "Tod4S3-Sync-v1.5.34.apk",
        "sha256": hashlib.sha256(release_bytes).hexdigest(),
        "data": release_bytes,
    }

    async def fake_latest_android_release(_application):
        return release

    monkeypatch.setattr(bot, "latest_android_release", fake_latest_android_release)
    calls = []

    async def fake_send_document(**kwargs):
        calls.append(kwargs)
        if kwargs["chat_id"] == -100100:
            return SimpleNamespace(document=SimpleNamespace(file_id="telegram-file-id"))
        return SimpleNamespace(document=SimpleNamespace(file_id="telegram-file-id"))

    fake_bot = SimpleNamespace(send_document=fake_send_document)
    context = SimpleNamespace(
        bot=fake_bot,
        application=SimpleNamespace(bot_data={"bot_username": "toduss3_bot"}),
    )
    update = SimpleNamespace(
        effective_chat=SimpleNamespace(id=-100100, type="supergroup", username="grupozonaoscura"),
        effective_user=SimpleNamespace(id=735, is_bot=False),
        effective_message=SimpleNamespace(message_id=55),
    )

    asyncio.run(bot.send_latest_app_release(update, context, support_intent="linking"))

    assert len(calls) == 1
    assert calls[0]["chat_id"] == -100100
    assert calls[0]["reply_to_message_id"] == 55
    assert "reply_markup" not in calls[0]
    caption = calls[0]["caption"]
    assert "1.5.34" in caption
    assert "github" not in caption.casefold()
    assert "https://" not in caption.casefold()


def test_failed_apk_attachment_replies_without_markup_or_github_link(monkeypatch):
    monkeypatch.setattr(bot, "APP_SUPPORT_GROUP", "@grupozonaoscura")
    release_bytes = b"fake apk bytes"
    release = {
        "version_code": 79,
        "version_name": "1.5.34",
        "apk_url": "https://raw.githubusercontent.com/tipinicol-ai/Tod4S3/main/Tod4S3-Sync-v1.5.34.apk",
        "filename": "Tod4S3-Sync-v1.5.34.apk",
        "sha256": hashlib.sha256(release_bytes).hexdigest(),
        "data": release_bytes,
    }

    async def fake_latest_android_release(_application):
        return release

    monkeypatch.setattr(bot, "latest_android_release", fake_latest_android_release)
    replies = []

    async def failed_send_document(**_kwargs):
        raise RuntimeError("Telegram upload failed")

    async def fake_reply_text(text, **kwargs):
        replies.append((text, kwargs))

    context = SimpleNamespace(
        bot=SimpleNamespace(send_document=failed_send_document),
        application=SimpleNamespace(bot_data={"bot_username": "toduss3_bot"}),
    )
    update = SimpleNamespace(
        effective_chat=SimpleNamespace(id=-100100, type="supergroup", username="grupozonaoscura"),
        effective_user=SimpleNamespace(id=735, is_bot=False),
        effective_message=SimpleNamespace(message_id=55, reply_text=fake_reply_text),
    )

    assert asyncio.run(bot.send_latest_app_release(update, context, support_intent="linking")) is None

    assert len(replies) == 1
    fallback, kwargs = replies[0]
    assert "vuelve a intentarlo mas tarde" in bot.normalize_app_support_text(fallback)
    assert "github" not in fallback.casefold()
    assert "https://" not in fallback.casefold()
    assert "reply_markup" not in kwargs


def test_release_request_in_private_does_not_send_anything(monkeypatch):
    monkeypatch.setattr(bot, "APP_SUPPORT_GROUP", "@grupozonaoscura")
    calls = []

    async def forbidden_fetch(_application):
        raise AssertionError("No se debe consultar ni enviar el APK por privado")

    async def fake_send_document(**kwargs):
        calls.append(kwargs)

    monkeypatch.setattr(bot, "latest_android_release", forbidden_fetch)
    context = SimpleNamespace(
        bot=SimpleNamespace(send_document=fake_send_document),
        application=SimpleNamespace(bot_data={}),
    )
    update = SimpleNamespace(
        effective_chat=SimpleNamespace(id=735, type="private", username=None),
        effective_user=SimpleNamespace(id=735, is_bot=False),
        effective_message=SimpleNamespace(message_id=56),
    )
    assert asyncio.run(bot.send_latest_app_release(update, context)) is None
    assert calls == []


def test_text_support_reply_is_silent_outside_the_group():
    async def should_not_reply(*args, **kwargs):
        raise AssertionError("La respuesta automática debe limitarse al grupo")

    context = SimpleNamespace(
        bot=SimpleNamespace(send_message=should_not_reply),
        application=SimpleNamespace(bot_data={}),
    )
    update = SimpleNamespace(
        effective_chat=SimpleNamespace(id=735, type="private", username=None),
        effective_user=SimpleNamespace(id=735, is_bot=False),
        effective_message=SimpleNamespace(reply_text=should_not_reply),
    )
    asyncio.run(bot.send_app_support_answer(update, context, "respuesta"))


def test_generic_support_message_has_styled_card():
    text = bot.app_support_answer("bot_help")
    assert "╭" in text
    assert "privado" in bot.normalize_app_support_text(text)
