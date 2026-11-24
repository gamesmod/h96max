#!/bin/bash
set -euo pipefail
test "$#" -eq 1 || { echo "Usage: bash adb-diagnose.sh SERIAL"; exit 2; }
A=(adb -s "$1")
"${A[@]}" get-state
for p in ro.build.version.release ro.build.version.sdk ro.product.cpu.abilist sys.boot_completed; do
  printf '%s=' "$p"; "${A[@]}" shell getprop "$p"
done
"${A[@]}" shell pm path org.tvbox.smarttubeproxy
"${A[@]}" shell 'ss -ltn 2>/dev/null | grep ":18765 " || true'
echo "Проверить вручную: 127.0.0.1 only, UI, видео, отказ и резерв. Скрипт не подтверждает сетевой тест."
