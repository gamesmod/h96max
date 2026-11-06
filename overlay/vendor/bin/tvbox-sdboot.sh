#!/system/bin/sh
# Runs on "fs" when booted from the eMMC. "Питание" → "Перезагрузить с внутренней памяти" (tvboxd REBOOT_EMMC)
# makes U-Boot skip the SD card for one boot: rkimgtest only accepts the SD when sector 64 starts with the IDB
# magic 3b8cdcfc, so tvboxd saves the original 4 bytes into sector 63 ("TVBOX-EMMCBOOT" + 2 zero bytes + 4 bytes)
# and writes them inverted into sector 64. Here they are put back, so the next reboot starts from the SD again.
grep -q 'androidboot.boot_devices=ff520000' /proc/cmdline || exit 0
SD=/dev/block/mmcblk0
case "$(readlink -f /sys/block/mmcblk0/device)" in *ff500000*) ;; *) exit 0;; esac
hex() { dd if=$SD bs=512 skip=$1 count=1 2>/dev/null | od -An -tx1 -v | tr -d ' \n' | cut -c$2; }
[ "$(hex 63 1-28)" = "54564f582d454d4d43424f4f5400" ] || exit 0   # "TVBOX-EMMCBOOT\0"
ORIG=$(hex 63 33-40)
CUR=$(hex 64 1-8)
INV=$(echo $ORIG | awk '{ s = ""; for (i = 1; i <= 8; i += 2) { h = substr($0, i, 2); v = index("0123456789abcdef", substr(h, 1, 1)) * 16 + index("0123456789abcdef", substr(h, 2, 1)) - 17; s = s sprintf("%02x", 255 - v) } print s }')
if [ "$CUR" = "$INV" ]; then
    printf "$(echo $ORIG | sed 's/../\\x&/g')" | dd of=$SD bs=1 seek=32768 count=4 conv=notrunc,fsync 2>/dev/null
    echo 3 > /proc/sys/vm/drop_caches
fi
if [ "$(hex 64 1-8)" = "$ORIG" ]; then
    dd if=/dev/zero of=$SD bs=512 seek=63 count=1 conv=fsync 2>/dev/null
    echo "tvbox: SD boot restored (one-shot eMMC boot done)" > /dev/kmsg
else
    echo "tvbox: SD boot NOT restored: sector 64 = $(hex 64 1-8), saved $ORIG" > /dev/kmsg
fi
