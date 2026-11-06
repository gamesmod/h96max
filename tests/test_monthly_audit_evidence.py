"""Проверки свежести ежемесячного аудита журналов и сетевого трафика."""
import importlib.util
import datetime
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "monthly_audit_evidence", ROOT / "tools/check-monthly-audit-evidence.py")
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class MonthlyAuditEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.data = {
            "audit_date": "2026-11-06",
            "logs_reviewed": True,
            "network_capture_reviewed": True,
            "capture_duration_seconds": 15,
            "captured_packets": 5000,
            "raw_capture_retained": False,
            "sanitized_summary": "HTTP проверен, PCAP удалён",
        }

    def test_recent_log_and_network_review_passes(self):
        self.assertEqual(AUDIT.validate(self.data, today=datetime.date(2026, 11, 6)), [])

    def test_stale_or_missing_network_review_fails(self):
        self.data["audit_date"] = "2026-09-01"
        self.data["network_capture_reviewed"] = False
        errors = AUDIT.validate(self.data, today=datetime.date(2026, 11, 6))
        self.assertTrue(any("старше" in error for error in errors))
        self.assertTrue(any("сетевого захвата" in error for error in errors))
        self.assertTrue(AUDIT.validate([], today=datetime.date(2026, 11, 6)))

    def test_raw_capture_must_not_be_retained(self):
        self.data["raw_capture_retained"] = True
        self.assertTrue(any("сырой PCAP" in error for error in AUDIT.validate(
            self.data, today=datetime.date(2026, 11, 6))))

    def test_monthly_workflow_is_scheduled(self):
        workflow = (ROOT / ".github/workflows/monthly-security-audit.yml").read_text(encoding="utf-8")
        self.assertIn("schedule:", workflow)
        self.assertIn("cron: '17 8 1 * *'", workflow)
        self.assertIn("--max-age-days 31", workflow)


if __name__ == "__main__":
    unittest.main()
