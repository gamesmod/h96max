#!/bin/bash
# Скачивает сторонние входы сборки по tools/third-party-apps.json и сверяет SHA-256.
# Стоковые разделы A12 сюда не входят — они в Git LFS (dl/a12, a9fw, stock/vfd, overlay-wifi).
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd); cd "$R"
MANIFEST=${1:-"$R/tools/third-party-apps.json"}
[[ -f "$MANIFEST" ]] || { echo "Нет манифеста: $MANIFEST" >&2; exit 1; }
command -v curl >/dev/null || { echo "Требуется curl" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "Требуется sha256sum" >&2; exit 1; }
command -v git >/dev/null || { echo "Требуется git" >&2; exit 1; }
command -v git-lfs >/dev/null || command -v git >/dev/null
python3 -I - "$MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

manifest_path = pathlib.Path(sys.argv[1])
root = manifest_path.resolve().parent.parent
data = json.loads(manifest_path.read_text())
items = data.get("items") or data.get("apps")
if not isinstance(items, list) or not items:
    raise SystemExit("third-party manifest has no items")


def sha256(path: pathlib.Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fetch_http(url: str, dest: pathlib.Path) -> None:
    subprocess.run(
        [
            "curl", "--fail", "--location", "--retry", "3",
            "--silent", "--show-error",
            "--output", str(dest),
            url,
        ],
        check=True,
    )


def fetch_git_lfs(spec: dict, dest: pathlib.Path) -> None:
    """Fetch one LFS-tracked path at a pinned commit.

    Partial clone with LFS smudge disabled leaves a pointer and cannot scan the
    LFS tree (missing git objects). Clone with smudge enabled, fetch the
    commit, then check it out so git-lfs materializes the blob.
    """
    repo = spec["repo"]
    commit = spec["commit"]
    path = spec["path"]
    if not shutil.which("git-lfs") and subprocess.run(
        ["git", "lfs", "version"], capture_output=True
    ).returncode != 0:
        raise SystemExit("git-lfs required to fetch " + path)
    with tempfile.TemporaryDirectory(prefix="tvbox-gitfetch-") as tmp:
        tmp_path = pathlib.Path(tmp)
        src = tmp_path / "src"
        subprocess.run(
            ["git", "clone", "--no-checkout", repo, str(src)],
            check=True,
            cwd=tmp_path,
        )
        subprocess.run(
            ["git", "fetch", "--depth", "1", "origin", commit],
            check=True,
            cwd=src,
        )
        subprocess.run(["git", "checkout", commit], check=True, cwd=src)
        blob = src / path
        if not blob.is_file() or blob.stat().st_size < 1024:
            raise SystemExit(f"git checkout left empty/missing file for {path}")
        if b"git-lfs.github.com/spec/v1" in blob.read_bytes()[:200]:
            raise SystemExit(f"git-lfs did not smudge {path}")
        shutil.copyfile(blob, dest)


ok = 0
for item in items:
    dest = root / item["dest"]
    expect = item["sha256"].lower()
    if len(expect) != 64 or any(c not in "0123456789abcdef" for c in expect):
        raise SystemExit(f"bad sha256 for {item['id']}")
    if dest.is_file() and dest.stat().st_size > 0 and sha256(dest) == expect:
        print(f"OK cached {item['id']} -> {item['dest']}")
        ok += 1
        continue
    dest.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix="tvbox-fetch-", dir=str(dest.parent), delete=False) as tmp:
        tmp_path = pathlib.Path(tmp.name)
    try:
        if "git" in item:
            print(f"GIT {item['git']['repo']}@{item['git']['commit']}:{item['git']['path']}", flush=True)
            fetch_git_lfs(item["git"], tmp_path)
        else:
            print(f"GET {item['url']}", flush=True)
            fetch_http(item["url"], tmp_path)
        got = sha256(tmp_path)
        if got != expect:
            raise SystemExit(
                f"SHA-256 mismatch for {item['id']}: got {got}, expected {expect}"
            )
        tmp_path.replace(dest)
        if item["dest"].endswith("TorrServer-android-arm64"):
            dest.chmod(0o755)
        else:
            dest.chmod(0o644)
        print(f"OK fetched {item['id']} -> {item['dest']}")
        ok += 1
    finally:
        if tmp_path.exists():
            tmp_path.unlink()

print(f"third-party inputs ready: {ok}/{len(items)}")
PY
