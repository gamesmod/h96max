#!/bin/bash
# Приватные входы CI; право распространения бинарников проверяется отдельно.
set -euo pipefail
umask 077
R=$(cd "$(dirname "$0")/.." && pwd); cd "$R"
source "$R/tools/required-apps.sh"
tvbox_require_apks || { echo "Архив не создан: комплект приложений неполный" >&2; exit 1; }
OUT=${1:-/tmp/h96max-release-inputs.tar.zst}
[[ ! -e "$OUT" && ! -L "$OUT" ]] || { echo "Архив уже существует; перезапись запрещена" >&2; exit 1; }
command -v zerofree >/dev/null || { echo "Требуется zerofree для удаления остатков APK" >&2; exit 1; }
STAGE=$(mktemp -d "${TMPDIR:-/tmp}/h96max-ci-clean.XXXXXXXX")
trap 'rm -rf -- "$STAGE"' EXIT
python3 -I tools/prepare-ci-partitions.py dl/a12/Image/lp "$STAGE/dl/a12/Image/lp"
# Только входы build-sd.sh/setup-sdk.sh: не включать super.img повторно,
# распакованные ramdisk, DTB/DTS и результаты исследования исходной прошивки.
INPUTS=(
    dl/a12/MiniLoaderAll.bin
    dl/a12/Image/{boot,recovery,uboot,trust,dtbo,vbmeta,baseparameter}.img
    dl/a12/Image/lp/{system,system_ext,vendor,vendor_dlkm,odm,odm_dlkm,product}.img
    "${TVBOX_REQUIRED_APKS[@]/#/dl/apk/}"
    dl/webview/webview-arm64.apk dl/torrserver/TorrServer-android-arm64
    a9fw/config.txt a9fw/bcm4334.hcd
    a9fw/fw_bcm4334b1_ag.bin a9fw/fw_bcm4334b1_ag_apsta.bin
    stock/vfd/vfdsrv overlay-wifi/TvboxWifiOverlay.apk
)
CHECK_INPUTS=()
for f in "${INPUTS[@]}"; do
    if [[ "$f" == dl/a12/Image/lp/*.img ]]; then CHECK_INPUTS+=("$STAGE/$f"); else CHECK_INPUTS+=("$R/$f"); fi
    [[ -f "$f" && -s "$f" && ! -L "$f" ]] || { echo "Отсутствует обычный непустой build input: $f" >&2; exit 1; }
done
python3 -I tools/check-image-wifi-secrets.py "$STAGE"/dl/a12/Image/lp/*.img
# Проверять содержимое образов, а не только имена файлов. Полный PEM-блок
# закрытого ключа блокирует упаковку; значения никогда не печатаются.
# Это не исчерпывающий аудит токенов/персональных данных или сжатых вложений.
python3 - "${CHECK_INPUTS[@]}" <<'PY_CHECK_KEYS'
import mmap
import re
import sys

pem = re.compile(
    rb"-----BEGIN ((?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY)-----"
    rb"[\r\n]+[A-Za-z0-9+/=\r\n]+-----END \1-----"
)
found = False
for name in sys.argv[1:]:
    with open(name, "rb") as source, mmap.mmap(source.fileno(), 0, access=mmap.ACCESS_READ) as data:
        if pem.search(data):
            print(f"Упаковка запрещена: полный PEM-блок закрытого ключа в {name}", file=sys.stderr)
            found = True
if found:
    sys.exit(1)
PY_CHECK_KEYS
# noclobber не позволяет перезаписать существующий архив, включая симлинк.
TAR_INPUTS=()
for f in "${INPUTS[@]}"; do
    if [[ "$f" == dl/a12/Image/lp/*.img ]]; then TAR_INPUTS+=(-C "$STAGE" "$f"); else TAR_INPUTS+=(-C "$R" "$f"); fi
done
# Отчёт содержит исходные/очищенные хеши и список исключённых APK.
TAR_INPUTS+=(-C "$STAGE/dl/a12/Image/lp" cleanup-report.json)
(set -o noclobber; tar --zstd -cf - "${TAR_INPUTS[@]}" > "$OUT")
sha256sum "$OUT"
echo "Архив готов: $OUT"
