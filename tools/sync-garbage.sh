#!/bin/bash
# Mirror project sources (no secrets, vendor images, dumps or build outputs) into gamesmod/garbage:rk3318-tvbox/android,
# refuse on any secret value from secrets/*.env, then commit + push branch rk3318-tvbox. Usage: tools/sync-garbage.sh "сообщение"
set -euo pipefail
P=$(cd "$(dirname "$0")/.." && pwd); G=${GARBAGE:-$HOME/garbage}; D=$G/rk3318-tvbox/android
cd $G && git checkout -q rk3318-tvbox && git pull -q --rebase origin rk3318-tvbox 2>/dev/null || true
mkdir -p $D
rsync -a --delete --prune-empty-dirs \
  --exclude='/dl/' --exclude='/backup/' --exclude='/build/' --exclude='/sdk/' --exclude='/stock/' --exclude='/secrets/' \
  --exclude='/logs/' --exclude='/fblog/' --exclude='/a9fw/' --exclude='/a9cfg/' --exclude='/media/' \
  --exclude='*.log' --exclude='__pycache__/' --exclude='obj/' --exclude='out/' --exclude='gen/' --exclude='stubobj/' \
  --exclude='/monitor/*.csv' --exclude='/monitor/*.log' --exclude='/monitor/*.out' \
  --exclude='*.img' --exclude='*.bin' --exclude='*.gz' --exclude='*.zip' --exclude='*.apk' \
  --exclude='/ota/tvbox-flash' --include='/release/notes/' --exclude='/release/*/' \
  --filter='P a9cfg/' --filter='P stock.dts' $P/ $D/
mkdir -p $D/a9cfg && rsync -a --delete $P/a9cfg/kl/ $D/a9cfg/kl/ && cp $P/stock/stock.dts $D/
python3 -I - "$P" "$D" <<'PY'
import os,re,glob,sys
P,D=sys.argv[1:3]; vals=set()
for f in glob.glob(P+'/secrets/*.env'):
    for l in open(f,errors='ignore'):
        m=re.match(r'\s*(?:export\s+)?\w+=["\']?([^"\'\n]+)',l)
        if m and len(m.group(1).strip())>=6: vals.add(m.group(1).strip())
bad=[]
for root,_,fs in os.walk(D):
    for fn in fs:
        t=open(os.path.join(root,fn),errors='ignore').read()
        if ('PRIVATE'+' KEY') in t or any(v in t for v in vals): bad.append(os.path.join(root,fn))
if bad: print('ABORT: secrets in', *bad, sep='\n  '); sys.exit(1)
PY
cd $G && git add rk3318-tvbox
git diff --cached --quiet && { echo "нет изменений"; exit 0; }
git commit -q -m "rk3318-tvbox: ${1:-синхронизация исходников}" && git push -q -u origin rk3318-tvbox && git log --oneline -1
