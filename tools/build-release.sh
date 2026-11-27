#!/bin/bash
# Full release build from scratch (version = ro.tvbox.release in build-sd.sh):
#   tools/build-release.sh            # needs release/notes/VERSION.txt (changelog, shown in "Установка")
# Steps: stock inputs (extract-vendor-blobs / LFS) → toolchain → own apps/jars → OTA helper →
#        clean build/ → eMMC image → release/VERSION/emmc + signed OTA.
# Стоковые входы: Git LFS или TVBOX_UPDATE_IMG=/path/update.img (см. tools/prepare-build-inputs.sh).
# Do not edit build-sd.sh or apps/ while it runs.
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd); cd $R
V=$(grep '^ro.tvbox.release=' build-sd.sh | cut -d= -f2)
NOTES=release/notes/$V.txt
[ -f $NOTES ] || { echo "missing $NOTES"; exit 1; }
[ -e release/$V ] && { echo "release/$V already exists"; exit 1; }
for f in secrets/tvbox-key.pem secrets/tvbox-cert.pem secrets/ota-key.pem secrets/ota-cert.pem; do
    [ -f $f ] || { echo "missing $f"; exit 1; }; done
step() { echo; echo "=== [$(date +%T)] $*"; }

step "1/6 stock inputs"
# Git LFS по умолчанию; из сырого стока: TVBOX_UPDATE_IMG=… или tools/prepare-build-inputs.sh --update …
tools/prepare-build-inputs.sh
step "2/6 toolchain"
tools/setup-sdk.sh --check || tools/setup-sdk.sh
python3 -I tools/prepare-dicio-assets.py --fetch
step "3/6 own apps"
for app in antivirus bootlog bootscreen clean files installer iptv links power tvboxd voicebridge; do
    echo "-- apps/$app/build.sh"
    bash "apps/$app/build.sh"
done
for a in antivirus/TvboxAntivirus bootlog/TvboxBootLog clean/TvboxClean files/TvboxFiles installer/TvboxInstaller iptv/TvboxIptv \
         links/TvboxLinks power/TvboxPower voicebridge/TvboxVoiceBridge; do
    ls apps/${a%%/*}/out/*.apk >/dev/null 2>&1 || { echo "no apk from apps/${a%%/*}"; exit 1; }; done
[ -f apps/tvboxd/out/tvboxd.jar ]
[ -f apps/bootscreen/out/tvbox-bootscreen.jar ]
step "4/6 OTA helper"
aarch64-linux-gnu-gcc -static -O2 -Wall -o ota/tvbox-flash ota/tvbox-flash.c && aarch64-linux-gnu-strip ota/tvbox-flash
step "5/6 eMMC image"
rm -rf build
MEDIA=emmc ./build-sd.sh
step "6/6 eMMC release files"
O=build/out-emmc
D=release/$V/emmc; mkdir -p $D
cp -r $O/manifest.txt $O/release.txt $O/regions $D/
(cd $D && find . -type f ! -name SHA256SUMS | sort | xargs sha256sum > SHA256SUMS)
tools/release-ota.sh $V $NOTES
tools/package-clean-install.sh "$V"
step "release metadata and Obtainium APKs"
cp release/updates/emmc/latest.txt release/updates/emmc/latest-emmc.txt
(cd release/updates/emmc && sha256sum "tvbox-update-$V-emmc.zip" latest-emmc.txt > SHA256SUMS)
mkdir -p release/obtainium
cp apps/antivirus/out/TvboxAntivirus.apk release/obtainium/Panadonik-antivirus.apk
cp apps/bootlog/out/TvboxBootLog.apk release/obtainium/Panadonik-bootlog.apk
cp apps/clean/out/TvboxClean.apk release/obtainium/Panadonik-performance.apk
cp apps/files/out/TvboxFiles.apk release/obtainium/Panadonik-files.apk
cp apps/installer/out/TvboxInstaller.apk release/obtainium/Panadonik-installer.apk
cp apps/iptv/out/TvboxIptv.apk release/obtainium/Panadonik-iptv.apk
cp apps/links/out/TvboxLinks.apk release/obtainium/Panadonik-links.apk
cp apps/power/out/TvboxPower.apk release/obtainium/Panadonik-power.apk
step "done: release/$V, release/updates"
