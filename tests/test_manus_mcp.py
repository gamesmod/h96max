"""Проверка MCP-сервера tools/manus-mcp/server.mjs на локальном имитаторе Manus API (без сети и ключей)."""
import json
import os
import shutil
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "tools" / "manus-mcp" / "server.mjs"
CALLS = []


class Fake(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, obj, code=200):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        CALLS.append((self.path, self.headers.get("x-manus-api-key"), body))
        if self.path == "/v2/task.create":
            self._send({"ok": True, "task_id": "task12345678", "task_url": "https://manus.im/app/task12345678"})
        else:
            self._send({"ok": True, "task_id": body.get("task_id")})

    def do_GET(self):
        CALLS.append((self.path, self.headers.get("x-manus-api-key"), None))
        if self.path.startswith("/v2/task.detail"):
            self._send({"ok": True, "task": {"id": "task12345678", "status": "stopped", "has_running_background_jobs": False, "credit_usage": 7, "task_url": "u"}})
        else:
            self._send({"ok": True, "messages": [
                {"id": "3", "type": "assistant_message", "assistant_message": {"content": "Готово", "attachments": [{"type": "file", "filename": "r.pdf", "url": "https://x/r.pdf"}]}},
                {"id": "2", "type": "status_update", "status_update": {"agent_status": "running"}},
                {"id": "1", "type": "user_message", "user_message": {"content": "q"}},
            ], "has_more": False})


@unittest.skipUnless(shutil.which("node"), "node не установлен")
class ManusMcpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = HTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def rpc(self, messages, key="test-key"):
        env = {**os.environ, "MANUS_API_BASE": f"http://127.0.0.1:{self.httpd.server_port}"}
        env.pop("MANUS_API_KEY", None)
        if key:
            env["MANUS_API_KEY"] = key
        inp = "".join(json.dumps(m) + "\n" for m in messages)
        p = subprocess.run(["node", str(SERVER)], input=inp, capture_output=True, text=True, env=env, timeout=30)
        self.assertEqual(p.returncode, 0, p.stderr)
        return {r["id"]: r for r in map(json.loads, p.stdout.splitlines())}

    def call(self, name, args, **kw):
        out = self.rpc([{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": name, "arguments": args}}], **kw)[1]
        return out["result"]

    def test_tools_list(self):
        r = self.rpc([{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26"}},
                      {"jsonrpc": "2.0", "method": "notifications/initialized"},
                      {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}])
        self.assertEqual(r[1]["result"]["protocolVersion"], "2025-03-26")
        self.assertEqual({t["name"] for t in r[2]["result"]["tools"]}, {"manus_delegate", "manus_status", "manus_reply", "manus_stop"})

    def test_delegate_and_status(self):
        CALLS.clear()
        res = self.call("manus_delegate", {"prompt": "Сравни два сервиса и верни таблицу с источниками.", "profile": "lite"})
        self.assertNotIn("isError", res)
        self.assertEqual(json.loads(res["content"][0]["text"])["task_id"], "task12345678")
        path, key, body = CALLS[0]
        self.assertEqual((path, key, body["agent_profile"], body["share_visibility"]), ("/v2/task.create", "test-key", "lite", "private"))
        st = json.loads(self.call("manus_status", {"task_id": "task12345678"})["content"][0]["text"])
        self.assertEqual(st["state"], "done")
        self.assertEqual(st["result"], "Готово")
        self.assertEqual(st["attachments"][0]["filename"], "r.pdf")

    def test_secret_is_refused_before_network(self):
        CALLS.clear()
        res = self.call("manus_delegate", {"prompt": "Вот ключ -----BEGIN PRIVATE KEY----- MIIE... сделай что-нибудь"})
        self.assertTrue(res.get("isError"))
        self.assertEqual(CALLS, [])

    def test_missing_key(self):
        res = self.call("manus_delegate", {"prompt": "Достаточно длинная самодостаточная задача."}, key=None)
        self.assertTrue(res.get("isError"))
        self.assertIn("MANUS_API_KEY", res["content"][0]["text"])


if __name__ == "__main__":
    unittest.main()
