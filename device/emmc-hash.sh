#!/system/bin/sh
# Read-only: sha256 of every eMMC partition (by-name) + whole-disk GPT areas. Output to $1.
OUT=$1
E=/sys/block/mmcblk2
case "$(readlink -f $E/device)" in *ff520000.dwmmc*) ;; *) echo "mmcblk2 is not eMMC"; exit 1;; esac
: > $OUT
for l in /dev/block/platform/ff520000.dwmmc/by-name/*; do
    n=$(basename $l)
    [ "$n" = userdata ] && [ "$2" != full ] && continue
    echo "$n $(readlink $l) $(sha256sum < $l | cut -d' ' -f1)" >> $OUT
done
echo "gpt-head $(dd if=/dev/block/mmcblk2 bs=512 count=34 2>/dev/null | sha256sum | cut -d' ' -f1)" >> $OUT
S=$(cat $E/size)
echo "gpt-tail $(dd if=/dev/block/mmcblk2 bs=512 skip=$((S-33)) count=33 2>/dev/null | sha256sum | cut -d' ' -f1)" >> $OUT
echo "idb $(dd if=/dev/block/mmcblk2 bs=512 skip=64 count=8128 2>/dev/null | sha256sum | cut -d' ' -f1)" >> $OUT
chmod 644 $OUT
echo done
