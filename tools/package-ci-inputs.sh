#!/bin/bash
# Собрать приватный архив зависимостей для GitHub Actions без ключей подписи и Wi-Fi настроек.
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd); cd "$R"
source "$R/tools/required-apps.sh"
tvbox_require_apks || { echo "Архив не создан: комплект приложений неполный" >&2; exit 1; }
OUT=${1:-/tmp/h96max-release-inputs.tar.zst}
for f in dl/a12/Image/lp/system.img dl/a12/Image/lp/vendor.img dl/a12/MiniLoaderAll.bin \
         dl/apk/com.tvlauncher.apk dl/webview/webview-arm64.apk dl/torrserver/TorrServer-android-arm64 \
         a9fw/config.txt a9fw/bcm4334.hcd a9fw/fw_bcm4334b1_ag.bin a9fw/fw_bcm4334b1_ag_apsta.bin \
         stock/vfd/vfdsrv overlay-wifi/TvboxWifiOverlay.apk; do
    [ -f "$f" ] || { echo "Не найден build input: $f" >&2; exit 1; }
done
# Включаем остальные файлы A12 Image, нужные загрузчику и recovery.
tar --zstd -cf "$OUT" \
    dl/a12/Image dl/a12/MiniLoaderAll.bin \
    "${TVBOX_REQUIRED_APKS[@]/#/dl/apk/}" dl/webview/webview-arm64.apk dl/torrserver/TorrServer-android-arm64 \
    a9fw/config.txt a9fw/bcm4334.hcd a9fw/fw_bcm4334b1_ag.bin a9fw/fw_bcm4334b1_ag_apsta.bin \
    stock/vfd/vfdsrv overlay-wifi/TvboxWifiOverlay.apk
sha256sum "$OUT"
echo "Архив готов: $OUT"
