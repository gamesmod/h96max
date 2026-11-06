#!/bin/bash
# Full release build from scratch (version = ro.tvbox.release in build-sd.sh):
#   tools/build-release.sh            # needs release/notes/VERSION.txt (changelog, shown in "Установка")
# Steps: toolchain check → own apps/jars (apps/*/build.sh) → OTA helper (ota/tvbox-flash) → clean build/ →
#        eMMC image → release/VERSION/emmc with SHA256SUMS → signed eMMC OTA package release/updates/emmc.
# Do not edit build-sd.sh or apps/ while it runs.
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd); cd $R
V=$(grep '^ro.tvbox.release=' build-sd.sh | cut -d= -f2)
NOTES=release/notes/$V.txt
[ -f $NOTES ] || { echo "missing $NOTES"; exit 1; }
[ -e release/$V ] && { echo "release/$V already exists"; exit 1; }
for f in secrets/tvbox-key.pem secrets/tvbox-cert.pem secrets/ota-key.pem secrets/ota-cert.pem secrets/wifi.env; do
    [ -f $f ] || { echo "missing $f"; exit 1; }; done
step() { echo; echo "=== [$(date +%T)] $*"; }

step "1/5 toolchain"
tools/setup-sdk.sh --check || tools/setup-sdk.sh
step "2/5 own apps"
for app in antivirus bootlog bootscreen clean installer iptv links power tvboxd; do
    echo "-- apps/$app/build.sh"
    bash "apps/$app/build.sh"
done
for a in antivirus/TvboxAntivirus bootlog/TvboxBootLog clean/TvboxClean installer/TvboxInstaller iptv/TvboxIptv \
         links/TvboxLinks power/TvboxPower; do
    ls apps/${a%%/*}/out/*.apk >/dev/null 2>&1 || { echo "no apk from apps/${a%%/*}"; exit 1; }; done
[ -f apps/tvboxd/out/tvboxd.jar ]
[ -f apps/bootscreen/out/tvbox-bootscreen.jar ]
step "3/5 OTA helper"
aarch64-linux-gnu-gcc -static -O2 -Wall -o ota/tvbox-flash ota/tvbox-flash.c && aarch64-linux-gnu-strip ota/tvbox-flash
step "4/5 eMMC image"
rm -rf build
MEDIA=emmc ./build-sd.sh
step "5/5 eMMC release files"
O=build/out-emmc
D=release/$V/emmc; mkdir -p $D
cp -r $O/manifest.txt $O/release.txt $O/regions $D/
(cd $D && find . -type f ! -name SHA256SUMS | sort | xargs sha256sum > SHA256SUMS)
tools/release-ota.sh $V $NOTES
step "done: release/$V, release/updates"
