import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

spec=importlib.util.spec_from_file_location("app_compat",Path(__file__).resolve().parents[1]/"scripts/app_compat.py")
c=importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)

class AppTests(unittest.TestCase):
    def test_api(self):
        self.assertEqual(c.api_status(29,28),"MIN_SDK_BLOCK")
        self.assertEqual(c.api_status(28,28),"MIN_SDK_OK")
        self.assertEqual(c.api_status(None,31),"UNKNOWN")
    def test_manifest(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"AndroidManifest.xml"
            p.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.example"><uses-sdk android:minSdkVersion="23" android:targetSdkVersion="29"/><uses-permission android:name="android.permission.REBOOT"/></manifest>')
            r=c.source_manifest(p)
            self.assertEqual(r["api_status"]["28"],"MIN_SDK_OK")
            self.assertIn("android.permission.REBOOT",r["permissions"])
    def test_apk_abi(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"test.apk"
            with zipfile.ZipFile(p,"w") as z:
                z.writestr("lib/arm64-v8a/libtest.so",b"abc")
                z.writestr("assets/libfake.so",b"abc")
            self.assertEqual(c.apk_abis(p),["arm64-v8a"])
    def test_hash_mismatch(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            (root/"x.apk").write_bytes(b"test")
            item={"id":"x","version":"1","dest":"x.apk","sha256":"0"*64}
            self.assertEqual(c.apk_record(root,item)["status"],"HASH_MISMATCH")
    def test_missing(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(c.apk_record(Path(d),{"id":"x","version":"1","dest":"x.apk","sha256":"0"*64})["status"],"NOT_PRESENT")
