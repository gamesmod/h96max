#!/bin/bash
set -e
export GIT_LFS_SKIP_SMUDGE=1
GIT=(git -c filter.lfs.smudge= -c filter.lfs.process= -c filter.lfs.required=false)
for i in $(seq 1 40); do
  if ! pgrep -f 'git commit' >/dev/null 2>&1 && ! test -f .git/index.lock; then
    echo "clear after $i"
    break
  fi
  echo "waiting $i"
  sleep 3
done
"${GIT[@]}" log -3 --oneline
"${GIT[@]}" status --short --branch -- build-sd.sh release/notes/0.4.11.txt .cursor/agents/ || true
grep -n 'ro.tvbox.release=' build-sd.sh | head -1
test -f release/notes/0.4.11.txt && echo notes_ok
