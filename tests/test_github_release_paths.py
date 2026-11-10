"""Проверка адресов GitHub Release для обновления приставки."""
import pathlib
import subprocess
import shutil
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = ROOT / "sdk/jdk/usr/lib/jvm/java-21-openjdk-amd64/bin"
JAVAC = str(JDK / "javac") if (JDK / "javac").exists() else shutil.which("javac")
JAVA = str(JDK / "java") if (JDK / "javac").exists() else shutil.which("java")

class GitHubReleasePathTests(unittest.TestCase):
    def test_release_bases(self):
        source = (ROOT / "apps/tvboxd/src/org/tvbox/daemon/Main.java").read_text()
        start = source.index("    static boolean githubReleaseBase(String base) {")
        end = source.index("    static String latestUrl(", start)
        method = source[start:end]
        cases = [
            ("https://github.com/owner/repo/releases/latest/download/", True),
            ("https://github.com/owner/repo/releases/download/dev-v1.2.3/", True),
            ("https://github.com/owner/repo/releases/download/v1.2.3/", True),
            ("https://example.com/github.com/owner/repo/releases/download/v1/", False),
            ("https://github.com/owner/repo/releases/download/", False),
        ]
        checks = "\n".join(
            "if (githubReleaseBase(\"%s\") != %s) throw new AssertionError();"
            % (url, str(expected).lower()) for url, expected in cases
        )
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory)
            (path / "Check.java").write_text(
                "import java.net.URL; class Check {\n" + method
                + "public static void main(String[] args) {\n" + checks + "\n}}\n"
            )
            subprocess.run([JAVAC, "Check.java"], cwd=path, check=True, capture_output=True)
            subprocess.run([JAVA, "-cp", str(path), "Check"], check=True, capture_output=True)

if __name__ == "__main__":
    unittest.main()
