#!/bin/bash
# Локальная пересборка очищенных LP-разделов из полного стока (не для публикации).
# Обычный путь сборки: tools/prepare-build-inputs.sh (LFS или --update update.img).
# Этот скрипт — ручная пересборка LP для обновления Git LFS; CI берёт уже очищенные входы.
# Не включать super.img, secrets/, *.pem и сторонние APK.
set -euo pipefail
umask 077
R=$(cd "$(dirname "$0")/.." && pwd); cd "$R"
SOURCE=${1:-"$R/dl/a12/Image/lp"}
DEST=${2:-}
command -v zerofree >/dev/null || { echo "Требуется zerofree" >&2; exit 1; }
command -v resize2fs >/dev/null || { echo "Требуется resize2fs" >&2; exit 1; }
[[ -d "$SOURCE" ]] || { echo "Нет каталога исходных разделов: $SOURCE" >&2; exit 1; }
for p in system system_ext vendor vendor_dlkm odm odm_dlkm product; do
    [[ -f "$SOURCE/$p.img" && -s "$SOURCE/$p.img" ]] || { echo "Нет $SOURCE/$p.img" >&2; exit 1; }
done
if [[ -z "$DEST" ]]; then
    DEST=$(mktemp -d "${TMPDIR:-/tmp}/h96max-ci-clean.XXXXXXXX")
    echo "DEST=$DEST"
fi
[[ ! -e "$DEST" ]] || { echo "Каталог назначения уже существует: $DEST" >&2; exit 1; }
python3 -I tools/prepare-ci-partitions.py "$SOURCE" "$DEST"
python3 -I tools/check-image-wifi-secrets.py "$DEST"/*.img
# Проверять содержимое образов, а не только имена файлов. Полный PEM-блок
# закрытого ключа блокирует упаковку; значения никогда не печатаются.
python3 - "$DEST"/*.img <<'PY_CHECK_KEYS'
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
echo "Очищенные разделы готовы: $DEST"
echo "Отчёт: $DEST/cleanup-report.json"
ls -la "$DEST"
