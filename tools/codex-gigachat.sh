#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
codex_home="${CODEX_HOME:-$HOME/.codex}"
default_profile="${CODEX_GIGACHAT_PROFILE:-gigachat}"
codex_bin="${CODEX_BIN:-codex}"

usage() {
  cat <<'EOF'
Usage:
  tools/codex-gigachat.sh install [profile] [--force]
  tools/codex-gigachat.sh check [profile]
  tools/codex-gigachat.sh check-project [path]
  tools/codex-gigachat.sh run [codex args...]
  tools/codex-gigachat.sh notools [codex args...]
  tools/codex-gigachat.sh delegate < sanitized-task.txt

Profiles:
  gigachat           full tools, read-only by default
  gigachat-notools   no shell/goals/apps/plugins/subagents, read-only

Environment:
  CODEX_HOME          Codex home (default: ~/.codex)
  CODEX_BIN           Codex executable (default: codex)
  GIGACHAT_MODEL      model passed to Codex (default: GigaChat-2-Max)
  GPT2GIGA_BASE_URL   optional base_url override for provider "gigachat"
  GPT2GIGA_API_KEY    gateway key; required for run/notools, never printed
EOF
}

profile_path() {
  printf '%s/%s.config.toml\n' "$codex_home" "$1"
}

install_profile() {
  local profile="$1"
  local force="${2:-}"
  local src="$repo_root/.codex/profiles/${profile}.config.toml"
  local dst
  dst="$(profile_path "$profile")"

  [[ -f "$src" ]] || { echo "missing profile source: $src" >&2; exit 1; }
  mkdir -p "$codex_home"

  if [[ -e "$dst" ]]; then
    if [[ "$force" != "--force" ]]; then
      echo "refusing to overwrite $dst (use --force)" >&2
      exit 2
    fi
    cp -p "$dst" "$dst.bak.$(date +%Y%m%dT%H%M%S)"
  fi

  install -m 600 "$src" "$dst"
  echo "installed $dst"
}

check_profile() {
  local profile="$1"
  local dst
  dst="$(profile_path "$profile")"
  [[ -f "$dst" ]] || { echo "profile not installed: $dst" >&2; exit 2; }

  local log
  log="$(mktemp "${TMPDIR:-/tmp}/codex-gigachat-check.XXXXXX")"

  if env -u GPT2GIGA_API_KEY "$codex_bin" --profile "$profile" --strict-config exec \
      --skip-git-repo-check --sandbox read-only --ephemeral "Reply OK" \
      >"$log" 2>&1; then
    echo "unexpected success without GPT2GIGA_API_KEY" >&2
    rm -f "$log"
    return 1
  fi

  if grep -q "unknown configuration field" "$log"; then
    cat "$log" >&2
    rm -f "$log"
    return 1
  fi

  if ! grep -q "Missing environment variable" "$log" || ! grep -q "GPT2GIGA_API_KEY" "$log"; then
    cat "$log" >&2
    rm -f "$log"
    return 1
  fi

  rm -f "$log"
  echo "strict-config OK: profile parsed; GPT2GIGA_API_KEY is intentionally unset"
}

check_project() {
  local path="${1:-$repo_root/.codex/config.toml}"
  python3 "$repo_root/tools/check-codex-project-config.py" "$path"
}

run_profile() {
  local profile="$1"
  shift

  [[ -n "${GPT2GIGA_API_KEY:-}" ]] || {
    echo "GPT2GIGA_API_KEY is not set" >&2
    exit 1
  }

  local args=(
    --profile "$profile"
    --strict-config
    -c model_provider=gigachat
    -c "model=${GIGACHAT_MODEL:-GigaChat-2-Max}"
  )

  if [[ -n "${GPT2GIGA_BASE_URL:-}" ]]; then
    args+=(-c "model_providers.gigachat.base_url=${GPT2GIGA_BASE_URL}")
  fi

  "$codex_bin" "${args[@]}" "$@"
}

delegate_task() (
  if [[ -t 0 ]]; then
    echo "delegate requires a sanitized task on stdin" >&2
    exit 64
  fi

  local delegate_workspace
  delegate_workspace="$(mktemp -d "${TMPDIR:-/tmp}/codex-gigachat-delegate.XXXXXX")"
  trap 'rmdir -- "$delegate_workspace" 2>/dev/null || true' EXIT

  run_profile "gigachat-notools" exec \
    --cd "$delegate_workspace" \
    --skip-git-repo-check \
    --sandbox read-only \
    --ephemeral \
    -
)

cmd="${1:-}"
if [[ -z "$cmd" ]]; then
  usage
  exit 64
fi
shift || true

case "$cmd" in
  install)
    profile=""
    force=""
    for arg in "$@"; do
      case "$arg" in
        --force) force="--force" ;;
        *) profile="$arg" ;;
      esac
    done
    if [[ -n "$profile" ]]; then
      install_profile "$profile" "$force"
    else
      install_profile "gigachat" "$force"
      install_profile "gigachat-notools" "$force"
    fi
    ;;
  check)
    check_profile "${1:-$default_profile}"
    ;;
  check-project)
    check_project "${1:-}"
    ;;
  run)
    run_profile "$default_profile" "$@"
    ;;
  notools)
    run_profile "gigachat-notools" "$@"
    ;;
  delegate)
    [[ "$#" -eq 0 ]] || { usage >&2; exit 64; }
    delegate_task
    ;;
  *)
    usage
    exit 64
    ;;
esac
