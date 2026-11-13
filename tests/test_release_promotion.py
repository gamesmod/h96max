"""Поведенческая проверка выбора Actions-кандидата без сети и публикации."""
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class CandidatePromotionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="promotion-test-")
        self.addCleanup(self.tmp.cleanup)
        self.base = pathlib.Path(self.tmp.name)
        self.repo = self.base / "repo"
        self.repo.mkdir()
        (self.repo / "tools").mkdir()
        (self.repo / "docs/release-qa").mkdir(parents=True)
        (self.base / "bin").mkdir()
        shutil.copy(ROOT / "tools/check-candidate-run.py", self.repo / "tools/check-candidate-run.py")
        (self.repo / "build-sd.sh").write_text("ro.tvbox.release=9.9.9\n", encoding="utf-8")
        self.git("init", "-q")
        self.git("config", "user.name", "QA")
        self.git("config", "user.email", "qa@example.invalid")
        self.git("add", ".")
        self.git("commit", "-qm", "candidate")
        self.source = self.git("rev-parse", "HEAD")
        evidence = {"candidate": {"actions_run_id": 42, "source_commit": self.source}}
        (self.repo / "docs/release-qa/9.9.9.json").write_text(json.dumps(evidence), encoding="utf-8")
        self.git("add", "docs")
        self.git("commit", "-qm", "evidence")
        self.head = self.git("rev-parse", "HEAD")
        self.run_data = {"head_sha": self.source, "status": "completed", "conclusion": "success",
                         "path": ".github/workflows/release.yml", "event": "push",
                         "head_branch": "candidate/test"}
        fake_gh = self.base / "bin/gh"
        fake_gh.write_text('#!/bin/sh\ncat "$MOCK_RUN_JSON"\n', encoding="utf-8")
        fake_gh.chmod(0o755)
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        select = workflow.split("      - name: Select and verify candidate run\n", 1)[1]
        select = select.split("        run: |\n", 1)[1].split("\n      - name:", 1)[0]
        self.script = textwrap.dedent(select).replace("/tmp/candidate-selection", str(self.base / "selection"))
        self.script = self.script.replace("/tmp/candidate-run.json", str(self.base / "candidate-run.json"))

    def git(self, *args):
        result = subprocess.run(["git", *args], cwd=self.repo, capture_output=True, text=True, check=True)
        return result.stdout.strip()

    def run_selection(self):
        payload = self.base / "run.json"
        payload.write_text(json.dumps(self.run_data), encoding="utf-8")
        env = dict(os.environ, PATH=str(self.base / "bin") + os.pathsep + os.environ["PATH"],
                   MOCK_RUN_JSON=str(payload), GITHUB_REF_NAME="dev-v9.9.9", GITHUB_SHA=self.head,
                   GITHUB_REPOSITORY="example/project", GITHUB_OUTPUT=str(self.base / "output"))
        return subprocess.run(["bash", "-c", self.script], cwd=self.repo, env=env,
                              capture_output=True, text=True, check=False)

    def test_candidate_selection_accepts_same_source_and_evidence_only_commit(self):
        result = self.run_selection()
        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = (self.base / "output").read_text(encoding="utf-8")
        self.assertIn("run_id=42\n", outputs)
        self.assertIn("source_sha=" + self.source + "\n", outputs)

    def test_candidate_selection_rejects_failed_run(self):
        self.run_data["conclusion"] = "failure"
        self.assertNotEqual(self.run_selection().returncode, 0)

    def test_candidate_selection_rejects_changed_source(self):
        (self.repo / "build-sd.sh").write_text("ro.tvbox.release=9.9.9\n# changed\n", encoding="utf-8")
        self.git("add", "build-sd.sh")
        self.git("commit", "-qm", "changed source")
        self.head = self.git("rev-parse", "HEAD")
        self.assertNotEqual(self.run_selection().returncode, 0)


if __name__ == "__main__":
    unittest.main()
