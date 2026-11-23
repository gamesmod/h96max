"""Файловый менеджер по умолчанию — штатный RkExplorer (com.android.rk) вместо Total Commander; запасные «Файлы (root)»."""
import pathlib
import re
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
FIRSTBOOT = (ROOT / "overlay/vendor/bin/tvbox-firstboot.sh").read_text()
DAEMON = (ROOT / "apps/tvboxd/src/org/tvbox/daemon/Main.java").read_text()
FILES_OPS = (ROOT / "apps/tvboxd/src/org/tvbox/daemon/Files.java").read_text()
MANIFEST = (ROOT / "apps/files/AndroidManifest.xml").read_text()
BUILD = (ROOT / "build-sd.sh").read_text()
UNUSED = (ROOT / "tools/unused-stock-apps.txt").read_text()
TC = re.compile(r"total\s*commander|ghisler|tcplugins|chrisgch/tca", re.I)
# Исторические отчёты и журналы прошлых выпусков описывают то, что было выпущено, и не переписываются.
HISTORY = ("docs/release-notes/", "docs/release-qa/", "release/notes/")
# Скрипт первого запуска называет пакеты только для их удаления с уже обновлённых приставок.
CLEANUP = ("overlay/vendor/bin/tvbox-firstboot.sh", "tests/test_files_manager.py")


class FilesManagerTests(unittest.TestCase):
    def test_total_commander_removed_from_build_and_docs(self):
        tracked = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
        found = []
        for name in tracked:
            if name.startswith(HISTORY) or name in CLEANUP:
                continue
            path = ROOT / name
            if not path.is_file() or path.suffix in (".png", ".webp", ".gz", ".bmp"):
                continue
            try:
                text = path.read_text()
            except UnicodeDecodeError:
                continue
            if TC.search(text):
                found.append(name)
        self.assertEqual(found, [])

    def test_firstboot_only_removes_total_commander(self):
        for line in FIRSTBOOT.splitlines():
            if "ghisler" in line:
                self.assertNotRegex(line, r"appops set|pm grant|pm install|ALLOWED=|^g ")
        self.assertIn('pm uninstall --user 0 "$p"', FIRSTBOOT)

    def test_stock_file_manager_tile_is_default_on_home_screen(self):
        allowed = re.search(r'ALLOWED="([^"]+)"', FIRSTBOOT).group(1).split()
        self.assertEqual(allowed[:3], ["org.tvbox.iptv", "ru.yourok.torrserve", "com.android.rk"])
        self.assertNotIn("org.tvbox.files", allowed)
        self.assertIn("launcher-filemanager-tile", FIRSTBOOT)
        self.assertIn("com.ghisler.*|org.tvbox.files) [ \"$PLACED\" = 1 ] && continue; p=$FM", FIRSTBOOT)
        self.assertIn("appops set com.android.rk MANAGE_EXTERNAL_STORAGE allow", FIRSTBOOT)
        self.assertIn("pm enable com.android.rk", FIRSTBOOT)

    def test_stock_file_manager_is_kept_in_image(self):
        self.assertNotRegex(UNUSED, r"(?i)rkexplorer|com\.android\.rk\b")
        self.assertIn("-iname 'RkExplorer*.apk'", BUILD)
        self.assertLess(BUILD.index("remove_unused_stock_apps system\n"), BUILD.index("RkExplorer*.apk"))

    def test_root_operations_only_for_files_app(self):
        self.assertIn('static final String FILES = "org.tvbox.files";', DAEMON)
        self.assertIn("fs = uid == 0 || uid == 2000 || uid == uidOf(FILES)", DAEMON)
        self.assertIn('cmd.startsWith("FS_") ? !fs', DAEMON)
        # Файловые операции выполняются Java/Os-вызовами, без shell и без su.
        self.assertNotIn("/system/bin/sh", FILES_OPS)
        self.assertNotIn("Runtime.getRuntime", FILES_OPS)
        self.assertNotRegex((ROOT / "apps/files/src/org/tvbox/files/Root.java").read_text(), r'"su"|xbin/su')

    def test_fallback_files_app_does_not_take_default_role(self):
        self.assertIn("android.intent.action.MAIN", MANIFEST)
        for action in ("android.intent.action.VIEW", "android.intent.action.GET_CONTENT"):
            self.assertNotIn(action, MANIFEST)
        self.assertNotIn("resource/folder", MANIFEST)
        self.assertRegex(MANIFEST, r'android:name="\.Provider"[^>]*android:exported="false"')

    def test_files_app_is_built_and_installed(self):
        self.assertIn("apps/files/out/TvboxFiles.apk", (ROOT / "build-sd.sh").read_text())
        release = (ROOT / "tools/build-release.sh").read_text()
        self.assertRegex(release, r"for app in [^;]*\bfiles\b")
        self.assertIn("files/TvboxFiles", release)
        self.assertIn("Panadonik-files.apk", (ROOT / ".github/workflows/release.yml").read_text())


if __name__ == "__main__":
    unittest.main()
