#!/usr/bin/env bash
set -euo pipefail
umask 077
R="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 -B "$R/aosp/scripts/panadonik.py" "$@"
