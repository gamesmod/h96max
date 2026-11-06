#!/bin/bash
# УСТАРЕЛО: rkdeveloptool 1.32 не читает eMMC за 32 МиБ — используйте tools/rkusb.py flash
# Clean install of an eMMC release over USB (vendor/community method: Rockchip rockusb + rkdeveloptool),
#   tools/flash-emmc-usb.sh release/0.3.2/emmc
# The box must be in Loader mode on the PC (USB A-A cable in the OTG / USB 2.0 port; `adb reboot loader`, or hold
# the pin in the AV jack while plugging in). Writes every region of manifest.txt (offset_sector size name md5) in
# 64 MiB chunks: decompress -> wl -> rl read-back -> md5 compare, so the 4 GiB super never sits on the PC disk.
# userdata/misc regions are zeros: no pending recovery command, /data is formatted on first boot ("formattable").
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd); D=${1:?release dir, e.g. release/0.3.2/emmc}
RK="sudo $R/sdk/rk/usr/bin/rkdeveloptool"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
CH=$((4 * 1024 * 1024)); CHS=$((CH / 512))   # one wl call writes at most 4 MiB correctly (rkdeveloptool 1.32 pine64)
[ "$(cut -d' ' -f3 $D/release.txt)" = emmc ] || { echo "not an eMMC release"; exit 1; }
(cd $D && sha256sum -c --quiet SHA256SUMS) || { echo "release files damaged"; exit 1; }
$RK ld | tee $T/ld; grep -q 'Loader' $T/ld || { echo "box is not in Loader mode (see header)"; exit 1; }
cap=$($RK rfi 2>/dev/null | sed -n 's/.*Flash Size:[^0-9]*\([0-9]*\) *MB.*/\1/p')
echo "eMMC: ${cap:-?} MB"
while read off size name md5; do
    echo "== $name @$off ($size bytes)"
    zcat $D/regions/$name.gz | split -b $CH -d -a 3 - $T/c.
    i=0
    for c in $T/c.*; do
        s=$((off + i * CHS)); n=$(( ($(stat -c %s $c) + 511) / 512 ))
        $RK wl $s $c >/dev/null
        $RK rl $s $(stat -c %s $c) $T/rb >/dev/null;   # length in BYTES (rkdeveloptool 1.32 pine64)
        sudo chown "$(id -u):$(id -g)" $T/rb
        truncate -s $(stat -c %s $c) $T/rb
        [ "$(md5sum < $c)" = "$(md5sum < $T/rb)" ] || { echo "VERIFY FAIL $name chunk $i"; exit 1; }
        rm -f $c $T/rb; i=$((i + 1))
    done
    echo "   ok ($i chunk(s) written and read back)"
done < $D/manifest.txt
echo "ALL REGIONS WRITTEN AND VERIFIED"
$RK rd
