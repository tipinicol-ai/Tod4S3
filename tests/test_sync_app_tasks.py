import asyncio
import http.client
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import bot
import web_store
from task_queue import FairTaskQueue, TransferTask


class SyncAppTaskHttpTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), web_store.StoreHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_address[1]
        self.addCleanup(self._stop_server)

    def _stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        web_store.configure_app_task_bridge(None)

    def _request(self, method, path, body=None, token="app-token"):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        headers = {"Authorization": f"Bearer {token}"}
        encoded = None
        if body is not None:
            encoded = json.dumps(body)
            headers["Content-Type"] = "application/json"
        connection.request(method, path, body=encoded, headers=headers)
        response = connection.getresponse()
        data = json.loads(response.read().decode("utf-8"))
        status = response.status
        connection.close()
        return status, data

    def test_tasks_endpoint_rejects_missing_or_invalid_token(self):
        with patch.object(web_store.sync_api, "user_id_for_token", return_value=None):
            status, data = self._request("GET", "/api/app/tasks", token="bad-token")
        self.assertEqual(status, 401)
        self.assertEqual(data["error"], "unauthorized")

    def test_download_endpoint_uses_owner_from_bearer_and_forwards_one_url(self):
        received = []

        def bridge(action, user_id, payload):
            received.append((action, user_id, payload))
            return {"ok": True, "task_id": "task123", "state": "queued"}, 202

        web_store.configure_app_task_bridge(bridge)
        with patch.object(web_store.sync_api, "user_id_for_token", return_value=735):
            status, data = self._request(
                "POST", "/api/app/download", {"url": " https://youtu.be/dQw4w9WgXcQ "}
            )
        self.assertEqual(status, 202)
        self.assertEqual(data["task_id"], "task123")
        self.assertEqual(received, [("enqueue", 735, {"url": "https://youtu.be/dQw4w9WgXcQ"})])

    def test_tasks_and_cancel_are_dispatched_through_authenticated_routes(self):
        received = []

        def bridge(action, user_id, payload):
            received.append((action, user_id, payload))
            if action == "list":
                return {"ok": True, "tasks": []}, 200
            return {"ok": True}, 200

        web_store.configure_app_task_bridge(bridge)
        with patch.object(web_store.sync_api, "user_id_for_token", return_value=735):
            status, _ = self._request("GET", "/api/app/tasks")
            self.assertEqual(status, 200)
            status, _ = self._request("POST", "/api/app/tasks/cancel", {"task_id": "task123"})
        self.assertEqual(status, 200)
        self.assertEqual(received, [
            ("list", 735, {}),
            ("cancel", 735, {"task_id": "task123"}),
        ])

    def test_download_rejects_malformed_or_overlong_url_before_dispatch(self):
        called = []
        web_store.configure_app_task_bridge(lambda *args: called.append(args) or ({"ok": True}, 202))
        with patch.object(web_store.sync_api, "user_id_for_token", return_value=735):
            for url in ("", "   ", "x" * 2049, "https://bad\nurl.example/"):
                with self.subTest(url=url[:30]):
                    status, data = self._request("POST", "/api/app/download", {"url": url})
                    self.assertEqual(status, 400)
                    self.assertEqual(data["error"], "invalid_url")
        self.assertEqual(called, [])


class SyncAppTaskWorkerTests(unittest.TestCase):
    def _application(self):
        status_message = SimpleNamespace(edit_text=AsyncMock(), delete=AsyncMock())
        bot_api = SimpleNamespace(send_message=AsyncMock(return_value=status_message))
        application = SimpleNamespace(bot=bot_api, bot_data={"task_queue": FairTaskQueue()})
        return application, status_message

    def test_app_link_enqueues_url_as_todus_task_without_telegram_source_message(self):
        application, _ = self._application()
        with (
            patch.object(bot, "get_user_todus_number", return_value="+5350000000"),
            patch.object(bot, "get_user_queue_limit", return_value=2),
            patch.object(bot, "ensure_task_workers"),
            patch.object(bot, "_edit_task_status", new=AsyncMock()),
        ):
            result, status = asyncio.run(bot.sync_app_task_request(
                application, "enqueue", 735, {"url": "https://youtu.be/dQw4w9WgXcQ"}
            ))
        self.assertEqual(status, 202)
        self.assertTrue(result["ok"])
        jobs = application.bot_data["task_queue"].for_user(735)
        self.assertEqual(len(jobs), 1)
        self.assertEqual(jobs[0].source_kind, "url")
        self.assertIsNone(jobs[0].source_message)
        self.assertEqual(jobs[0].source_url, "https://youtu.be/dQw4w9WgXcQ")
        self.assertEqual(jobs[0].delivery_mode, "todus")
        self.assertEqual(jobs[0].submission_source, "android_app")

    def test_invalid_url_and_missing_todus_number_do_not_enqueue(self):
        application, _ = self._application()
        with patch.object(bot, "get_user_todus_number", return_value="+5350000000"):
            result, status = asyncio.run(bot.sync_app_task_request(
                application, "enqueue", 735, {"url": "http://127.0.0.1/private"}
            ))
        self.assertEqual(status, 400)
        self.assertEqual(result["error"], "invalid_url")
        self.assertEqual(application.bot_data["task_queue"].for_user(735), [])

        with patch.object(bot, "get_user_todus_number", return_value=""):
            result, status = asyncio.run(bot.sync_app_task_request(
                application, "enqueue", 735, {"url": "https://youtu.be/dQw4w9WgXcQ"}
            ))
        self.assertEqual(status, 409)
        self.assertEqual(result["error"], "todus_number_required")
        self.assertEqual(application.bot_data["task_queue"].for_user(735), [])

    def test_list_only_returns_tasks_created_by_android_app(self):
        application, status_message = self._application()
        manager = application.bot_data["task_queue"]
        android_job = TransferTask(
            user_id=735, chat_id=735, source_kind="url", source_message=None,
            status_message=status_message, source_url="https://youtu.be/dQw4w9WgXcQ",
            submission_source="android_app",
        )
        telegram_job = TransferTask(
            user_id=735, chat_id=735, source_kind="url", source_message=object(),
            status_message=status_message, source_url="https://youtu.be/other",
        )
        manager.jobs[android_job.task_id] = android_job
        manager.jobs[telegram_job.task_id] = telegram_job
        result, status = asyncio.run(bot.sync_app_task_request(application, "list", 735, {}))
        self.assertEqual(status, 200)
        self.assertEqual([row["task_id"] for row in result["tasks"]], [android_job.task_id])

    def test_android_cancel_cannot_cancel_a_telegram_origin_task(self):
        application, status_message = self._application()
        manager = application.bot_data["task_queue"]
        telegram_job = TransferTask(
            user_id=735, chat_id=735, source_kind="url", source_message=object(),
            status_message=status_message, source_url="https://youtu.be/other",
        )
        manager.jobs[telegram_job.task_id] = telegram_job
        result, status = asyncio.run(bot.sync_app_task_request(
            application, "cancel", 735, {"task_id": telegram_job.task_id}
        ))
        self.assertEqual(status, 404)
        self.assertEqual(result["error"], "task_not_found")
        self.assertEqual(telegram_job.state, "queued")
