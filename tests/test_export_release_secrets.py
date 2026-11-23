"""tools/export-release-secrets.sh: зашифрованный архив секретов release.yml и обратный импорт."""
import hashlib
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/export-release-secrets.sh"
TEXT = SCRIPT.read_text()
WORKFLOW = (ROOT / ".github/workflows/release.yml").read_text()
COMPONENTS = (ROOT / "COMPONENTS.md").read_text()
SECRETS = ("RELEASE_INPUTS_ARCHIVE_URL", "RELEASE_INPUTS_SHA256", "TVBOX_SIGNING_KEY",
           "TVBOX_SIGNING_CERT", "OTA_SIGNING_KEY", "OTA_SIGNING_CERT")
PASS = "correct horse battery staple"

GH_STUB = """#!/bin/bash
case "$1 $2" in
  "auth status"|"auth setup-git"|"secret list") exit 0;;
  "api "*) echo true; exit 0;;
  "secret set") cat > "$GH_LOG/$3"; exit 0;;
esac
echo "unexpected gh $*" >&2; exit 1
"""


@unittest.skipUnless(shutil.which("openssl"), "нужен openssl")
class ExportReleaseSecretsTests(unittest.TestCase):
    def test_script_is_valid_bash(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)

    def test_covers_exactly_the_workflow_secrets(self):
        self.assertEqual(set(re.findall(r"secrets\.([A-Z0-9_]+)", WORKFLOW)), set(SECRETS))
        for name in SECRETS:
            self.assertIn(name, TEXT)
        for pem in ("tvbox-key.pem", "tvbox-cert.pem", "ota-key.pem", "ota-cert.pem"):
            self.assertIn("secrets/" + pem, WORKFLOW)
            self.assertIn("secrets/" + pem, TEXT)

    def test_fingerprints_match_components_and_no_key_generation(self):
        for var in ("TVBOX_CERT_SHA256", "OTA_CERT_SHA256"):
            self.assertIn(re.search(var + r'="([0-9a-f]{64})"', TEXT).group(1), COMPONENTS)
        self.assertNotRegex(TEXT, r"openssl (genrsa|genpkey|req)|keytool")
        self.assertIn("-pbkdf2", TEXT)

    def test_bundle_name_is_git_ignored(self):
        r = subprocess.run(["git", "check-ignore", "-q", "h96max-release-secrets-20261123-0830.tar.gz.enc"], cwd=ROOT)
        self.assertEqual(r.returncode, 0)

    def _fixture(self, tmp):
        repo = tmp / "repo"
        (repo / "tools").mkdir(parents=True)
        sec = tmp / "keys"
        sec.mkdir()
        fps = {}
        for name in ("tvbox", "ota"):
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                            "-subj", "/CN=test-" + name, "-keyout", str(sec / f"{name}-key.pem"),
                            "-out", str(sec / f"{name}-cert.pem")], check=True, capture_output=True)
            der = subprocess.run(["openssl", "x509", "-in", str(sec / f"{name}-cert.pem"), "-outform", "DER"],
                                 check=True, capture_output=True).stdout
            fps[name] = hashlib.sha256(der).hexdigest()
        script = re.sub(r'TVBOX_CERT_SHA256="[0-9a-f]+"', f'TVBOX_CERT_SHA256="{fps["tvbox"]}"', TEXT)
        script = re.sub(r'OTA_CERT_SHA256="[0-9a-f]+"', f'OTA_CERT_SHA256="{fps["ota"]}"', script)
        path = repo / "tools/export-release-secrets.sh"
        path.write_text(script)
        (repo / "build-sd.sh").write_text("ro.tvbox.release=0.4.9\n")
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        bin_dir = tmp / "bin"
        bin_dir.mkdir()
        (bin_dir / "gh").write_text(GH_STUB)
        (bin_dir / "gh").chmod(0o755)
        log = tmp / "gh"
        log.mkdir()
        inputs = tmp / "release-inputs.tar.zst"
        inputs.write_bytes(b"inputs")
        env = dict(os.environ, PATH=f"{bin_dir}:{os.environ['PATH']}", GH_LOG=str(log), HOME=str(tmp),
                   RELEASE_SECRETS_PASSPHRASE=PASS)
        for var in ("RELEASE_INPUTS_ARCHIVE_URL", "ARCHIVE", "SECRETS_DIR"):
            env.pop(var, None)
        return repo, path, sec, inputs, log, env

    def _run(self, args, env):
        return subprocess.run(["bash", *map(str, args)], env=env, capture_output=True, text=True,
                              stdin=subprocess.DEVNULL)

    def test_roundtrip_encrypt_then_apply(self):
        with tempfile.TemporaryDirectory() as t:
            tmp = pathlib.Path(t)
            repo, path, sec, inputs, log, env = self._fixture(tmp)
            url = "https://example.invalid/private/inputs.tar.zst"
            out = tmp / "bundle.tar.gz.enc"
            r = self._run([path, "--secrets-dir", sec, "--archive", inputs, "--output", out],
                          dict(env, RELEASE_INPUTS_ARCHIVE_URL=url))
            self.assertEqual(r.returncode, 0, r.stderr)
            data = out.read_bytes()
            self.assertTrue(data.startswith(b"Salted__"))
            self.assertNotIn(b"PRIVATE KEY", data)
            self.assertEqual(out.stat().st_mode & 0o777, 0o600)
            self.assertNotIn("PRIVATE KEY", r.stdout + r.stderr)
            self.assertNotIn(url, r.stdout + r.stderr)
            self.assertEqual(list(log.iterdir()), [])

            bad = self._run([path, "--apply", out], dict(env, RELEASE_SECRETS_PASSPHRASE="wrong password!"))
            self.assertNotEqual(bad.returncode, 0)
            self.assertEqual(list(log.iterdir()), [])

            r = self._run([path, "--apply", out], env)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertNotIn("PRIVATE KEY", r.stdout + r.stderr)
            self.assertEqual(sorted(p.name for p in log.iterdir()), sorted(SECRETS))
            self.assertEqual((log / "TVBOX_SIGNING_KEY").read_bytes(), (sec / "tvbox-key.pem").read_bytes())
            self.assertEqual((log / "OTA_SIGNING_KEY").read_bytes(), (sec / "ota-key.pem").read_bytes())
            self.assertEqual((log / "OTA_SIGNING_CERT").read_bytes(), (sec / "ota-cert.pem").read_bytes())
            self.assertEqual((log / "RELEASE_INPUTS_ARCHIVE_URL").read_text(), url)
            self.assertEqual((log / "RELEASE_INPUTS_SHA256").read_text(), hashlib.sha256(b"inputs").hexdigest())

    def test_default_output_is_outside_repo_in_home(self):
        with tempfile.TemporaryDirectory() as t:
            tmp = pathlib.Path(t)
            repo, path, sec, inputs, log, env = self._fixture(tmp)
            r = self._run([path, "--secrets-dir", sec, "--archive", inputs], env)
            self.assertEqual(r.returncode, 0, r.stderr)
            made = list(tmp.glob("h96max-release-secrets-*.tar.gz.enc"))
            self.assertEqual(len(made), 1)

    def test_refuses_unignored_output_inside_work_tree(self):
        with tempfile.TemporaryDirectory() as t:
            tmp = pathlib.Path(t)
            repo, path, sec, inputs, log, env = self._fixture(tmp)
            out = repo / "secrets-backup.tar.gz.enc"
            r = self._run([path, "--secrets-dir", sec, "--archive", inputs, "--output", out], env)
            self.assertNotEqual(r.returncode, 0)
            self.assertFalse(out.exists())
            (repo / ".gitignore").write_text("secrets-backup.*\n")
            r = self._run([path, "--secrets-dir", sec, "--archive", inputs, "--output", out], env)
            self.assertEqual(r.returncode, 0, r.stderr)

    def test_refuses_foreign_keys_and_warns_on_no_encrypt(self):
        with tempfile.TemporaryDirectory() as t:
            tmp = pathlib.Path(t)
            repo, path, sec, inputs, log, env = self._fixture(tmp)
            real = tmp / "real.sh"
            real.write_text(TEXT)
            r = self._run([real, "--secrets-dir", sec, "--archive", inputs, "--output", tmp / "x.enc"], env)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("COMPONENTS.md", r.stderr)
            self.assertFalse((tmp / "x.enc").exists())
            out = tmp / "plain.tar.gz"
            r = self._run([path, "--secrets-dir", sec, "--archive", inputs, "--output", out, "--no-encrypt"], env)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn("ОТКРЫТОМ", r.stderr)


if __name__ == "__main__":
    unittest.main()
