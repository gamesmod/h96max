#!/bin/bash
set -euo pipefail
export GIT_LFS_SKIP_SMUDGE=1
GIT=(git -c filter.lfs.smudge= -c filter.lfs.process= -c filter.lfs.required=false)

echo "last author: $("${GIT[@]}" log -1 --pretty=format:'%an <%ae>')"
echo "user.name=$(git config --get user.name || true)"
echo "user.email=$(git config --get user.email || true)"
git check-ignore -v release/notes/0.4.10.txt release/notes/0.4.11.txt || true
"${GIT[@]}" ls-files 'release/notes/*' | head
grep -n 'notes\|release' .gitignore 2>/dev/null | head || true

# Match previous commit author for this repo only for this commit via env
# (do not write git config).
author="$("${GIT[@]}" log -1 --pretty=format:'%an <%ae>')"
export GIT_AUTHOR_NAME="${author%% <*}"
export GIT_AUTHOR_EMAIL="${author##*<}"
export GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL%>}"
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
echo "using author $GIT_AUTHOR_NAME <$GIT_AUTHOR_EMAIL>"

# notes may be ignored — force-add tracked pattern like existing notes
git add -- build-sd.sh
git add -f -- release/notes/0.4.11.txt
"${GIT[@]}" diff --cached --stat
"${GIT[@]}" diff --cached --name-only

"${GIT[@]}" commit -m "$(cat <<'EOF'
Bump release candidate version to 0.4.11.

Include HM-7 main-thread fixes and LFS fetch fixes after published 0.4.10.
EOF
)"

"${GIT[@]}" status --short --branch
"${GIT[@]}" rev-parse HEAD
"${GIT[@]}" log -2 --oneline
