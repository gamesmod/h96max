#!/bin/bash
# Build out/tvbox-bootscreen.jar (dex jar for app_process). Toolchain: see ../../BUILD-AND-STATE.md
set -e
cd "$(dirname "$0")"
J=$PWD/../../sdk/jdk/usr/lib/jvm/java-21-openjdk-amd64/bin
X=../../sdk/x/usr/lib/android-sdk
AJ=$X/platforms/android-23/android.jar
rm -rf obj stubobj out; mkdir -p obj stubobj out
$J/javac --release 8 -nowarn -encoding UTF-8 -classpath $AJ -d stubobj stubs/android/view/*.java
$J/javac --release 8 -nowarn -encoding UTF-8 -classpath stubobj:$AJ -d obj src/org/tvbox/bootscreen/*.java
# no lambdas/method refs: dx emits invoke-custom, which fails under app_process here
$J/java -jar $X/build-tools/debian/lib/dx.jar --dex --min-sdk-version=24 --output=out/tvbox-bootscreen.jar obj
