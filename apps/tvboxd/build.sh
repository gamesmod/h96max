#!/bin/bash
# Build out/tvboxd.jar (dex jar for app_process, started by init as service tvboxd). Toolchain: see ../../BUILD-AND-STATE.md
set -e
cd "$(dirname "$0")"
J=$PWD/../../sdk/jdk/usr/lib/jvm/java-21-openjdk-amd64/bin
X=../../sdk/x/usr/lib/android-sdk
AJ=$X/platforms/android-23/android.jar
rm -rf obj out; mkdir -p obj out
$J/javac --release 8 -nowarn -encoding UTF-8 -classpath $AJ -d obj src/org/tvbox/daemon/*.java
# no lambdas/method refs: dx emits invoke-custom, which fails under app_process here
$J/java -jar $X/build-tools/debian/lib/dx.jar --dex --min-sdk-version=24 --output=out/tvboxd.jar obj
echo "built out/tvboxd.jar"
