#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
R=../..
source "$R/tools/app-version.sh"
J=$PWD/../../sdk/jdk/usr/lib/jvm/java-21-openjdk-amd64/bin
X=$R/sdk/x/usr/lib/android-sdk
AJ=$X/platforms/android-23/android.jar
# Отдельный APK-кандидат; существующие ключи обязательны.
mkdir -p obj out assets
cp sources.json assets/sources.json
rm -f out/res.apk out/u.apk out/classes.dex out/TvboxSmartTubeProxy.apk
aapt package -f -M AndroidManifest.xml -A assets -I "$R/sdk/framework/framework-res.apk" "${TVBOX_AAPT_VERSION_ARGS[@]}" -F out/res.apk
"$J/javac" --release 8 -encoding UTF-8 -classpath "$AJ" -d obj src/org/tvbox/smarttubeproxy/*.java
"$J/java" -jar "$X/build-tools/debian/lib/dx.jar" --dex --min-sdk-version=24 --output=out/classes.dex obj
cp out/res.apk out/u.apk
(cd out && aapt add u.apk classes.dex >/dev/null)
python3 -I "$R/tools/v1sign.py" out/u.apk out/TvboxSmartTubeProxy.apk "$R/secrets/tvbox-key.pem" "$R/secrets/tvbox-cert.pem"
(cd out && sha256sum TvboxSmartTubeProxy.apk > SHA256SUMS)
