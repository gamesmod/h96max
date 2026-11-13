#!/usr/bin/env python3
"""Проверить происхождение Actions run перед продвижением его артефакта."""
import argparse
import json
import pathlib
import re
import sys


def valid_candidate(run, source_sha):
    if not isinstance(run, dict) or not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        return False
    if (run.get("head_sha") != source_sha or run.get("status") != "completed" or
            run.get("conclusion") != "success" or run.get("path") != ".github/workflows/release.yml"):
        return False
    event = run.get("event")
    branch = run.get("head_branch")
    return event in ("schedule", "workflow_dispatch") or (
        event == "push" and isinstance(branch, str) and branch.startswith("candidate/"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True)
    parser.add_argument("--source-sha", required=True)
    args = parser.parse_args()
    try:
        run = json.loads(pathlib.Path(args.run).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        print("Не удалось прочитать данные Actions run", file=sys.stderr)
        return 1
    if not valid_candidate(run, args.source_sha):
        print("Actions run не является успешной сборкой указанного кандидата", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
