"""Проверки разделения аппаратных gate для dev и stable выпусков."""
import json
import datetime
import hashlib
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
CHECKER = ROOT / "tools/check-release-evidence.py"
IPTV_CHECKER_SPEC = importlib.util.spec_from_file_location(
    "check_public_release", ROOT / "tools/check-public-release.py"
)
IPTV_CHECKER = importlib.util.module_from_spec(IPTV_CHECKER_SPEC)
IPTV_CHECKER_SPEC.loader.exec_module(IPTV_CHECKER)
CANDIDATE_SPEC = importlib.util.spec_from_file_location("candidate_run", ROOT / "tools/check-candidate-run.py")
CANDIDATE_CHECKER = importlib.util.module_from_spec(CANDIDATE_SPEC)
CANDIDATE_SPEC.loader.exec_module(CANDIDATE_CHECKER)


class ReleaseChannelEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.evidence = pathlib.Path(self.tmp.name) / "evidence.json"
        self.ota = pathlib.Path(self.tmp.name) / "candidate.zip"
        self.ota.write_bytes(b"tested OTA")
        self.data = {
            "version": "9.9.9",
            "device": "test device",
            "candidate": {"ota_sha256": hashlib.sha256(b"tested OTA").hexdigest()},
            "automated_tests": {"status": "pass", "count": 1},
            "security_analysis": {"reviewed": True, "summary": "Анализ выполнен; риск раскрыт."},
            "hot_test": {"network_capture": {
                "captured_at": datetime.date.today().isoformat(),
                "duration_seconds": 15,
                "packets": 100,
                "auth_cookie_or_token_patterns": 0,
                "raw_capture_retained": False,
            }},
            "checks": {
                name: "pass" for name in (
                    "cold_boot_ready", "boot_log_review", "qr_vlc",
                    "wired_network", "wifi_network", "iptv_playback", "torrent_playback",
                    "graphics_video", "graphics_stress", "swap_disabled", "network_capture_review",
                    "usb_remote_iptv_channels",
                )
            },
            "log_analysis": {"reviewed": True, "untriaged_errors": 0, "summary": "test"},
        }

    def run_checker(self, channel):
        self.evidence.write_text(json.dumps(self.data), encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(CHECKER), "--file", str(self.evidence), "--version", "9.9.9",
             "--channel", channel, "--ota", str(self.ota)], capture_output=True, text=True, check=False,
        )

    def test_dev_gate_does_not_block_on_security_review(self):
        self.data["checks"]["security_review"] = "fail"
        result = self.run_checker("dev")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("(dev)", result.stdout)
        self.assertIn("security_review не блокирует", result.stdout)

    def test_rejects_rebuilt_or_missing_ota(self):
        self.ota.write_bytes(b"different OTA")
        result = self.run_checker("dev")
        self.assertEqual(result.returncode, 1)
        self.assertIn("SHA-256 OTA", result.stderr)
        self.ota.unlink()
        result = self.run_checker("dev")
        self.assertEqual(result.returncode, 1)
        self.assertIn("не удалось прочитать OTA", result.stderr)

    def test_candidate_run_requires_successful_matching_source_and_allowed_event(self):
        sha = "a" * 40
        run = {"head_sha": sha, "status": "completed", "conclusion": "success",
               "path": ".github/workflows/release.yml", "event": "push", "head_branch": "candidate/test"}
        self.assertTrue(CANDIDATE_CHECKER.valid_candidate(run, sha))
        for field, value in (("head_sha", "b" * 40), ("conclusion", "failure"),
                             ("status", "in_progress"), ("path", ".github/workflows/ci.yml"),
                             ("event", "pull_request"), ("head_branch", "master")):
            with self.subTest(field=field):
                altered = dict(run, **{field: value})
                self.assertFalse(CANDIDATE_CHECKER.valid_candidate(altered, sha))
        for event in ("schedule", "workflow_dispatch"):
            self.assertTrue(CANDIDATE_CHECKER.valid_candidate(dict(run, event=event), sha))

    def test_dev_requires_security_analysis_even_when_risk_is_allowed(self):
        self.data["checks"]["security_review"] = "fail"
        for analysis in ({"reviewed": False, "summary": "risk"},
                         {"reviewed": True, "summary": "  "}, {}):
            with self.subTest(analysis=analysis):
                self.data["security_analysis"] = analysis
                result = self.run_checker("dev")
                self.assertEqual(result.returncode, 1)
                self.assertIn("анализ безопасности", result.stderr)

    def test_stable_gate_still_requires_security_review(self):
        self.data["checks"]["security_review"] = "fail"
        result = self.run_checker("stable")
        self.assertEqual(result.returncode, 1)
        self.assertIn("security_review", result.stderr)

    def test_dev_gate_requires_usb_remote_channel_switching(self):
        self.data["checks"]["usb_remote_iptv_channels"] = "pending"
        result = self.run_checker("dev")
        self.assertEqual(result.returncode, 1)
        self.assertIn("usb_remote_iptv_channels", result.stderr)

    def test_dev_gate_requires_network_capture_review(self):
        self.data["checks"]["network_capture_review"] = "pending"
        result = self.run_checker("dev")
        self.assertEqual(result.returncode, 1)
        self.assertIn("network_capture_review", result.stderr)

    def test_network_capture_pass_requires_fresh_details_and_deleted_pcap(self):
        capture = self.data["hot_test"]["network_capture"]
        capture["captured_at"] = (datetime.date.today() - datetime.timedelta(days=32)).isoformat()
        capture["raw_capture_retained"] = True
        result = self.run_checker("dev")
        self.assertEqual(result.returncode, 1)
        self.assertIn("не старше 31 дня", result.stderr)
        self.assertIn("сырой PCAP", result.stderr)

    def test_boot_duration_does_not_block_release(self):
        self.data["cold_boot_seconds"] = 50
        self.assertEqual(self.run_checker("dev").returncode, 0)

    def test_cold_boot_readiness_is_required(self):
        self.data["checks"]["cold_boot_ready"] = "pending"
        result = self.run_checker("dev")
        self.assertEqual(result.returncode, 1)
        self.assertIn("cold_boot_ready", result.stderr)

    def test_network_capture_counts_must_be_valid_integers(self):
        capture = self.data["hot_test"]["network_capture"]
        for field, value in (("duration_seconds", True), ("packets", True),
                             ("auth_cookie_or_token_patterns", False),
                             ("auth_cookie_or_token_patterns", -1)):
            with self.subTest(field=field, value=value):
                old = capture[field]
                capture[field] = value
                self.assertEqual(self.run_checker("dev").returncode, 1)
                capture[field] = old

    def test_local_release_builder_refreshes_metadata_and_obtainium_apks(self):
        script = (ROOT / "tools/build-release.sh").read_text(encoding="utf-8")
        self.assertIn('cp release/updates/emmc/latest.txt release/updates/emmc/latest-emmc.txt', script)
        self.assertIn('sha256sum "tvbox-update-$V-emmc.zip" latest-emmc.txt > SHA256SUMS', script)
        self.assertIn('cp apps/iptv/out/TvboxIptv.apk release/obtainium/Panadonik-iptv.apk', script)

    def test_github_release_uses_committed_version_notes(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn('Using committed release notes', workflow)
        self.assertIn('release/notes/$version.txt', workflow)

    def test_update_notes_keep_multiline_text_over_line_protocol(self):
        daemon = (ROOT / "apps/tvboxd/src/org/tvbox/daemon/Main.java").read_text(encoding="utf-8")
        installer = (ROOT / "apps/installer/src/org/tvbox/installer/MainActivity.java").read_text(encoding="utf-8")
        self.assertIn('"NOTES64 url:"', daemon)
        self.assertIn('Base64.encodeToString(m.get("notes").getBytes("UTF-8")', daemon)
        self.assertIn('k.equals("NOTES64")', installer)
        self.assertIn('Base64.decode(p[1]', installer)

    def test_long_update_description_keeps_cleanup_buttons_visible(self):
        installer = (ROOT / "apps/installer/src/org/tvbox/installer/MainActivity.java").read_text(encoding="utf-8")
        self.assertIn('int maxHeight = Math.min(dp(560)', installer)
        self.assertIn('content.setPadding(dp(24), dp(4), dp(24), dp(4))', installer)
        self.assertIn('.setView(content)', installer)
        self.assertIn('.setPositiveButton("Продолжить с очисткой"', installer)
        self.assertIn('confirmCmd("Подтвердить очистку и обновление?"', installer)

    def test_dev_candidate_allows_only_adult_inventory_gap(self):
        issues = [
            "категория «Для взрослых» должна быть первой",
            "каналов 18+ 0, требуется минимум 20",
            "найдены детские каналы",
            "есть канал без итоговой метки измеренного качества 1080p или выше",
        ]
        self.assertEqual(
            ["найдены детские каналы", "есть канал без итоговой метки измеренного качества 1080p или выше"],
            IPTV_CHECKER.candidate_blocking_issues(issues),
        )

    def test_candidate_adult_gap_option_never_allows_private_stream_url(self):
        sources = pathlib.Path(self.tmp.name) / "sources.txt"
        playlist = pathlib.Path(self.tmp.name) / "candidate.m3u"
        sources.write_text("", encoding="utf-8")
        playlist.write_text(
            '#EXTM3U\n#EXTINF:-1 group-title="Для взрослых",Тест (1080p)\n'
            'http://user:secret@example.invalid/live.m3u8\n', encoding="utf-8",
        )
        result = subprocess.run(
            [sys.executable, str(ROOT / "tools/check-public-release.py"), "--sources", str(sources),
             "--playlist", str(playlist), "--min-adult", "20", "--allow-adult-gap"],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("URL с учётными данными запрещён", result.stderr)
        self.assertNotIn("secret", result.stderr)

    def test_workflow_discloses_candidate_iptv_gap_and_blocks_tagged_release(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("candidate_args+=(--allow-adult-gap)", workflow)
        self.assertIn("IPTV verification failed; tagged dev/stable publication is blocked.", workflow)
        self.assertIn("release/qa/iptv-validation.md", workflow)

    def test_nightly_candidates_never_publish_and_tag_runs_require_hardware_gate(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("schedule:", workflow)
        self.assertIn("- cron: '20 3 * * *'", workflow)
        self.assertIn("- 'candidate/**'", workflow)
        tag_only = "if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/')"
        self.assertEqual(workflow.count(tag_only), 2)
        self.assertIn("Upload signed build candidate (nightly/manual run)", workflow)
        self.assertIn("github.event_name != 'push' || !startsWith(github.ref, 'refs/tags/')", workflow)
        self.assertIn("retention-days: 3", workflow)
        self.assertIn("Preflight required Actions secrets", workflow)

    def test_repository_ci_runs_on_master_and_analyzes_full_test_log(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("branches: [master]", workflow)
        self.assertIn("python3 -m unittest discover -s tests -v", workflow)
        self.assertIn("--fail-on-errors", workflow)


if __name__ == "__main__":
    unittest.main()
