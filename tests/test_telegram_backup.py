import asyncio
import gzip
import io
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import bot as telegram_bot
import sync_api
from telegram_backup import (
    BACKUP_FILENAME,
    BackupError,
    BackupFormatError,
    BackupLayout,
    BackupSizeError,
    FORMAT_NAME,
    FORMAT_VERSION,
    MAGIC,
    NONCE_BYTES,
    build_encrypted_backup,
    build_snapshot,
    decrypt_snapshot,
    derive_backup_key,
    restore_from_telegram,
    restore_snapshot,
    backup_to_telegram,
)

TOKEN = "123456:FAKE-TOKEN-ONLY-FOR-TESTS"


class MemoryFile:
    def __init__(self, payload):
        self.payload = payload

    async def download_to_memory(self, out):
        out.write(self.payload)


class FakeTelegramBackupBot:
    def __init__(self):
        self.pinned = None
        self.messages = {}
        self.files = {}
        self.unpinned = []
        self.sent = []
        self.next_message_id = 100

    async def get_chat(self, chat_id):
        self.last_chat_id = chat_id
        return SimpleNamespace(type="channel", pinned_message=self.pinned)

    async def send_document(self, chat_id, document, filename, caption):
        payload = document.read()
        self.next_message_id += 1
        message_id = self.next_message_id
        file_id = f"file-{message_id}"
        doc = SimpleNamespace(file_id=file_id, file_name=filename)
        message = SimpleNamespace(message_id=message_id, document=doc, caption=caption)
        self.files[file_id] = payload
        self.messages[message_id] = message
        self.sent.append((message, payload, filename, caption))
        return message

    async def edit_message_media(self, chat_id, message_id, media):
        message = self.messages[message_id]
        payload = media.media.input_file_content
        file_id = f"file-{message_id}-edited"
        message.document = SimpleNamespace(file_id=file_id, file_name=media.media.filename)
        message.caption = media.caption
        self.files[file_id] = payload
        return message

    async def get_file(self, file_id):
        return MemoryFile(self.files[file_id])

    async def pin_chat_message(self, chat_id, message_id, disable_notification=True):
        self.pinned = self.messages[message_id]
        return True

    async def unpin_chat_message(self, chat_id, message_id):
        self.unpinned.append(message_id)
        if self.pinned and self.pinned.message_id == message_id:
            self.pinned = None
        return True


def make_layout(root: Path) -> BackupLayout:
    data_dir = root / "data"
    auth_dir = data_dir / "telegram_auth"
    data_dir.mkdir(parents=True, exist_ok=True)
    auth_dir.mkdir(parents=True, exist_ok=True)
    states = {
        "user_plans.json": data_dir / "user_plans.json",
        "daily_usage.json": data_dir / "daily_usage.json",
        "task_history.json": data_dir / "task_history.json",
    }
    return BackupLayout.from_paths(data_dir, auth_dir, states)


def test_encrypted_backup_round_trip_preserves_ban_unban_history_and_device_ids(monkeypatch):
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        source = make_layout(root / "source")
        banned_target = make_layout(root / "target-banned")
        unbanned_target = make_layout(root / "target-unbanned")
        device_id = "e" * 64
        source_state = root / "source" / "sync_app.json"
        banned_state = root / "target-banned" / "sync_app.json"
        unbanned_state = root / "target-unbanned" / "sync_app.json"
        source.state_files["sync_app.json"] = source_state
        banned_target.state_files["sync_app.json"] = banned_state
        unbanned_target.state_files["sync_app.json"] = unbanned_state
        monkeypatch.setattr(sync_api, "STATE_FILE", source_state)

        token = sync_api.consume_pairing_code(sync_api.create_pairing_code(123456), device_id)
        assert token
        sync_api.ban_app_user(123456, admin_id=900)

        encrypted_ban, _, _, _ = build_encrypted_backup(source, TOKEN)
        assert b"app_bans" not in encrypted_ban
        assert device_id.encode("ascii") not in encrypted_ban
        banned_snapshot = decrypt_snapshot(encrypted_ban, TOKEN)
        restored_ban, _ = restore_snapshot(banned_snapshot, banned_target)
        assert restored_ban >= 1
        banned_json = json.loads(banned_state.read_text(encoding="utf-8"))
        assert banned_json["app_bans"]["users"]["123456"]["banned_by"] == 900
        assert device_id in banned_json["app_bans"]["devices"]
        assert banned_json["app_ban_history"][-1]["action"] == "ban"

        sync_api.unban_app_user(123456, admin_id=900)
        encrypted_unban, _, _, _ = build_encrypted_backup(source, TOKEN)
        assert b"app_ban_history" not in encrypted_unban
        assert device_id.encode("ascii") not in encrypted_unban
        unbanned_snapshot = decrypt_snapshot(encrypted_unban, TOKEN)
        restored_unban, _ = restore_snapshot(unbanned_snapshot, unbanned_target)

        assert restored_unban >= 1
        unbanned_json = json.loads(unbanned_state.read_text(encoding="utf-8"))
        assert unbanned_json["app_bans"]["users"] == {}
        assert unbanned_json["app_bans"]["devices"] == {}
        assert [event["action"] for event in unbanned_json["app_ban_history"]] == ["ban", "unban"]
        assert all(event["user_id"] == 123456 and event["admin_id"] == 900
                   for event in unbanned_json["app_ban_history"])
        assert all(event["device_ids"] == [device_id] for event in unbanned_json["app_ban_history"])
        assert unbanned_json["tokens"][next(iter(unbanned_json["tokens"]))]["device_id"] == device_id


class TelegramBackupFlowTests(unittest.IsolatedAsyncioTestCase):
    async def test_runtime_backup_layout_includes_premium_and_app_token_state(self):
        layout = telegram_bot.telegram_backup_layout()
        self.assertEqual(layout.state_files["user_plans.json"], Path(telegram_bot.PLAN_DATA_FILE))
        self.assertEqual(layout.state_files["sync_app.json"], Path(telegram_bot.SYNC_APP_STATE_FILE))

    async def test_backup_captures_premium_plans_and_activation_tokens_encrypted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            layout = make_layout(root)
            plans = root / "user_plans.json"
            app_state = root / "sync_app.json"
            plans.write_text('{"123":{"plan":"premium","expires_at":"2030-01-01T00:00:00+00:00"}}', encoding="utf-8")
            app_state.write_text('{"premium_activations":{"123":{"token_hash":"deadbeef","device_id":"device-1"}}}', encoding="utf-8")
            layout.state_files["user_plans.json"] = plans
            layout.state_files["sync_app.json"] = app_state

            encrypted, snapshot, _, _ = build_encrypted_backup(layout, TOKEN)
            restored = decrypt_snapshot(encrypted, TOKEN)
            states = {entry["path"]: json.loads(__import__("base64").b64decode(entry["data_b64"]))
                      for entry in restored["files"] if entry["kind"] == "state"}

            self.assertIn("state/user_plans.json", states)
            self.assertEqual(states["state/user_plans.json"]["123"]["plan"], "premium")
            self.assertIn("state/sync_app.json", states)
            self.assertEqual(
                states["state/sync_app.json"]["premium_activations"]["123"]["device_id"],
                "device-1",
            )
            self.assertNotIn(b"deadbeef", encrypted)
            self.assertNotIn(b"premium_activations", encrypted)

    async def test_first_backup_is_encrypted_uploaded_verified_and_pinned(self):
        with tempfile.TemporaryDirectory() as temp:
            layout = make_layout(Path(temp))
            layout.state_files["user_plans.json"].write_text('{"123":{"plan":"free"}}', encoding="utf-8")
            bot = FakeTelegramBackupBot()

            ok = await backup_to_telegram(bot, TOKEN, "-1001234567890", layout)

            self.assertTrue(ok)
            self.assertEqual(len(bot.sent), 1)
            message, encrypted, filename, caption = bot.sent[0]
            self.assertEqual(filename, BACKUP_FILENAME)
            self.assertNotIn(b"user_plans", encrypted)
            self.assertNotIn(b"free", encrypted)
            self.assertEqual(bot.pinned.message_id, message.message_id)
            snapshot = decrypt_snapshot(encrypted, TOKEN)
            self.assertEqual(snapshot["format"], FORMAT_NAME)
            self.assertEqual(snapshot["version"], FORMAT_VERSION)
            self.assertEqual(bot.last_chat_id, -1001234567890)

    async def test_update_does_not_restore_deleted_auth_file(self):
        with tempfile.TemporaryDirectory() as temp:
            layout = make_layout(Path(temp))
            state_file = layout.state_files["user_plans.json"]
            state_file.write_text('{"123":{"plan":"free"}}', encoding="utf-8")
            session_file = layout.auth_dir / "mtproto_bot.session"
            session_file.write_bytes(b"fake-telegram-session")
            bot = FakeTelegramBackupBot()

            await backup_to_telegram(bot, TOKEN, "-1001234567890", layout)
            old_id = bot.pinned.message_id
            session_file.unlink()
            state_file.write_text('{"123":{"plan":"premium"}}', encoding="utf-8")

            await backup_to_telegram(bot, TOKEN, "-1001234567890", layout)

            self.assertEqual(len(bot.sent), 1)
            self.assertEqual(bot.pinned.message_id, old_id)
            new_snapshot = decrypt_snapshot(bot.files[bot.pinned.document.file_id], TOKEN)
            archive_files = {entry["path"]: entry for entry in new_snapshot["files"]}
            self.assertNotIn("telegram_auth/mtproto_bot.session", archive_files)

    async def test_update_preserves_state_missing_during_a_deploy(self):
        with tempfile.TemporaryDirectory() as temp:
            layout = make_layout(Path(temp))
            state_file = layout.state_files["user_plans.json"]
            state_file.write_text('{"123":{"plan":"premium"}}', encoding="utf-8")
            bot = FakeTelegramBackupBot()

            await backup_to_telegram(bot, TOKEN, "-100123", layout)
            old_id = bot.pinned.message_id
            state_file.unlink()
            await backup_to_telegram(bot, TOKEN, "-100123", layout)

            self.assertEqual(bot.pinned.message_id, old_id)
            updated = decrypt_snapshot(bot.files[bot.pinned.document.file_id], TOKEN)
            state_entry = next(entry for entry in updated["files"] if entry["path"] == "state/user_plans.json")
            import base64
            self.assertEqual(
                base64.b64decode(state_entry["data_b64"]),
                b'{"123":{"plan":"premium"}}',
            )

    async def test_restore_writes_only_missing_files_and_never_overwrites_local_session(self):
        with tempfile.TemporaryDirectory() as temp:
            source_layout = make_layout(Path(temp) / "source")
            source_layout.state_files["user_plans.json"].write_text('{"restored":true}', encoding="utf-8")
            (source_layout.auth_dir / "mtproto_bot.session").write_bytes(b"session-from-cloud")
            encrypted, _snapshot, _size, _count = build_encrypted_backup(source_layout, TOKEN)

            bot = FakeTelegramBackupBot()
            uploaded = await bot.send_document(-100123, io.BytesIO(encrypted), BACKUP_FILENAME, "backup")
            bot.pinned = uploaded

            target_layout = make_layout(Path(temp) / "target")
            local_session = target_layout.auth_dir / "mtproto_bot.session"
            local_session.write_bytes(b"keep-local-session")
            restored, restored_bytes = await restore_from_telegram(
                bot, TOKEN, "-100123", target_layout
            )

            self.assertEqual(restored, 3)
            self.assertEqual(restored_bytes, len(b'{"restored":true}') + len(b'{}') * 2)
            self.assertEqual(local_session.read_bytes(), b"keep-local-session")
            self.assertEqual(
                target_layout.state_files["user_plans.json"].read_text(encoding="utf-8"),
                '{"restored":true}',
            )

    async def test_restore_accepts_pinned_backup_document_without_filename_or_caption(self):
        with tempfile.TemporaryDirectory() as temp:
            source_layout = make_layout(Path(temp) / "source")
            source_layout.state_files["user_plans.json"].write_text('{"restored":true}', encoding="utf-8")
            encrypted, _snapshot, _size, _count = build_encrypted_backup(source_layout, TOKEN)
            bot = FakeTelegramBackupBot()
            uploaded = await bot.send_document(-100123, io.BytesIO(encrypted), BACKUP_FILENAME, "backup")
            bot.pinned = SimpleNamespace(
                message_id=uploaded.message_id,
                document=SimpleNamespace(file_id=uploaded.document.file_id),
                caption=None,
            )
            target_layout = make_layout(Path(temp) / "target")

            restored, _restored_bytes = await restore_from_telegram(
                bot, TOKEN, "-100123", target_layout
            )

            self.assertGreater(restored, 0)
            self.assertEqual(
                target_layout.state_files["user_plans.json"].read_text(encoding="utf-8"),
                '{"restored":true}',
            )

    async def test_new_backup_is_not_reported_successful_when_telegram_cannot_pin_it(self):
        class CannotPinBot(FakeTelegramBackupBot):
            async def pin_chat_message(self, **_kwargs):
                raise PermissionError("bot has no pin permission")

        with tempfile.TemporaryDirectory() as temp:
            layout = make_layout(Path(temp))
            layout.state_files["user_plans.json"].write_text("{}", encoding="utf-8")
            bot = CannotPinBot()

            with self.assertRaisesRegex(BackupError, "no permitió fijar"):
                await backup_to_telegram(bot, TOKEN, "-100123", layout)

            self.assertEqual(len(bot.sent), 1)
            self.assertIsNone(bot.pinned)
            self.assertFalse((layout.data_dir / ".telegram_backup_message_id").exists())

    def test_empty_auth_folder_is_valid_if_application_state_exists(self):
        with tempfile.TemporaryDirectory() as temp:
            layout = make_layout(Path(temp))
            layout.state_files["user_plans.json"].write_text("{}", encoding="utf-8")

            snapshot, total_bytes, file_count = build_snapshot(layout)

            self.assertEqual(file_count, 3)
            self.assertEqual(total_bytes, 6)
            self.assertEqual(snapshot["files"][0]["path"], "state/user_plans.json")

    def test_snapshot_skips_symlinks_and_old_backup_directories(self):
        with tempfile.TemporaryDirectory() as temp:
            layout = make_layout(Path(temp))
            layout.state_files["user_plans.json"].write_text("{}", encoding="utf-8")
            (layout.auth_dir / "nested").mkdir()
            (layout.auth_dir / "nested" / "session.db").write_bytes(b"session")
            old_backup = layout.auth_dir / "backups"
            old_backup.mkdir()
            (old_backup / "old.session").write_bytes(b"old")
            outside = Path(temp) / "outside.session"
            outside.write_bytes(b"linked")
            try:
                (layout.auth_dir / "linked.session").symlink_to(outside)
                (layout.auth_dir / "linked_dir").symlink_to(old_backup, target_is_directory=True)
            except OSError:
                self.skipTest("El entorno no permite crear symlinks")

            snapshot, _total_bytes, _file_count = build_snapshot(layout)

            paths = {entry["path"] for entry in snapshot["files"]}
            self.assertEqual(paths, {
                "state/user_plans.json",
                "state/daily_usage.json",
                "state/task_history.json",
                "telegram_auth/nested/session.db",
            })

    async def test_rejects_incomplete_json_state_without_replacing_a_copy(self):
        with tempfile.TemporaryDirectory() as temp:
            layout = make_layout(Path(temp))
            state_file = layout.state_files["user_plans.json"]
            state_file.write_text("{}", encoding="utf-8")
            bot = FakeTelegramBackupBot()
            await backup_to_telegram(bot, TOKEN, "-100123", layout)
            old_pinned = bot.pinned
            sent_count = len(bot.sent)
            state_file.write_text('{"broken":', encoding="utf-8")

            with self.assertRaisesRegex(BackupFormatError, "incompleto"):
                await backup_to_telegram(bot, TOKEN, "-100123", layout)
            self.assertEqual(bot.pinned.message_id, old_pinned.message_id)
            self.assertEqual(len(bot.sent), sent_count)

    def test_rejects_incomplete_authenticated_manifest(self):
        malformed = {"format": FORMAT_NAME, "version": FORMAT_VERSION}
        nonce = b"n" * NONCE_BYTES
        compressed = gzip.compress(json.dumps(malformed).encode("utf-8"), mtime=0)
        encrypted = MAGIC + nonce + AESGCM(derive_backup_key(TOKEN)).encrypt(nonce, compressed, MAGIC)
        with self.assertRaisesRegex(BackupFormatError, "incompleta"):
            decrypt_snapshot(encrypted, TOKEN)

    async def test_size_limit_reports_actual_size_and_leaves_previous_pin_intact(self):
        with tempfile.TemporaryDirectory() as temp:
            layout = make_layout(Path(temp))
            state_file = layout.state_files["user_plans.json"]
            state_file.write_text("{}", encoding="utf-8")
            bot = FakeTelegramBackupBot()
            await backup_to_telegram(bot, TOKEN, "-100123", layout)
            old_pinned = bot.pinned
            old_sent_count = len(bot.sent)

            state_file.write_text(json.dumps({"x": "x" * 512}), encoding="utf-8")
            with self.assertRaises(BackupSizeError) as raised:
                await backup_to_telegram(
                    bot, TOKEN, "-100123", layout, max_total_bytes=64
                )

            self.assertIn("bytes", str(raised.exception))
            self.assertEqual(bot.pinned.message_id, old_pinned.message_id)
            self.assertEqual(len(bot.sent), old_sent_count)


class RestoreBeforeMtprotoTests(unittest.IsolatedAsyncioTestCase):
    async def test_post_init_restores_before_constructing_or_starting_mtproto(self):
        with tempfile.TemporaryDirectory() as temp:
            layout = make_layout(Path(temp))
            events = []

            async def fake_restore(*_args, **_kwargs):
                events.append("restore")
                return 0, 0, False

            async def fake_backup(*_args, **_kwargs):
                events.append("backup")
                return True

            async def idle_backup(*_args, **_kwargs):
                await asyncio.sleep(3600)

            class FakeClient:
                async def start(self, **_kwargs):
                    events.append("start_mtproto")

                def add_event_handler(self, *_args):
                    pass

                async def catch_up(self):
                    pass

                def is_connected(self):
                    return False

            def construct_client(*_args):
                events.append("construct_mtproto")
                return FakeClient()

            fake_bot = SimpleNamespace(
                token=TOKEN,
                get_me=AsyncMock(return_value=SimpleNamespace(username="test_bot")),
                set_my_commands=AsyncMock(),
            )
            app = SimpleNamespace(bot=fake_bot, bot_data={"task_queue": None})
            with patch.dict(
                "os.environ",
                {
                    "TELEGRAM_BACKUP_CHAT_ID": "-100123",
                    "TELEGRAM_API_ID": "12345",
                    "TELEGRAM_API_HASH": "fake-hash",
                },
            ), patch.object(telegram_bot, "telegram_backup_layout", return_value=layout), patch.object(
                telegram_bot, "TELEGRAM_AUTH_DIR", layout.auth_dir
            ), patch.object(telegram_bot, "restore_from_telegram", side_effect=fake_restore), patch.object(
                telegram_bot, "backup_to_telegram", side_effect=fake_backup
            ), patch.object(telegram_bot, "periodic_backup", side_effect=idle_backup), patch.object(
                telegram_bot, "TelegramClient", side_effect=construct_client
            ):
                await telegram_bot.post_init(app)
                self.assertLess(events.index("restore"), events.index("construct_mtproto"))
                self.assertLess(events.index("restore"), events.index("start_mtproto"))
                self.assertLess(events.index("start_mtproto"), len(events) - 1)
                self.assertEqual(events[-1], "backup")
                await telegram_bot.post_shutdown(app)

    async def test_post_init_preserves_existing_backup_when_restore_writes_nothing(self):
        with tempfile.TemporaryDirectory() as temp:
            layout = make_layout(Path(temp))
            events = []

            async def fake_restore(*_args, **_kwargs):
                events.append("restore")
                return 0, 0, True

            async def fake_backup(*_args, **_kwargs):
                events.append("backup")
                return True

            async def idle_backup(*_args, **_kwargs):
                await asyncio.sleep(3600)

            class FakeClient:
                async def start(self, **_kwargs):
                    pass

                def add_event_handler(self, *_args):
                    pass

                async def catch_up(self):
                    pass

                def is_connected(self):
                    return False

            fake_bot = SimpleNamespace(
                token=TOKEN,
                get_me=AsyncMock(return_value=SimpleNamespace(username="test_bot")),
                set_my_commands=AsyncMock(),
            )
            app = SimpleNamespace(bot=fake_bot, bot_data={"task_queue": None})
            with patch.dict(
                "os.environ",
                {
                    "TELEGRAM_BACKUP_CHAT_ID": "-100123",
                    "TELEGRAM_API_ID": "12345",
                    "TELEGRAM_API_HASH": "fake-hash",
                },
            ), patch.object(telegram_bot, "telegram_backup_layout", return_value=layout), patch.object(
                telegram_bot, "TELEGRAM_AUTH_DIR", layout.auth_dir
            ), patch.object(telegram_bot, "restore_from_telegram", side_effect=fake_restore), patch.object(
                telegram_bot, "backup_to_telegram", side_effect=fake_backup
            ), patch.object(telegram_bot, "periodic_backup", side_effect=idle_backup), patch.object(
                telegram_bot, "TelegramClient", return_value=FakeClient()
            ):
                await telegram_bot.post_init(app)
                self.assertTrue(app.bot_data["telegram_backup_hold"])
                self.assertEqual(events, ["restore"])
                await telegram_bot.post_shutdown(app)


if __name__ == "__main__":
    unittest.main()
