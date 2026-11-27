"""Манифест сторонних входов и отсутствие RELEASE_INPUTS в release.yml."""
import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "tools/third-party-apps.json"
FETCH = (ROOT / "tools/fetch-third-party.sh").read_text()
RELEASE = (ROOT / ".github/workflows/release.yml").read_text()
REQUIRED = (ROOT / "tools/required-apps.sh").read_text()


class ThirdPartyFetchTests(unittest.TestCase):
    def test_manifest_pins_sha_and_destinations(self):
        data = json.loads(MANIFEST.read_text())
        items = data["items"]
        self.assertGreaterEqual(len(items), 7)
        dests = {item["dest"] for item in items}
        for name in (
            "dl/apk/com.tvlauncher.apk",
            "dl/apk/dev.imranr.obtainium.apk",
            "dl/apk/org.maintainteam.hypatia.apk",
            "dl/apk/org.videolan.vlc.apk",
            "dl/apk/ru.yourok.torrserve.apk",
            "dl/webview/webview-arm64.apk",
            "dl/torrserver/TorrServer-android-arm64",
        ):
            self.assertIn(name, dests)
        for item in items:
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
            self.assertTrue(item.get("url") or item.get("git"))

    def test_required_apps_covered_by_manifest(self):
        required = re.findall(r'^\s+([a-z0-9_.]+\.apk)\s*$', REQUIRED, re.M)
        dests = {item["dest"] for item in json.loads(MANIFEST.read_text())["items"]}
        dicio = json.loads((ROOT / "tools/dicio-inputs.json").read_text())["apk"]
        dests.add(dicio["path"])
        for name in required:
            self.assertIn("dl/apk/" + name, dests)

    def test_release_uses_lfs_and_fetch_without_private_archive(self):
        self.assertIn("lfs: false", RELEASE)
        self.assertIn("actions/cache@v4", RELEASE)
        self.assertIn("path: .git/lfs", RELEASE)
        self.assertIn("git lfs pull", RELEASE)
        self.assertNotIn("lfs: true", RELEASE)
        self.assertIn("tools/prepare-build-inputs.sh", RELEASE)
        self.assertIn("tools/fetch-third-party.sh", RELEASE)
        self.assertNotIn("RELEASE_INPUTS_ARCHIVE_URL", RELEASE)
        self.assertNotIn("RELEASE_INPUTS_SHA256", RELEASE)
        self.assertIn("third-party-apps.json", FETCH)
        self.assertIn("sha256", FETCH)


if __name__ == "__main__":
    unittest.main()
