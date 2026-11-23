"""tools/setup-release-secrets.sh: секреты release.yml из прежних ключей, без вывода ключей и без генерации новых."""
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/setup-release-secrets.sh"
TEXT = SCRIPT.read_text()
WORKFLOW = (ROOT / ".github/workflows/release.yml").read_text()
COMPONENTS = (ROOT / "COMPONENTS.md").read_text()
SECRETS = ("RELEASE_INPUTS_ARCHIVE_URL", "RELEASE_INPUTS_SHA256", "TVBOX_SIGNING_KEY",
           "TVBOX_SIGNING_CERT", "OTA_SIGNING_KEY", "OTA_SIGNING_CERT")

GH_STUB = """#!/bin/bash
case "$1 $2" in
  "auth status") exit 0;;
  "auth setup-git") exit 0;;
  "api "*) echo true; exit 0;;
  "secret set") cat > "$GH_LOG/$3"; exit 0;;
  "secret list") exit 0;;
esac
echo "unexpected gh $*" >&2; exit 1
"""


@unittest.skipUnless(shutil.which("openssl"), "нужен openssl")
class SetupReleaseSecretsTests(unittest.TestCase):
    def test_script_is_valid_bash(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)

    def test_covers_exactly_the_workflow_secrets(self):
        used = set(re.findall(r"secrets\.([A-Z0-9_]+)", WORKFLOW))
        self.assertEqual(used, set(SECRETS))
        for name in SECRETS:
            self.assertIn(name, TEXT)

    def test_expected_fingerprints_match_components(self):
        for var in ("TVBOX_CERT_SHA256", "OTA_CERT_SHA256"):
            value = re.search(var + r'="([0-9a-f]{64})"', TEXT).group(1)
            self.assertIn(value, COMPONENTS)

    def test_never_generates_or_prints_keys(self):
        self.assertNotRegex(TEXT, r"openssl (genrsa|genpkey|req)|keytool|cat \"\$SECRETS_DIR")
        self.assertIn('gh secret set "$1" -R "$REPO_SLUG" < "$2"', TEXT)

    def _fixture(self, tmp, patch_fingerprints):
        repo = tmp / "repo"
        (repo / "tools").mkdir(parents=True)
        script = TEXT
        sec = repo / "secrets"
        sec.mkdir()
        fps = {}
        for name in ("tvbox", "ota"):
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                            "-subj", "/CN=test-" + name, "-keyout", str(sec / f"{name}-key.pem"),
                            "-out", str(sec / f"{name}-cert.pem")], check=True, capture_output=True)
            der = subprocess.run(["openssl", "x509", "-in", str(sec / f"{name}-cert.pem"), "-outform", "DER"],
                                 check=True, capture_output=True).stdout
            import hashlib
            fps[name] = hashlib.sha256(der).hexdigest()
        if patch_fingerprints:
            script = re.sub(r'TVBOX_CERT_SHA256="[0-9a-f]+"', f'TVBOX_CERT_SHA256="{fps["tvbox"]}"', script)
            script = re.sub(r'OTA_CERT_SHA256="[0-9a-f]+"', f'OTA_CERT_SHA256="{fps["ota"]}"', script)
        path = repo / "tools/setup-release-secrets.sh"
        path.write_text(script)
        path.chmod(0o755)
        (repo / "build-sd.sh").write_text("echo 'ro.tvbox.release=0.4.9'\nro.tvbox.release=0.4.9\n")
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        bin_dir = tmp / "bin"
        bin_dir.mkdir()
        (bin_dir / "gh").write_text(GH_STUB)
        (bin_dir / "gh").chmod(0o755)
        log = tmp / "gh"
        log.mkdir()
        archive = tmp / "inputs.tar.zst"
        archive.write_bytes(b"not a real archive")
        env = dict(os.environ, PATH=f"{bin_dir}:{os.environ['PATH']}", GH_LOG=str(log))
        env.pop("RELEASE_INPUTS_ARCHIVE_URL", None)
        return path, sec, archive, log, env

    def test_refuses_keys_that_are_not_the_project_keys(self):
        with tempfile.TemporaryDirectory() as t:
            path, sec, archive, log, env = self._fixture(pathlib.Path(t), patch_fingerprints=False)
            r = subprocess.run(["bash", str(path), "--skip-git", "--archive", str(archive), "--archive-url",
                                "https://example.invalid/x"], env=env, capture_output=True, text=True,
                               stdin=subprocess.DEVNULL)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("COMPONENTS.md", r.stderr)
            self.assertEqual(list(log.iterdir()), [])
            self.assertNotIn("PRIVATE KEY", r.stdout + r.stderr)

    def test_sets_secrets_via_stdin_without_printing_keys(self):
        with tempfile.TemporaryDirectory() as t:
            path, sec, archive, log, env = self._fixture(pathlib.Path(t), patch_fingerprints=True)
            url = "https://example.invalid/private/inputs.tar.zst"
            r = subprocess.run(["bash", str(path), "--skip-git", "--archive", str(archive)],
                               env=dict(env, RELEASE_INPUTS_ARCHIVE_URL=url), capture_output=True, text=True,
                               stdin=subprocess.DEVNULL)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertNotIn("PRIVATE KEY", r.stdout + r.stderr)
            self.assertNotIn(url, r.stdout + r.stderr)
            self.assertEqual(sorted(p.name for p in log.iterdir()), sorted(SECRETS))
            self.assertEqual((log / "TVBOX_SIGNING_KEY").read_bytes(), (sec / "tvbox-key.pem").read_bytes())
            self.assertEqual((log / "OTA_SIGNING_CERT").read_bytes(), (sec / "ota-cert.pem").read_bytes())
            self.assertEqual((log / "RELEASE_INPUTS_ARCHIVE_URL").read_text(), url)
            import hashlib
            self.assertEqual((log / "RELEASE_INPUTS_SHA256").read_text(),
                             hashlib.sha256(archive.read_bytes()).hexdigest())

    def test_dry_run_sets_nothing_and_rejects_public_repo_url(self):
        with tempfile.TemporaryDirectory() as t:
            path, sec, archive, log, env = self._fixture(pathlib.Path(t), patch_fingerprints=True)
            r = subprocess.run(["bash", str(path), "--skip-git", "--dry-run", "--archive", str(archive)],
                               env=env, capture_output=True, text=True, stdin=subprocess.DEVNULL)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(list(log.iterdir()), [])
            r = subprocess.run(["bash", str(path), "--skip-git", "--archive", str(archive), "--archive-url",
                                "https://github.com/gamesmod/h96max/releases/download/x/inputs.tar.zst"],
                               env=env, capture_output=True, text=True, stdin=subprocess.DEVNULL)
            self.assertNotEqual(r.returncode, 0)
            self.assertEqual(list(log.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
