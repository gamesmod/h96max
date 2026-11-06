#!/bin/bash
# Упаковать полный комплект eMMC для чистой установки со стока через Rockchip Loader.
set -euo pipefail

R=$(cd "$(dirname "$0")/.." && pwd)
V=${1:?укажите версию, например 0.4.0}
D="$R/release/$V/emmc"
OUT="$R/release/Panadonik-$V-emmc-clean-install.zip"
SIG="$OUT.sig"
CERT="$R/release/Panadonik-$V-ota-cert.pem"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

test -s "$D/manifest.txt"
test -s "$D/release.txt"
test -s "$D/SHA256SUMS"
test -s "$R/secrets/ota-key.pem"
test -s "$R/secrets/ota-cert.pem"
(cd "$D" && sha256sum --check --quiet SHA256SUMS)
command -v zip >/dev/null || { echo "Не найдена утилита zip" >&2; exit 1; }
command -v openssl >/dev/null || { echo "Не найдена утилита openssl" >&2; exit 1; }

cd "$R"
zip -0 -q -r "$OUT" "release/$V/emmc" tools/rkusb.py docs/install-from-stock.md
unzip -tq "$OUT" >/dev/null
openssl x509 -in "$R/secrets/ota-cert.pem" -pubkey -noout > "$TMP/ota-public-key.pem"
openssl dgst -sha256 -sign "$R/secrets/ota-key.pem" -out "$SIG" "$OUT"
openssl dgst -sha256 -verify "$TMP/ota-public-key.pem" -signature "$SIG" "$OUT"
install -m 0644 "$R/secrets/ota-cert.pem" "$CERT"
(cd "$(dirname "$OUT")" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo "Комплект чистой установки: $OUT"
cat "$OUT.sha256"
