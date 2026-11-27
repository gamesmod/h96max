#!/usr/bin/env python3
"""Fail if a project .codex/config.toml contains machine-local keys.

Codex ignores model_provider/model_providers in project config, but a project
`model` would override a named profile. This check keeps the project file
MCP-only without modifying it.
"""

from __future__ import annotations

import sys
import tomllib
from pathlib import Path

FORBIDDEN = {
    "model",
    "model_provider",
    "model_providers",
    "profile",
    "profiles",
}


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else ".codex/config.toml")
    if not path.is_file():
        print(f"{path}: not found", file=sys.stderr)
        return 2

    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except tomllib.TOMLDecodeError as exc:
        print(f"{path}: invalid TOML: {exc}", file=sys.stderr)
        return 2

    found = sorted(FORBIDDEN & data.keys())
    if found:
        print(
            f"{path}: forbidden project-level keys: {', '.join(found)}",
            file=sys.stderr,
        )
        return 1

    print(f"{path}: OK (no machine-local model/provider keys)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
