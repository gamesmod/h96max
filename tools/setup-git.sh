#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

if ! command -v git-lfs >/dev/null 2>&1; then
  echo "Git LFS не установлен. Установите git-lfs и повторите запуск." >&2
  exit 1
fi

git lfs install --local
git config --local core.quotepath false
git config --local pull.ff only
git config --local fetch.prune true

echo "Git LFS и базовые настройки репозитория включены."
