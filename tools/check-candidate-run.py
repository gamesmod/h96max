#!/usr/bin/env python3
"""Проверить происхождение Actions run перед продвижением его артефакта."""
import argparse
import json
import pathlib
import re
import sys
import textwrap


EXAMPLE_INVOCATION = (
    "python3 -I tools/check-candidate-run.py "
    "--run /path/to/actions-run.json --source-sha <40-hex-commit>"
)


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


def build_parser():
    parser = argparse.ArgumentParser(
        description="Проверить происхождение Actions run перед продвижением его артефакта.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent(f"""\
            Examples:
              {EXAMPLE_INVOCATION}
              gh run view 123456789 --json databaseId,headSha,status,conclusion,path,event,headBranch \\
                > /tmp/candidate-run.json
              python3 -I tools/check-candidate-run.py \\
                --run /tmp/candidate-run.json \\
                --source-sha "$(git rev-parse HEAD)"

            Notes:
              Допустимы только успешные completed-запуски release.yml из schedule,
              workflow_dispatch или push в ветку candidate/*.
              Повторный запуск идемпотентен (только чтение JSON).
            """),
    )
    parser.add_argument(
        "--run",
        required=True,
        help="JSON одного Actions run (например, вывод gh run view --json ...)",
    )
    parser.add_argument(
        "--source-sha",
        required=True,
        help="ожидаемый 40-символьный hex SHA исходного коммита кандидата",
    )
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    path = pathlib.Path(args.run)
    try:
        run = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"Не удалось прочитать данные Actions run: {error}", file=sys.stderr)
        print(f"  {EXAMPLE_INVOCATION}", file=sys.stderr)
        return 1
    if not valid_candidate(run, args.source_sha):
        print("Actions run не является успешной сборкой указанного кандидата", file=sys.stderr)
        print(
            "  Нужны: head_sha==source-sha, status=completed, conclusion=success, "
            "path=.github/workflows/release.yml, event=schedule|workflow_dispatch "
            "или push в candidate/*",
            file=sys.stderr,
        )
        print(f"  {EXAMPLE_INVOCATION}", file=sys.stderr)
        return 1
    run_id = run.get("databaseId") or run.get("id") or "unknown"
    print(f"candidate_run_ok: {run_id}")
    print(f"head_sha: {run.get('head_sha')}")
    print(f"event: {run.get('event')}")
    if isinstance(run.get("head_branch"), str):
        print(f"head_branch: {run['head_branch']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
