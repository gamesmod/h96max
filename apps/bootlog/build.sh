#!/bin/bash
# Build out/TvboxBootLog.apk ("Журнал загрузки": boot log viewer). Toolchain: see ../../BUILD-AND-STATE.md
set -e
cd "$(dirname "$0")"
R=../..
J=$PWD/../../sdk/jdk/usr/lib/jvm/java-21-openjdk-amd64/bin
X=$R/sdk/x/usr/lib/android-sdk
AJ=$X/platforms/android-23/android.jar
rm -rf obj gen out; mkdir -p obj gen out
aapt package -f -m -J gen -M AndroidManifest.xml -S res -I $R/sdk/framework/framework-res.apk -F out/res.apk
$J/javac --release 8 -nowarn -encoding UTF-8 -classpath $AJ -d obj src/org/tvbox/bootlog/*.java gen/org/tvbox/bootlog/R.java
$J/java -jar $X/build-tools/debian/lib/dx.jar --dex --min-sdk-version=23 --output=out/classes.dex obj
cp out/res.apk out/u.apk
(cd out && aapt add u.apk classes.dex >/dev/null)
python3 -I $R/tools/v1sign.py out/u.apk out/TvboxBootLog.apk $R/secrets/tvbox-key.pem $R/secrets/tvbox-cert.pem
echo "built out/TvboxBootLog.apk"
