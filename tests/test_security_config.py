"""Регрессионные проверки базовых настроек безопасности образа."""
import importlib.util
import pathlib
import subprocess
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("image_wifi_secrets", ROOT / "tools/check-image-wifi-secrets.py")
IMAGE_WIFI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IMAGE_WIFI)


class ReleaseSecurityConfigTests(unittest.TestCase):
    def test_launcher_repair_removes_duplicates_and_keeps_order(self):
        script = (ROOT / "overlay/vendor/bin/tvbox-firstboot.sh").read_text(encoding="utf-8")
        start = script.index('    ALLOWED="org.tvbox.iptv')
        end = script.index('    if [ "$T" != "$OLD" ]', start)
        fragment = script[start:end]
        command = ('pm() { return 0; }\n'
                   "OLD='org.tvbox.iptv&#30;org.tvbox.links&#30;org.tvbox.iptv'\n"
                   + fragment + '\nprintf "%s" "$T"\n')
        result = subprocess.run(["sh", "-c", command], capture_output=True, text=True, check=True)
        tiles = result.stdout.split("&#30;")
        self.assertEqual(tiles[:2], ["org.tvbox.iptv", "org.tvbox.links"])
        self.assertEqual(len(tiles), len(set(tiles)))
        self.assertIn("ru.yourok.torrserve", tiles)
        self.assertIn("org.smarttube.stable", tiles)

    def test_build_rejects_missing_required_applications(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                ["bash", "-c", 'R="$1"; source "$2"; tvbox_require_apks', "--",
                 directory, str(ROOT / "tools/required-apps.sh")],
                capture_output=True, text=True, check=False,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("org.videolan.vlc.apk", result.stderr)

    def test_zram_cannot_be_enabled_from_project(self):
        script = (ROOT / "overlay/vendor/bin/tvbox-zram.sh").read_text(encoding="utf-8")
        ui = (ROOT / "apps/clean/src/org/tvbox/clean/MainActivity.java").read_text(encoding="utf-8")
        perf = (ROOT / "apps/tvboxd/src/org/tvbox/daemon/Perf.java").read_text(encoding="utf-8")
        build = (ROOT / "build-sd.sh").read_text(encoding="utf-8")
        self.assertNotRegex(script, r"(?m)^\s*(?:mkswap|swapon)\b")
        self.assertNotIn('PERF_ZRAM_SET ', ui)
        self.assertIn('op.done(false, "сжатая подкачка отключена")', perf)
        self.assertIn('property:persist.sys.zram_enabled=1/,+1d', build)
        self.assertIn('persist.sys.zram_enabled=0/', build)

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
