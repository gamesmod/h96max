#!/bin/bash
# Сборка только исходников; не скачивать готовые SmartTube APK.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
test -n "$SDK"
for f in "$SDK/platforms/android-34/android.jar" "$SDK/build-tools/30.0.3/aapt" "$SDK/ndk/21.0.6113669/source.properties"; do test -e "$f" || { echo "Нет SDK: $f"; exit 1; }; done
SHA=$(python3 -c 'import json; print(json.load(open("source.lock.json"))["commit"])')
URL=$(python3 -c 'import json; print(json.load(open("source.lock.json"))["repository"])')
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
git -C "$W" init -q
git -C "$W" remote add origin "$URL"
git -C "$W" fetch --depth=1 origin "$SHA"
git -C "$W" checkout --detach FETCH_HEAD
test "$(git -C "$W" rev-parse HEAD)" = "$SHA"
git -C "$W" -c protocol.file.allow=never submodule update --init --recursive
python3 "$ROOT/patch_source.py" "$W"
git -C "$W" -c core.whitespace=cr-at-eol diff --check
mkdir -p out
git -C "$W" submodule status --recursive > out/SUBMODULES.txt
git -C "$W" diff --binary > out/PANADONIK.patch
cp "$W/LICENSE" out/LICENSE.smarttube
cp source.lock.json out/source.lock.json
(cd "$W" && bash gradlew :smarttubetv:assembleStfdroidRelease --no-daemon --console=plain --max-workers=2)
python3 "$ROOT/verify_apks.py" "$W/smarttubetv/build/outputs/apk/stfdroid/release" "$ROOT/out" "$SDK/build-tools/30.0.3"
