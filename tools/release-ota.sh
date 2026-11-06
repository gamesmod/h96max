#!/bin/bash
# Signed OTA package for the eMMC build from the last build (MEDIA=emmc ./build-sd.sh):
#   tools/release-ota.sh VERSION CHANGELOG.txt
# -> release/updates/emmc/tvbox-update-VERSION-emmc.zip + latest.txt (served by tools/serve-updates.sh,
#    or copied to a USB stick / SD card for recovery's "Install update from SD card")
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd); V=$1; CL=$2
media=emmc
O=$R/build/out-emmc
read rel date m < $O/release.txt
[ "$rel" = "$V" ] && [ "$m" = "$media" ] || { echo "$O is release '$rel' ($m), not $V ($media)"; exit 1; }
D=$R/release/updates/$media; mkdir -p $D
F=tvbox-update-$V-$media.zip
python3 -I $R/tools/mkota.py $O "$V" $CL $D/$F $R/secrets/ota-key.pem $R/secrets/ota-cert.pem
notes=$(sed ':a;N;$!ba;s/\n/\\n/g' $CL)
printf 'version=%s\nfile=%s\nsha256=%s\nsize=%s\nnotes=%s\n' "$V" $F $(sha256sum $D/$F | cut -d' ' -f1) $(stat -c %s $D/$F) "$notes" > $D/latest.txt.new
mv $D/latest.txt.new $D/latest.txt
echo "== $media"; cat $D/latest.txt
