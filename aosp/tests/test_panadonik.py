import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("panadonik", Path(__file__).resolve().parents[1] / "scripts/panadonik.py")
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)

class TestPanadonik(unittest.TestCase):
    def test_properties_allowlist(self):
        self.assertEqual(p.properties("ro.build.version.sdk=31\nwifi.password=secret"), {"ro.build.version.sdk":"31"})
    def test_lfs(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "x.img"
            f.write_text("version https://git-lfs.github.com/spec/v1\n")
            self.assertTrue(p.lfs_pointer(f))
    def test_hash(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "x"
            f.write_bytes(b"abc")
            self.assertEqual(p.sha(f), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    def test_manifest(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "m.xml"
            f.write_text('<manifest><default revision="' + "a"*40 + '"/><project name="x"/></manifest>')
            self.assertTrue(p.inspect_manifest(f)["fully_pinned_flat_manifest"])
            f.write_text('<manifest><include name="extra.xml"/><project name="x" revision="main"/></manifest>')
            self.assertFalse(p.inspect_manifest(f)["fully_pinned_flat_manifest"])
    def test_cpu(self):
        self.assertAlmostEqual(p.cpu_percent(p.cpu_ticks("cpu 1 0 1 8 0 0 0 0"), p.cpu_ticks("cpu 3 0 3 14 0 0 0 0")), 40)
        with self.assertRaises(ValueError):
            p.cpu_percent((1,1),(1,1))
    def test_memory(self):
        self.assertEqual(p.parse_meminfo("MemTotal: 100 kB\nMemAvailable: 45 kB\nSwapTotal: 0 kB"), {"MemTotal":100,"MemAvailable":45,"SwapTotal":0})
    def test_extended_benchmark_parsers(self):
        self.assertEqual(p.parse_loadavg("1.00 0.50 0.25 1/100 10")["load_5m"], 0.5)
        self.assertEqual(p.parse_thermal("soc\t73500\ngpu\t61\n"), {"soc": 73.5, "gpu": 61.0})
        self.assertEqual(p.parse_named_ints("cpu0\t1200000\nbad\tx\n"), {"cpu0": 1200000})
        gfx = p.parse_gfxinfo("Total frames rendered: 1,000\nJanky frames: 25 (2.50%)\n95th percentile: 18ms\n")
        self.assertEqual(gfx["total_frames"], 1000)
        self.assertEqual(gfx["janky_percent"], 2.5)
    def test_boot_timeline_has_stage_durations(self):
        rows = p.parse_boot_events("I/boot_progress_start( 1): 1000\nI/boot_progress_pms_ready( 1): 4500\n")
        self.assertEqual(rows[0], {"event": "boot_progress_start", "elapsed_ms": 1000, "stage_duration_ms": 1000})
        self.assertEqual(rows[1]["stage_duration_ms"], 3500)
if __name__ == "__main__":
    unittest.main()
