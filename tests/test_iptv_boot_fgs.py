"""IndexOnBootService — typed dataSync FGS, без mic/camera while-in-use."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = (ROOT / "apps/iptv/AndroidManifest.xml").read_text()
SERVICE = (ROOT / "apps/iptv/src/org/tvbox/iptv/IndexOnBootService.java").read_text()


class IptvBootFgsTests(unittest.TestCase):
    def test_manifest_declares_datasync_fgs(self):
        self.assertIn("android.permission.FOREGROUND_SERVICE_DATA_SYNC", MANIFEST)
        self.assertIn('android:foregroundServiceType="dataSync"', MANIFEST)
        self.assertIn(".IndexOnBootService", MANIFEST)

    def test_service_starts_typed_datasync_foreground(self):
        self.assertIn("startDataSyncForeground", SERVICE)
        self.assertIn("FOREGROUND_SERVICE_TYPE_DATA_SYNC", SERVICE)
        self.assertNotIn("microphone", MANIFEST.lower())

    def test_build_uses_framework_res_for_fgs_attr(self):
        build = (ROOT / "apps/iptv/build.sh").read_text()
        self.assertIn("sdk/framework/framework-res.apk", build)


if __name__ == "__main__":
    unittest.main()
