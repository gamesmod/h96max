"""Голосовой пульт: microphone FGS до early-exit; дубль WEB_SEARCH до ready не рвёт STT."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = (ROOT / "apps/voicebridge/AndroidManifest.xml").read_text()
SERVICE = (ROOT / "apps/voicebridge/src/org/tvbox/voicebridge/ListeningService.java").read_text()
MAIN = (ROOT / "apps/voicebridge/src/org/tvbox/voicebridge/MainActivity.java").read_text()


class VoicebridgeFgsTests(unittest.TestCase):
    def test_manifest_microphone_fgs(self):
        self.assertIn("android.permission.FOREGROUND_SERVICE_MICROPHONE", MANIFEST)
        self.assertIn('android:foregroundServiceType="microphone"', MANIFEST)
        self.assertIn("android.speech.action.WEB_SEARCH", MANIFEST)

    def test_service_foreground_before_early_exit(self):
        ensure = SERVICE.index("ensureForeground();")
        active_guard = SERVICE.index("if (active)")
        self.assertLess(ensure, active_guard)
        self.assertIn("FOREGROUND_SERVICE_TYPE_MICROPHONE", SERVICE)
        self.assertIn("Повтор до ready", SERVICE)

    def test_trampoline_starts_foreground_service(self):
        self.assertIn("startForegroundService", MAIN)
        self.assertIn("ListeningService", MAIN)


if __name__ == "__main__":
    unittest.main()
