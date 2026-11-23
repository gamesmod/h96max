#!/bin/bash
# Build background voice control using Dicio's recognition service and the existing project key.
set -euo pipefail
cd "$(dirname "$0")"
R=$(cd ../.. && pwd)
source "$R/tools/app-version.sh"
J="$R/sdk/jdk/usr/lib/jvm/java-21-openjdk-amd64/bin"
X="$R/sdk/x/usr/lib/android-sdk"
AJ="$X/platforms/android-23/android.jar"
mkdir -p obj out
aapt package -f -M AndroidManifest.xml -I "$R/sdk/framework/framework-res.apk" "${TVBOX_AAPT_VERSION_ARGS[@]}" -F out/res.apk
"$J/javac" --release 8 -nowarn -encoding UTF-8 -classpath "$AJ" -d obj src/org/tvbox/voicebridge/*.java
"$J/java" -jar "$X/build-tools/debian/lib/dx.jar" --dex --min-sdk-version=23 --output=out/classes.dex obj
cp out/res.apk out/u.apk
(cd out && aapt add u.apk classes.dex >/dev/null)
python3 -I "$R/tools/v1sign.py" out/u.apk out/TvboxVoiceBridge.apk "$R/secrets/tvbox-key.pem" "$R/secrets/tvbox-cert.pem"
echo "built out/TvboxVoiceBridge.apk"
