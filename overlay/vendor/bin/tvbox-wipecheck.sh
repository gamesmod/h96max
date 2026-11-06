#!/system/bin/sh
# Runs on "fs", before /data is mounted. An OTA in "с очисткой" mode leaves the marker TVBOX-WIPE in misc at 64 KiB
# (written by update-binary only after every partition was written and verified). Zeroing the head of userdata
# makes init format it on this boot (userdata is "formattable"), exactly like the first boot of a fresh image.
MISC=/dev/block/by-name/misc
[ "$(dd if=$MISC bs=4096 skip=16 count=1 2>/dev/null | head -c 10)" = "TVBOX-WIPE" ] || exit 0
echo "tvbox: OTA wipe requested: clearing userdata" > /dev/kmsg
dd if=/dev/zero of=/dev/block/by-name/userdata bs=1048576 count=16 conv=fsync 2>/dev/null
dd if=/dev/zero of=$MISC bs=4096 seek=16 count=1 conv=fsync 2>/dev/null
echo "tvbox: userdata cleared, will be formatted" > /dev/kmsg
