#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
R=../..
source "$R/tools/app-version.sh"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
test -n "$SDK" || { echo "Укажите ANDROID_HOME"; exit 1; }
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-32/android.jar"
for f in "$BT/aapt" "$BT/d8" "$BT/apksigner" "$AJ"; do test -e "$f" || { echo "Отсутствует SDK-компонент: $f"; exit 1; }; done
test -s "$R/secrets/tvbox-key.pem" && test -s "$R/secrets/tvbox-cert.pem"
# Используем современный D8 для Java 8 lambda; версия и ключи остаются общими с Panadonik.
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/classes" "$W/dex" "$W/assets" out
cp sources.json "$W/assets/sources.json"
"$BT/aapt" package -f -M AndroidManifest.xml -A "$W/assets" -I "$AJ" "${TVBOX_AAPT_VERSION_ARGS[@]}" -F "$W/res.apk"
javac --release 8 -encoding UTF-8 -classpath "$AJ" -d "$W/classes" src/org/tvbox/smarttubeproxy/*.java
jar cf "$W/classes.jar" -C "$W/classes" .
"$BT/d8" --release --lib "$AJ" --min-api 24 --output "$W/dex" "$W/classes.jar"
cp "$W/res.apk" "$W/u.apk"
(cd "$W/dex" && "$BT/aapt" add "$W/u.apk" classes.dex >/dev/null)
python3 -I "$R/tools/v1sign.py" "$W/u.apk" "$W/TvboxSmartTubeProxy.apk" "$R/secrets/tvbox-key.pem" "$R/secrets/tvbox-cert.pem"
"$BT/apksigner" verify --verbose "$W/TvboxSmartTubeProxy.apk"
cp "$W/TvboxSmartTubeProxy.apk" out/
(cd out && sha256sum TvboxSmartTubeProxy.apk > SHA256SUMS)
