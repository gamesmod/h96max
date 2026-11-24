import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("compare_inputs", Path(__file__).resolve().parents[1] / "scripts/compare.py")
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)

class CompareTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.a = Path(self.tmp.name) / "a"
        self.b = Path(self.tmp.name) / "b"
    def test_config(self):
        self.a.write_text("CONFIG_A=y\n# CONFIG_B is not set\n")
        self.b.write_text("CONFIG_A=m\nCONFIG_C=y\n")
        d = c.diff_maps(c.config(self.a), c.config(self.b))
        self.assertEqual(d["changed"]["CONFIG_A"], {"left":"y","right":"m"})
        self.assertEqual(d["only_left"], ["CONFIG_B"])
        self.assertEqual(d["only_right"], ["CONFIG_C"])
    def test_conflict(self):
        self.a.write_text("CONFIG_A=y\nCONFIG_A=m\n")
        with self.assertRaises(ValueError):
            c.config(self.a)
    def test_manifest(self):
        self.a.write_text('<manifest><remote name="r" fetch="https://example.org" revision="remote"/><default remote="r" revision="default"/><project name="p"/><include name="other.xml"/></manifest>')
        self.b.write_text('<manifest><project name="p" revision="x"/></manifest>')
        self.assertEqual(c.manifest(self.a)[0]["p"]["revision"], "remote")
        self.assertEqual(c.compare_manifests(self.a,self.b)["status"], "PARTIAL_UNRESOLVED")
    def test_vintf(self):
        self.a.write_text('<manifest type="device"><hal format="hidl"><name>x</name><version>1.0</version><fqname>@1.0::IX/default</fqname></hal></manifest>')
        self.b.write_text('<manifest type="device"><hal format="hidl"><name>x</name><version>1.1</version></hal></manifest>')
        r = c.compare_vintf(self.a,self.b)
        self.assertIn("hidl:x",r["differences"]["changed"])
        self.assertEqual(r["status"],"STATIC_DIFF_ONLY")
    def test_blobs(self):
        row = {"partition":"vendor","path":"lib/libx.so","sha256":"a","needed":["libc.so"]}
        self.a.write_text(json.dumps([row]))
        self.b.write_text(json.dumps([{**row,"sha256":"b","needed":["libm.so"]}]))
        self.assertIn("vendor/lib/libx.so",c.compare_blobs(self.a,self.b)["needed_differences"]["changed"])
    def test_missing_elf_metadata_not_empty(self):
        row = {"partition":"vendor","path":"lib/libx.so","sha256":"a"}
        self.a.write_text(json.dumps([row]))
        self.b.write_text(json.dumps([{**row,"needed":[]}]))
        self.assertIn("vendor/lib/libx.so",c.compare_blobs(self.a,self.b)["needed_differences"]["changed"])
