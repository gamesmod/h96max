#!/usr/bin/env bash
# Обновление EPG (XMLTV) и внедрение url-tvg в итоговый M3U
# Использование: bash tools/update-epg.sh [путь_к_m3u]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
EPG_URL="${EPG_URL:-https://iptv-org.github.io/epg/guides/ru.xml.gz}"
EPG_DIR="${EPG_DIR:-/tmp/epg}"
M3U="${1:-overlay/system/etc/tvbox/iptv.m3u}"
mkdir -p "$EPG_DIR"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
echo "=== EPG update $STAMP ==="

# Скачивание и распаковка
curl -fsSL -A "VLC/3.0.20" -o "$EPG_DIR/epg.xml.gz" "$EPG_URL" || { echo "Download failed"; exit 1; }
gunzip -f "$EPG_DIR/epg.xml.gz" || true
if [ -f "$EPG_DIR/epg.xml" ]; then
  mv "$EPG_DIR/epg.xml" "$EPG_DIR/epg-$STAMP.xml"
  ln -sfn "epg-$STAMP.xml" "$EPG_DIR/epg-latest.xml"
  echo "EPG saved: $EPG_DIR/epg-latest.xml"
else
  echo "No XML produced"; exit 1
fi

# Если передан M3U — добавить/обновить url-tvg
if [ -f "$M3U" ]; then
  TMP=$(mktemp)
  if head -1 "$M3U" | grep -q '^#EXTM3U'; then
    sed '1s|^#EXTM3U.*|#EXTM3U url-tvg="file:///tmp/epg/epg-latest.xml"|' "$M3U" > "$TMP"
  else
    echo '#EXTM3U url-tvg="file:///tmp/epg/epg-latest.xml"' > "$TMP"
    cat "$M3U" >> "$TMP"
  fi
  mv "$TMP" "$M3U"
  echo "M3U updated with url-tvg: $M3U"
fi
echo "Done"
