import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "apps/links/src/org/tvbox/links/MainActivity.java").read_text()


class LinksLaunchPolicyTests(unittest.TestCase):
    def test_services_bind_local_app_only_to_their_selected_service(self):
        rows = re.findall(r'\{"([^\"]+)",\s*"(\d+)",\s*"([^\"]*)",\s*"([^\"]*)"\}', SOURCE)
        self.assertEqual(rows, [
            ("TorrServer", "8090", "", ""),
            ("VLC — удалённый доступ", "8443", "org.videolan.vlc", "VLC"),
        ])
        self.assertIn("String pkg=services.get(s)[2];", SOURCE)
        self.assertIn("String appName=services.get(s)[3];", SOURCE)
        self.assertIn("getLaunchIntentForPackage(pkg)", SOURCE)

    def test_qr_module_never_routes_launch_to_total_commander(self):
        self.assertNotRegex(SOURCE, re.compile(r"total\s*commander|com\.ghisler", re.I))

    def test_launch_button_names_the_selected_app(self):
        self.assertIn('launch.setText("Запустить " + appName + " на приставке")', SOURCE)
        self.assertIn('"Запущено приложение для выбранной службы: " + appName', SOURCE)


if __name__ == "__main__":
    unittest.main()
