import unittest
from patch_source import replace_once, transform, PATHS, LOCK

class SourcePatchTests(unittest.TestCase):
    def test_context_missing(self):
        for s in ["absent", "old old"]:
            with self.assertRaises(ValueError):
                replace_once(s, "old", "new")
    def test_media_client(self):
        s = "int source = tweaksData.getPlayerDataSource();\n    /**\n     * Use OkHttp for networking\nOkHttpClient client = OkHttpManager.instance().getClient();"
        result = transform(next(p for p in PATHS if p.endswith("/ExoMediaSourceFactory.java")), s)
        self.assertIn('PLAYER_DATA_SOURCE_OKHTTP', result)
        self.assertIn('proxySelector(java.net.ProxySelector.getDefault())', result)
        self.assertIn('isPanadonikProxy() ?', result)
    def test_id_and_abis(self):
        s = """        classpath 'com.google.gms:google-services:4.3.15'
        classpath 'com.google.firebase:firebase-crashlytics-gradle:2.7.1'
// The first letter of the flavor name must be in uppercase
if (true) { apply plugin: 'com.google.gms.google-services' }
android {
    versionName "32.83"
    def project = "SmartTube"
    applicationId "app.smarttube.fdroid"
    include 'armeabi-v7a', 'arm64-v8a', 'x86' // comment
    abiFilters 'armeabi-v7a', 'arm64-v8a'
    abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86'
    universalApk true
}
"""
        result = transform("smarttubetv/build.gradle", s)
        self.assertIn(LOCK["applicationId"], result)
        self.assertIn('resConfigs "ru", "en"', result)
        self.assertNotIn("'x86'", result)
        self.assertNotIn("com.google.gms", result)
        self.assertNotIn("firebase-crashlytics-gradle", result)
        self.assertIn("universalApk false", result)
        self.assertNotIn("abiFilters", result)
    def test_failover_hook(self):
        path = next(p for p in PATHS if p.endswith("/ErrorFixerController.java"))
        s = transform(path, "        Throwable error = getPlayer().getUnthrownError();\n")
        self.assertIn("rotateOnBuffering()", s)
        self.assertIn("invalidateFormatCache()", s)
        self.assertIn("reloadVideo()", s)
    def test_sources(self):
        import json
        from pathlib import Path
        rows = json.loads((Path(__file__).parent.parent / "smarttube-proxy/sources.json").read_text())
        self.assertGreaterEqual(len({r["id"] for r in rows}), 5)
    def test_autoupdate_disabled(self):
        s = "    public void start(boolean forceCheck) {\n        update();\n    }\n    @Override\n"
        self.assertNotIn("update();", transform(next(p for p in PATHS if p.endswith("/AppUpdatePresenter.java")), s))
    def test_rus_english_brand(self):
        path = "smarttubetv/src/stfdroid/res/values/strings.xml"
        self.assertEqual(transform(path, "SmartTube F-Droid / SmartTube F-Droid"), "Panadonik Tube / Panadonik Tube")

if __name__ == "__main__":
    unittest.main()
