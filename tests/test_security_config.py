"""Регрессионные проверки базовых настроек безопасности образа."""
import importlib.util
import pathlib
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("image_wifi_secrets", ROOT / "tools/check-image-wifi-secrets.py")
IMAGE_WIFI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IMAGE_WIFI)


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

    def test_image_wifi_scanner_detects_saved_ssid_and_key(self):
        self.assertTrue(IMAGE_WIFI.configured_networks('network={\n ssid="private"\n psk="hidden"\n}\n'))
        self.assertTrue(IMAGE_WIFI.configured_networks('network={\n ssid2=70726976617465\n}\n'))
        self.assertTrue(IMAGE_WIFI.configured_networks('WIFI_PASSWORD=hidden\n'))
        self.assertTrue(IMAGE_WIFI.configured_networks('<string name="SSID">"private"</string>'))

    def test_image_wifi_scanner_allows_default_config_without_profiles(self):
        text = '# default config\nctrl_interface=/data/misc/wifi/sockets\nupdate_config=1\n'
        self.assertFalse(IMAGE_WIFI.configured_networks(text))

    def test_image_wifi_scanner_fails_closed_on_unreadable_image(self):
        with tempfile.NamedTemporaryFile() as image, \
             mock.patch.object(IMAGE_WIFI, "debugfs", return_value=("", "Filesystem not open")):
            with self.assertRaisesRegex(ValueError, "ext4-образ"):
                IMAGE_WIFI.check_image(image.name)

    def test_release_workflow_scans_built_system_images(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("tools/check-image-wifi-secrets.py build/work/system.img build/work/vendor.img", workflow)

    def test_dev_and_stable_release_workflow_run_full_test_suite(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("python3 -m unittest discover -s tests -v", workflow)
        self.assertIn("--source \"автоматические тесты\" --output release/qa/test-log-analysis.md --fail-on-errors", workflow)

    def test_ota_update_defaults_to_confirmed_data_cleanup(self):
        installer = (ROOT / "apps/installer/src/org/tvbox/installer/MainActivity.java").read_text(encoding="utf-8")
        daemon = (ROOT / "apps/tvboxd/src/org/tvbox/daemon/Main.java").read_text(encoding="utf-8")
        self.assertIn('setPositiveButton("Продолжить с очисткой"', installer)
        self.assertIn('"UPDATE_INSTALL " + u[0] + " wipe"', installer)
        self.assertNotIn('"UPDATE_INSTALL " + u[0] + " keep"', installer)
        self.assertIn('a.length != 2 || !a[1].equals("wipe")', daemon)
        self.assertIn("обновление разрешено только с очисткой данных", daemon)


if __name__ == "__main__":
    unittest.main()
