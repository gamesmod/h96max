"""Регрессионные проверки сборки IPTV-плейлиста Panadonik."""
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("iptv_merge", ROOT / "tools/iptv-merge.py")
MERGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MERGE)
LOG_SPEC = importlib.util.spec_from_file_location("release_log_analysis", ROOT / "tools/analyze-release-logs.py")
LOG_ANALYSIS = importlib.util.module_from_spec(LOG_SPEC)
LOG_SPEC.loader.exec_module(LOG_ANALYSIS)
SECURITY_SPEC = importlib.util.spec_from_file_location("public_release_check", ROOT / "tools/check-public-release.py")
PUBLIC_CHECK = importlib.util.module_from_spec(SECURITY_SPEC)
SECURITY_SPEC.loader.exec_module(PUBLIC_CHECK)


class IptvCategoryTests(unittest.TestCase):
    def test_multilingual_topic_names_share_one_russian_category(self):
        pairs = (
            ("Sports", "Спорт", "спорт"),
            ("World News", "Новости", "Новости"),
            ("Movies", "Фильмы", "Кино"),
            ("Kids Cartoons", "Детские", "Мультфильмы"),
            ("Music", "Музыка", "Музыкальные"),
            ("Adult", "Для взрослых", "18+"),
        )
        for english, expected, russian in pairs:
            with self.subTest(english=english):
                self.assertEqual(MERGE.normalize_group(english), expected)
                self.assertEqual(MERGE.normalize_group(russian), expected)

    def test_country_and_region_categories_have_flag_and_russian_name(self):
        samples = {
            "Russia / Moscow Oblast": "🇷🇺 Россия · Московская область",
            "США - California": "🇺🇸 США · Калифорния",
            "Canada Ontario": "🇨🇦 Канада · Онтарио",
            "Germany": "🇩🇪 Германия",
        }
        for source, expected in samples.items():
            with self.subTest(source=source):
                self.assertEqual(MERGE.normalize_group(source), expected)

    def test_adult_category_sorts_before_every_other_group(self):
        entries = [
            {"attrs": {"group-title": "Новости"}, "name": "Канал новостей"},
            {"attrs": {"group-title": "Для взрослых"}, "name": "Канал 18+"},
            {"attrs": {"group-title": "Детские"}, "name": "Мультфильм"},
        ]
        entries.sort(key=MERGE.playlist_sort_key)
        self.assertEqual([entry["attrs"]["group-title"] for entry in entries],
                         ["Для взрослых", "Детские", "Новости"])


class IptvDuplicateTests(unittest.TestCase):
    def test_quality_variants_collapse_to_one_channel(self):
        def entry(name, url):
            return {"name": name, "url": url, "attrs": {"tvg-id": "news.id"}, "opts": []}

        unique = MERGE.dedupe([entry("Пример HD", "https://one.invalid/a"),
                               entry("Пример 1080p", "https://two.invalid/b")])
        self.assertEqual(len(unique), 1)
        self.assertEqual(unique[0]["url"], "https://one.invalid/a")


class ReleaseLogAnalysisTests(unittest.TestCase):
    def test_severe_runtime_errors_are_counted(self):
        counts, findings = LOG_ANALYSIS.analyze([
            "W/Activity: slow start",
            "E/AndroidRuntime: ForegroundServiceDidNotStartInTimeException: startup timeout",
            "build: warning: deprecated option",
        ])
        self.assertEqual(counts, {"critical": 1, "error": 0, "warning": 2})
        self.assertEqual(findings[1][0], "Критично")

    def test_report_sanitizer_hides_urls_addresses_and_secrets(self):
        text = LOG_ANALYSIS.sanitize(
            "failed https://example.invalid/live?token=private 192.168.1.55 password=secret")
        self.assertNotIn("example.invalid", text)
        self.assertNotIn("192.168.1.55", text)
        self.assertNotIn("private", text)
        self.assertNotIn("secret", text)


class PublicReleaseSecurityTests(unittest.TestCase):
    def test_urls_with_credentials_or_sensitive_query_keys_are_rejected(self):
        self.assertTrue(PUBLIC_CHECK.safe_url("https://example.invalid/live.m3u"))
        self.assertFalse(PUBLIC_CHECK.safe_url("https://user:pass@example.invalid/live"))
        self.assertFalse(PUBLIC_CHECK.safe_url("https://example.invalid/live?token=secret"))


if __name__ == "__main__":
    unittest.main()
