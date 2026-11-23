#!/usr/bin/env bash
# Автоматическая проверка IPTV (merge + verify ≥1080p + public release check)
# Запуск: bash tools/iptv-check-auto.sh
# Выходы в /tmp/iptv-check/ (или $IPTV_OUT_DIR)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
OUT=${IPTV_OUT_DIR:-/tmp/iptv-check}
mkdir -p "$OUT"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
LOG="$OUT/iptv-check-$STAMP.log"

echo "=== IPTV check started $STAMP ===" | tee "$LOG"

echo "1. Static check" | tee -a "$LOG"
if ! PYTHONDONTWRITEBYTECODE=1 python3 -B tools/check-public-release.py --sources tools/iptv-sources.txt >>"$LOG" 2>&1; then
  echo "STATIC FAIL" | tee -a "$LOG"
  exit 1
fi

echo "2. Merge" | tee -a "$LOG"
if ! python3 -I tools/iptv-merge.py --sources tools/iptv-sources.txt --out "$OUT/iptv-candidate.m3u" >>"$LOG" 2>&1; then
  echo "MERGE FAIL" | tee -a "$LOG"
  exit 1
fi

echo "3. Verify" | tee -a "$LOG"
if ! python3 -I tools/iptv-verify.py "$OUT/iptv-candidate.m3u" \
    --report "$OUT/iptv.csv" \
    --history "$OUT/history.jsonl" \
    --scope global >>"$LOG" 2>&1; then
  echo "VERIFY FAIL" | tee -a "$LOG"
  exit 1
fi

echo "4. Final public check (min-adult 20)" | tee -a "$LOG"
if ! python3 -I tools/check-public-release.py \
    --sources tools/iptv-sources.txt \
    --playlist "$OUT/iptv-candidate.m3u" \
    --min-adult 20 >>"$LOG" 2>&1; then
  echo "FINAL CHECK FAIL (adult category / count)" | tee -a "$LOG"
  echo "STATUS: FAIL" | tee -a "$LOG"
  exit 2
fi

echo "STATUS: PASS" | tee -a "$LOG"
echo "Reports in $OUT" | tee -a "$LOG"
