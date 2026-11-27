"""Check the background remote's Russian command vocabulary without Android or a microphone."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = ROOT / "sdk/jdk/usr/lib/jvm/java-21-openjdk-amd64/bin"
JAVAC = shutil.which("javac") or (str(JDK / "javac") if (JDK / "javac").exists() else None)
JAVA = shutil.which("java") or (str(JDK / "java") if (JDK / "java").exists() else None)


@unittest.skipUnless(JAVA and JAVAC, "Java toolchain required")
class VoiceCommandsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.path = pathlib.Path(cls.temp.name)
        harness = cls.path / "CheckVoice.java"
        harness.write_text('''
import org.tvbox.voicebridge.VoiceCommand;
public class CheckVoice {
    public static void main(String[] args) {
        VoiceCommand c = VoiceCommand.parse(args.length == 0 ? null : args[0]);
        System.out.print(c.kind.name() + "|" + c.app);
    }
}
''')
        subprocess.run([JAVAC, "--release", "8", "-encoding", "UTF-8", "-d", str(cls.path),
                        str(ROOT / "apps/voicebridge/src/org/tvbox/voicebridge/VoiceCommand.java"),
                        str(harness)], check=True, capture_output=True)

    def parse(self, text):
        command = [JAVA, "-cp", str(self.path), "CheckVoice"]
        if text is not None:
            command.append(text)
        return subprocess.run(command, check=True, capture_output=True, text=True, timeout=10).stdout

    def test_media_commands_are_idempotent_not_play_pause_toggle(self):
        for phrase, expected in [("Пауза!", "PAUSE|"), ("поставь на паузу", "PAUSE|"),
                                 ("Продолжи", "PLAY|"), ("следующий трек", "NEXT|"),
                                 ("предыдущее видео", "PREVIOUS|")]:
            with self.subTest(phrase=phrase):
                self.assertEqual(self.parse(phrase), expected)

    def test_volume_home_and_time(self):
        for phrase, expected in [("Сделай громче", "LOUDER|"), ("тише", "QUIETER|"),
                                 ("Выключи звук", "MUTE|"), ("Включи звук", "UNMUTE|"),
                                 ("Домой", "HOME|"), ("Который час?", "TIME|")]:
            with self.subTest(phrase=phrase):
                self.assertEqual(self.parse(phrase), expected)

    def test_app_opening_requires_explicit_request_and_preserves_whole_name(self):
        self.assertEqual(self.parse("Открой  VLC"), "OPEN|vlc")
        self.assertEqual(self.parse("запусти ТВ-каналы"), "OPEN|тв каналы")
        self.assertEqual(self.parse("Открой VLC; удали данные"), "OPEN|vlc удали данные")
        self.assertEqual(self.parse("VLC"), "UNKNOWN|")
        self.assertEqual(self.parse("открой"), "UNKNOWN|")

    def test_unimplemented_and_destructive_requests_do_not_execute(self):
        for phrase in [None, "", "сбрось данные", "перезагрузи приставку", "выключи приставку",
                       "удали приложение", "пауза затем домой", "пауза; перезагрузка",
                       "следующий канал", "назад", "включи swap", "$(reboot)"]:
            with self.subTest(phrase=phrase):
                self.assertEqual(self.parse(phrase), "UNKNOWN|")
