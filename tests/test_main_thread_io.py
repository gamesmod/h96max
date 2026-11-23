"""Собственные apps не должны читать/писать тяжёлые файлы и сокеты прямо в onCreate/refresh на UI."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
IPTV = (ROOT / "apps/iptv/src/org/tvbox/iptv/MainActivity.java").read_text()
BOOTLOG = (ROOT / "apps/bootlog/src/org/tvbox/bootlog/MainActivity.java").read_text()
LINKS = (ROOT / "apps/links/src/org/tvbox/links/MainActivity.java").read_text()
CLEAN = (ROOT / "apps/clean/src/org/tvbox/clean/MainActivity.java").read_text()
POWER = (ROOT / "apps/power/src/org/tvbox/power/MainActivity.java").read_text()
PERF = (ROOT / "apps/tvboxd/src/org/tvbox/daemon/Perf.java").read_text()


def method_body(source: str, name: str) -> str:
    """Грубое извлечение тела метода до следующего метода того же уровня отступа signature."""
    m = re.search(rf"(?:public |protected |private |static )*[\w<>\[\]]+\s+{re.escape(name)}\s*\([^)]*\)\s*\{{", source)
    if not m:
        raise AssertionError(f"метод {name} не найден")
    start = m.end()
    depth = 1
    i = start
    while i < len(source) and depth:
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
        i += 1
    return source[start : i - 1]


class MainThreadIoPolicyTests(unittest.TestCase):
    def test_iptv_oncreate_defers_playlist_io(self):
        body = method_body(IPTV, "onCreate")
        self.assertIn('"iptv-load-playlist"', body)
        self.assertNotIn("loadCachedIndex(bundled))", body.split("new Thread")[0])
        self.assertNotIn("parse(read(", body.split("new Thread")[0])

    def test_iptv_play_writes_playlist_off_ui(self):
        self.assertIn("playVlcPlaylistAsync", IPTV)
        self.assertIn('"iptv-vlc-playlist"', IPTV)
        self.assertNotIn("boolean playVlcPlaylist(", IPTV)

    def test_bootlog_loads_files_off_ui(self):
        body = method_body(BOOTLOG, "onCreate")
        self.assertIn('"bootlog-load"', body)
        self.assertIn("Загрузка журнала…", body)
        self.assertNotIn('read(DIR + "dmesg.txt")', body.split("new Thread")[0])

    def test_links_qr_built_off_ui(self):
        body = method_body(LINKS, "refresh")
        self.assertIn("qr(link, size)", body)
        self.assertIn("new Thread", body)
        before_thread = body.split("new Thread")[0]
        self.assertNotIn("qr(link", before_thread)

    def test_clean_history_io_off_ui(self):
        body = method_body(CLEAN, "storeHistory")
        self.assertIn('"perf-history"', body)
        self.assertIn("new Thread", body)
        self.assertIn("showHistory", CLEAN)
        self.assertIn('"perf-history-read"', CLEAN)

    def test_power_cmdline_off_ui(self):
        body = method_body(POWER, "onCreate")
        self.assertIn('"power-boot-media"', body)
        self.assertNotIn("bootedFromSd()", body.split("new Thread")[0])

    def test_perf_cpu_load_no_sleep_on_request_path(self):
        body = method_body(PERF, "cpuLoad")
        self.assertNotIn("Main.sleep", body)
        self.assertNotIn("Thread.sleep", body)
        self.assertIn("return new int[st.length]", body)


if __name__ == "__main__":
    unittest.main()
