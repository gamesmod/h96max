#!/system/bin/sh
# Boot-only setup; size is a limit for uncompressed pages, not a RAM reservation.
set -eu
D=/data/vendor/tvbox
mkdir -p "$D"
exec >>"$D/zram.log" 2>&1
echo "== $(date)"
MAX=$(awk '/^MemTotal:/ {print int($2 / 1024)}' /proc/meminfo)
MB=0
if [ -f "$D/zram-mb" ]; then read -r MB < "$D/zram-mb" || true; fi
case "$MB" in ''|*[!0-9]*) echo 'invalid size; disabled'; MB=0;; esac
[ "${#MB}" -le 6 ] || MB=0
[ "$MB" -le "$MAX" ] || MB=$MAX
if grep -q '^/dev/.*zram0[[:space:]]' /proc/swaps; then
    echo 'zram already active; leaving it unchanged'
    exit 1
fi
[ -d /sys/block/zram0 ] || { echo 'zram unavailable'; exit 1; }
if [ "$(cat /sys/block/zram0/disksize)" != 0 ]; then
    echo 1 > /sys/block/zram0/reset || true
    [ "$(cat /sys/block/zram0/disksize)" = 0 ] || exit 1
fi
if [ "$MB" = 0 ]; then echo 'compression disabled'; exit 0; fi
echo lz4 > /sys/block/zram0/comp_algorithm
# Large-byte arithmetic in the vendor shell overflows past 2 GiB.
BYTES=$(awk -v mb="$MB" 'BEGIN {printf "%.0f", mb * 1048576}')
echo "$BYTES" > /sys/block/zram0/disksize
mkswap /dev/block/zram0
swapon /dev/block/zram0
echo "zram enabled: $MB MB, lz4"
