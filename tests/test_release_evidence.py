"""Проверки разделения аппаратных gate для dev и stable выпусков."""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
CHECKER = ROOT / "tools/check-release-evidence.py"


class ReleaseChannelEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.evidence = pathlib.Path(self.tmp.name) / "evidence.json"
        self.data = {
            "version": "9.9.9",
            "device": "test device",
            "automated_tests": {"status": "pass", "count": 1},
            "cold_boot_seconds": 8,
            "checks": {
                name: "pass" for name in (
                    "cold_boot_le_10s", "boot_log_review", "qr_vlc",
                    "wired_network", "wifi_network", "iptv_playback", "torrent_playback",
                    "graphics_video", "graphics_stress", "swap_disabled",
                )
            },
            "log_analysis": {"reviewed": True, "untriaged_errors": 0, "summary": "test"},
        }

    def run_checker(self, channel):
        self.evidence.write_text(json.dumps(self.data), encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(CHECKER), "--file", str(self.evidence), "--version", "9.9.9",
             "--channel", channel], capture_output=True, text=True, check=False,
        )

    def test_dev_gate_does_not_block_on_security_review(self):
        self.data["checks"]["security_review"] = "fail"
        result = self.run_checker("dev")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("(dev)", result.stdout)
        self.assertIn("security_review не блокирует", result.stdout)

    def test_stable_gate_still_requires_security_review(self):
        self.data["checks"]["security_review"] = "fail"
        result = self.run_checker("stable")
        self.assertEqual(result.returncode, 1)
        self.assertIn("security_review", result.stderr)


if __name__ == "__main__":
    unittest.main()
