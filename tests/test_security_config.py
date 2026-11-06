"""Регрессионные проверки базовых настроек безопасности образа."""
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class ReleaseSecurityConfigTests(unittest.TestCase):
    def test_adb_requires_host_key(self):
        build = (ROOT / "build-sd.sh").read_text(encoding="utf-8")
        self.assertIn("$MNT/system/build.prop", build)
        self.assertIn("s/^ro.secure=.*/ro.secure=1/", build)
        self.assertIn("s/^ro.adb.secure=.*/ro.adb.secure=1/", build)
        self.assertNotRegex(build, r"(?m)^ro\.secure=1$")
        self.assertNotRegex(build, r"(?m)^ro\.adb\.secure=1$")

    def test_network_adb_is_not_enabled_at_boot(self):
        init = (ROOT / "overlay/vendor/etc/init/tvbox.rc").read_text(encoding="utf-8")
        self.assertNotRegex(init, r"(?m)^\s*setprop\s+service\.adb\.tcp\.port\s+\d+")

    def test_suid_helper_is_limited_to_shell_group(self):
        build = (ROOT / "build-sd.sh").read_text(encoding="utf-8")
        self.assertIn('sudo chown 0:2000 "$MNT/system/xbin/su"', build)
        self.assertIn('sudo chmod 4750 "$MNT/system/xbin/su"', build)


if __name__ == "__main__":
    unittest.main()
