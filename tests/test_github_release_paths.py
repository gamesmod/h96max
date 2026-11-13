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
    def test_http_errors_are_reported_without_exposing_source_url(self):
        source = (ROOT / "apps/tvboxd/src/org/tvbox/daemon/Main.java").read_text()
        start = source.index("    static java.util.Map<String, String> latest(String base)")
        end = source.index("    static boolean newer(", start)
        method = source[start:end]
        harness = r'''
import java.io.*;
import java.net.*;
import com.sun.net.httpserver.HttpServer;
class Check {
    static String latestUrl(String base) { return base; }
METHOD
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing", e -> { e.sendResponseHeaders(404, -1); e.close(); });
        server.createContext("/ok", e -> {
            byte[] body = "version=1.0\nfile=update.zip\nsha256=abc\nsize=1\n".getBytes("UTF-8");
            e.sendResponseHeaders(200, body.length);
            try (OutputStream out = e.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            try { latest(base + "/missing?token=private"); throw new AssertionError("accepted 404"); }
            catch (IOException e) { if (!"HTTP 404".equals(e.getMessage())) throw e; }
            if (!"1.0".equals(latest(base + "/ok").get("version"))) throw new AssertionError("bad metadata");
        } finally { server.stop(0); }
    }
}'''.replace("METHOD", method)
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory)
            (path / "Check.java").write_text(harness, encoding="utf-8")
            subprocess.run([JAVAC, "Check.java"], cwd=path, check=True, capture_output=True)
            subprocess.run([JAVA, "-cp", str(path), "Check"], cwd=path, check=True, capture_output=True, timeout=20)

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
