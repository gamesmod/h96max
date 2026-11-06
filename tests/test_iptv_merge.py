"""Регрессионные проверки сборки IPTV-плейлиста Panadonik."""
import importlib.util
import pathlib
import datetime
import json
import shutil
import subprocess
import unittest
import tempfile
from unittest import mock


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
VERIFY_SPEC = importlib.util.spec_from_file_location("iptv_verify", ROOT / "tools/iptv-verify.py")
IPTV_VERIFY = importlib.util.module_from_spec(VERIFY_SPEC)
VERIFY_SPEC.loader.exec_module(IPTV_VERIFY)


class IptvCategoryTests(unittest.TestCase):
    def test_multilingual_topic_names_share_one_russian_category(self):
        pairs = (
            ("Sports", "Спорт", "спорт"),
            ("World News", "Новости · Мир", "Новости · Мир"),
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
            "Германия | Germany": "🇩🇪 Германия",
            "Украина | Українські": "🇺🇦 Украина",
            "Турция | Türk": "🇹🇷 Турция",
            "Молдавия | Moldovenească": "🇲🇩 Молдова",
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
        entries = [entry for entry in entries if not MERGE.is_child_channel(entry)]
        entries.sort(key=MERGE.playlist_sort_key)
        self.assertEqual([entry["attrs"]["group-title"] for entry in entries],
                         ["Для взрослых", "Новости"])

    def test_child_channels_are_excluded_by_group_or_explicit_name(self):
        entries = [
            {"attrs": {"group-title": "Kids"}, "name": "News"},
            {"attrs": {"group-title": "США"}, "name": "Disney Channel"},
            {"attrs": {"group-title": "Спорт"}, "name": "Матч ТВ"},
            {"attrs": {"group-title": "Для взрослых"}, "name": "Adult TV"},
        ]
        self.assertEqual([e["name"] for e in entries if not MERGE.is_child_channel(e)],
                         ["Матч ТВ", "Adult TV"])

    def test_source_options_keep_spaces_in_group_name(self):
        parsed = MERGE.parse_source_line('https://example.invalid/list.m3u set-group="Для взрослых"')
        self.assertEqual(parsed, ("https://example.invalid/list.m3u", ["set-group=Для взрослых"]))

    def test_sport_variants_and_subcategories_are_russian(self):
        self.assertEqual(MERGE.normalize_group("Sports"), "Спорт")
        self.assertEqual(MERGE.normalize_group("Football"), "Спорт · Футбол")
        self.assertEqual(MERGE.normalize_group("Futbol"), "Спорт · Футбол")
        self.assertEqual(MERGE.normalize_group("Sports", "Football HD"), "Спорт · Футбол")
        self.assertEqual(MERGE.normalize_group("Спорт", "Хоккей 1"), "Спорт · Хоккей")
        self.assertEqual(MERGE.normalize_group("Sports", "Match TV"), "Спорт")
        self.assertEqual(MERGE.normalize_group("Футбол"), "Спорт · Футбол")
        self.assertEqual(MERGE.normalize_group("World News"), "Новости · Мир")
        self.assertEqual(MERGE.normalize_group("General"), "Общие")
        self.assertEqual(MERGE.normalize_group("Undefined"), "Общие")
        self.assertEqual(MERGE.normalize_group("Religious"), "Религиозные")
        self.assertEqual(MERGE.normalize_group("Cooking"), "Кулинария")
        self.assertEqual(MERGE.normalize_group("Auto"), "Авто")
        self.assertEqual(MERGE.normalize_group("", "Match! Football"), "Спорт · Футбол")

    def test_explicit_adult_rating_in_channel_name_overrides_generic_group(self):
        self.assertEqual(MERGE.normalize_group("Entertainment", "VF Юмор 18+"), "Для взрослых")
        self.assertEqual(MERGE.normalize_group("Movies", "Секс в большом городе"), "Фильмы")

    def test_unconfirmed_and_sub_1080_quality_are_rejected(self):
        for label, expected in (("1080p", False), ("FHD", False), ("720p", False), ("", False)):
            entry = {"name": "Channel " + label, "attrs": {}, "url": "https://example.invalid/a", "opts": []}
            self.assertEqual(MERGE.quality_ok(entry), expected)

    def test_only_measured_resolution_controls_quality_gate(self):
        entry = {"name": "Channel (4K)", "attrs": {}, "url": "https://example.invalid/channel-4k-2160p.ts",
                 "opts": [], "verified_resolution": 720}
        self.assertFalse(MERGE.quality_ok(entry))
        entry["verified_resolution"] = 1080
        self.assertTrue(MERGE.quality_ok(entry))

    def test_unknown_segment_resolution_is_not_replaced_by_hls_label(self):
        responses = [
            (200, "application/vnd.apple.mpegurl",
             b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080\nhigh.m3u8\n",
             "https://example.invalid/master.m3u8"),
            (200, "application/vnd.apple.mpegurl", b"#EXTM3U\nsegment.ts\n",
             "https://example.invalid/high.m3u8"),
            (206, "video/mp2t", b"not enough data to identify a frame",
             "https://example.invalid/segment.ts"),
        ]
        with mock.patch.object(MERGE, "fetch", side_effect=responses), \
             mock.patch.object(MERGE, "probe_segment_resolution", return_value=0):
            self.assertEqual(MERGE.stream_resolution("https://example.invalid/master.m3u8"), 0)

    def test_unknown_transport_stream_resolution_is_not_replaced_by_url_label(self):
        with mock.patch.object(MERGE, "fetch", return_value=(
                200, "video/mp2t", b"unrecognized transport stream",
                "https://example.invalid/channel-2160p.ts")), \
             mock.patch.object(MERGE, "probe_segment_resolution", return_value=0):
            self.assertEqual(MERGE.stream_resolution("https://example.invalid/channel-2160p.ts"), 0)

    def test_verified_quality_label_replaces_conflicting_source_label(self):
        entry = {"name": "Channel (720p)", "attrs": {}}
        MERGE.label_verified_quality(entry, 1080)
        self.assertEqual(entry["name"], "Channel (1080p)")

    def test_hls_master_requires_full_hd_variant_and_reports_best_height(self):
        playlist = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080\nhigh.m3u8\n"
        self.assertEqual(MERGE.hls_variant_resolution(playlist), 1080)
        self.assertEqual(MERGE.hls_variant_resolution("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720\nlow.m3u8\n"), 720)

    def test_transport_stream_probe_reports_video_height(self):
        if not shutil.which("ffprobe"):
            self.skipTest("ffprobe не установлен в сборочной среде")
        result = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=size=1920x1080:rate=1",
             "-frames:v", "1", "-c:v", "libx264", "-f", "mpegts", "pipe:1"],
            stdout=subprocess.PIPE, check=True)
        self.assertEqual(MERGE.probe_segment_resolution(result.stdout), 1080)


class IptvDuplicateTests(unittest.TestCase):
    def test_quality_variants_collapse_to_one_channel(self):
        def entry(name, url):
            return {"name": name, "url": url, "attrs": {"tvg-id": "news.id"}, "opts": []}

        unique = MERGE.dedupe([entry("Пример HD", "https://one.invalid/a"),
                               entry("Пример 1080p", "https://two.invalid/b")])
        self.assertEqual(len(unique), 1)
        self.assertEqual(unique[0]["url"], "https://one.invalid/a")

    def test_time_shift_suffixes_collapse_but_brand_plus_is_preserved(self):
        def entry(name, url):
            return {"name": name, "url": url, "attrs": {}, "opts": []}

        self.assertEqual(len(MERGE.dedupe([entry("Первый +1", "https://a.invalid"),
                                           entry("Первый -1", "https://b.invalid")])), 1)
        self.assertEqual(len(MERGE.dedupe([entry("HBO HD BR", "https://a.invalid"),
                                           entry("HBO+ BR", "https://b.invalid")])), 2)


class IptvAvailabilityHistoryTests(unittest.TestCase):
    def test_exclusion_requires_three_spaced_failures_and_success_resets_streak(self):
        base = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
        def event(day, height):
            return {"channel_key": "abc", "scope": "global",
                    "checked_at": (base + datetime.timedelta(days=day)).isoformat(), "stream_height": height}
        self.assertFalse(IPTV_VERIFY.exclusion_status("abc", [event(0, 0), event(1, 0)], "global"))
        self.assertTrue(IPTV_VERIFY.exclusion_status("abc", [event(0, 0), event(1, 0), event(2, 0)], "global"))
        self.assertFalse(IPTV_VERIFY.exclusion_status("abc", [event(0, 0), event(1, 0), event(2, 1080),
                                                               event(3, 0), event(4, 0)], "global"))

    def test_history_scopes_are_separate_and_rating_uses_success_share(self):
        now = datetime.datetime(2026, 1, 3, tzinfo=datetime.timezone.utc)
        events = [
            {"channel_key": "abc", "scope": "global", "checked_at": "2026-01-01T00:00:00+00:00", "stream_height": 1080},
            {"channel_key": "abc", "scope": "global", "checked_at": "2026-01-02T00:00:00+00:00", "stream_height": 0},
            {"channel_key": "abc", "scope": "device", "checked_at": "2026-01-02T00:00:00+00:00", "stream_height": 720},
        ]
        self.assertEqual(IPTV_VERIFY.availability_rating("abc", events, "global", now=now), 50)
        self.assertEqual(IPTV_VERIFY.availability_rating("abc", events, "device", now=now), 100)
        self.assertFalse(IPTV_VERIFY.exclusion_status("abc", events, "device"))

    def test_transient_failure_is_retained_only_with_recent_measured_full_hd(self):
        now = datetime.datetime(2026, 1, 3, tzinfo=datetime.timezone.utc)
        events = [
            {"channel_key": "abc", "scope": "global", "checked_at": "2026-01-02T00:00:00+00:00",
             "stream_height": 1080},
            {"channel_key": "abc", "scope": "global", "checked_at": "2026-01-03T00:00:00+00:00",
             "stream_height": 0},
        ]
        self.assertTrue(IPTV_VERIFY.has_recent_full_hd("abc", events, "global", now=now))
        self.assertFalse(IPTV_VERIFY.has_recent_full_hd("abc", events, "device", now=now))
        self.assertFalse(IPTV_VERIFY.has_recent_full_hd("unknown", events, "global", now=now))
        stale = [{**events[0], "checked_at": "2025-11-01T00:00:00+00:00"}]
        self.assertFalse(IPTV_VERIFY.has_recent_full_hd("abc", stale, "global", now=now))
        downgraded = [
            {**events[0], "checked_at": "2026-01-01T00:00:00+00:00", "stream_height": 1080},
            {**events[0], "checked_at": "2026-01-02T00:00:00+00:00", "stream_height": 720},
            events[1],
        ]
        self.assertFalse(IPTV_VERIFY.has_recent_full_hd("abc", downgraded, "global", now=now))

    def test_history_does_not_store_stream_url(self):
        with tempfile.TemporaryDirectory() as temp:
            path = pathlib.Path(temp) / "history.jsonl"
            event = {"channel_key": "abc", "scope": "global", "checked_at": "2026-01-01T00:00:00+00:00",
                     "stream_height": 1080, "channel": "Новости"}
            IPTV_VERIFY.append_history(path, [event])
            self.assertEqual(IPTV_VERIFY.load_history(path), [event])
            self.assertNotIn("https://", path.read_text())

    def test_identical_stream_url_is_deduplicated_even_with_different_names(self):
        entries = [
            {"name": "Channel A", "url": "https://one.invalid/live", "attrs": {}, "opts": []},
            {"name": "Channel B", "url": "https://one.invalid/live", "attrs": {}, "opts": []},
        ]
        self.assertEqual(len(MERGE.dedupe(entries)), 1)


class ReleaseLogAnalysisTests(unittest.TestCase):
    def test_severe_runtime_errors_are_counted(self):
        counts, findings = LOG_ANALYSIS.analyze([
            "W/Activity: slow start",
            "E/AndroidRuntime: ForegroundServiceDidNotStartInTimeException: startup timeout",
            "build: warning: deprecated option",
            "E/dhd-wlan0: firmware status -54",
            "E/[dhd-wlan0] firmware status -55",
            "E/[dhd-wlan0] wl_run_escan(    0): LEGACY_SCAN sync ID: 93, bssidx: 0",
        ])
        self.assertEqual(counts, {"critical": 1, "error": 2, "warning": 2, "known_informational": 1})
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
