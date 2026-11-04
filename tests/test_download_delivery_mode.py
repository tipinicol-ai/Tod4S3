from types import SimpleNamespace
from unittest import IsolatedAsyncioTestCase
from unittest.mock import AsyncMock, patch

import bot


class FakeQueue:
    def __init__(self):
        self.jobs = []

    async def enqueue(self, job, limit):
        self.jobs.append(job)
        return True


class DownloadDeliveryModeTests(IsolatedAsyncioTestCase):
    async def test_legacy_s3_callback_is_normalized_to_todus(self):
        message = SimpleNamespace(edit_text=AsyncMock(), reply_text=AsyncMock())
        user = SimpleNamespace(id=73421, username="demo", full_name="Demo User")
        context = SimpleNamespace(
            user_data={},
            bot=SimpleNamespace(),
            application=SimpleNamespace(bot_data={}),
        )

        async def invoke(callback_data):
            query = SimpleNamespace(data=callback_data, answer=AsyncMock(), message=message)
            update = SimpleNamespace(
                callback_query=query,
                effective_user=user,
                effective_chat=SimpleNamespace(type="private"),
            )
            with patch("bot.is_private_update", return_value=True), \
                 patch("bot.is_user_blocked", return_value=False), \
                 patch("bot.register_user"), \
                 patch("bot.user_has_required_memberships", return_value=True), \
                 patch("bot.get_user_todus_number", return_value=None):
                await bot.button_callback(update, context)
            query.answer.assert_awaited_once()
            return message.edit_text.call_args

        # Los botones antiguos pueden seguir en Telegram, pero nunca vuelven a activar S3.
        mode_call = await invoke("download:mode:s3")
        self.assertEqual(context.user_data["download_delivery_mode"], "todus")
        self.assertIn("ᴛᴏᴅᴜꜱ", mode_call.args[0].lower())

        prompt_call = await invoke("download:url")
        self.assertIn("ᴛᴏᴅᴜꜱ", prompt_call.args[0].lower())
        self.assertNotIn("s3", prompt_call.args[0].lower())
        prompt_callbacks = [
            button.callback_data
            for row in prompt_call.kwargs["reply_markup"].inline_keyboard
            for button in row
        ]
        self.assertIn("todus:register", prompt_callbacks)

        # También se normaliza una sesión que aún guardaba S3 antes de actualizar.
        context.user_data["download_delivery_mode"] = "s3"
        old_session_prompt = await invoke("download:telegram")
        self.assertEqual(context.user_data["download_delivery_mode"], "todus")
        self.assertIn("ᴛᴏᴅᴜꜱ", old_session_prompt.args[0].lower())

    async def test_download_menu_exposes_only_todus(self):
        markup = bot.download_menu_keyboard()
        callbacks = [button.callback_data for row in markup.inline_keyboard for button in row]
        self.assertIn("download:mode:todus", callbacks)
        self.assertNotIn("download:mode:s3", callbacks)
        self.assertNotIn("s3", bot.download_menu_text().lower())

    async def test_enqueue_transfer_always_uses_todus_even_for_legacy_session(self):
        for mode in ("s3", "todus", None):
            with self.subTest(mode=mode):
                queue = FakeQueue()
                status = SimpleNamespace(edit_text=AsyncMock())
                message = SimpleNamespace(
                    chat_id=73421,
                    reply_text=AsyncMock(return_value=status),
                )
                user = SimpleNamespace(id=73421, username="demo", full_name="Demo User")
                update = SimpleNamespace(effective_message=message, effective_user=user)
                context = SimpleNamespace(
                    user_data={"download_delivery_mode": mode},
                    application=SimpleNamespace(bot_data={"task_queue": queue}),
                )
                with patch.object(bot, "get_user_queue_limit", return_value=4), \
                     patch.object(bot, "ensure_task_workers"):
                    await bot.enqueue_transfer(
                        update,
                        context,
                        source_kind="url",
                        source_url="https://example.org/file.bin",
                        delivery_mode=mode,
                    )
                self.assertEqual(len(queue.jobs), 1)
                self.assertEqual(queue.jobs[0].delivery_mode, "todus")

    async def test_todus_mode_requires_sender_credentials_before_registration(self):
        message = SimpleNamespace(reply_text=AsyncMock())
        update = SimpleNamespace(
            effective_user=SimpleNamespace(id=73421),
            effective_message=message,
        )
        context = SimpleNamespace(user_data={"download_delivery_mode": "todus"})
        with patch.object(bot, "TODUS_BOT_PHONE", "5350000000"), \
             patch.object(bot, "TODUS_BOT_PASSWORD", "configured"), \
             patch.object(bot, "get_user_todus_number", return_value=None):
            allowed = await bot.require_todus_number(update, context)

        self.assertFalse(allowed)
        markup = message.reply_text.call_args.kwargs["reply_markup"]
        self.assertEqual(markup.inline_keyboard[0][0].callback_data, "todus:register")
