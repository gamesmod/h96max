#!/system/bin/sh
set -eu
B=/data/local/tmp/tvbox-zram-bench
M=/data/local/tmp/tvbox-zram-bench-ram
[ ! -e "$M" ] || { echo 'benchmark directory exists'; exit 1; }
chmod 0755 "$B"
ZID=$(cat /sys/class/zram-control/hot_add)
case "$ZID" in ''|*[!0-9]*|0) echo 'unexpected zram id'; exit 1;; esac
Z=/sys/block/zram$ZID
DEV=/dev/block/zram$ZID
cleanup() {
    umount "$M" 2>/dev/null || true
    rmdir "$M" 2>/dev/null || true
    echo 1 > "$Z/reset" 2>/dev/null || true
    echo "$ZID" > /sys/class/zram-control/hot_remove 2>/dev/null || true
}
trap cleanup EXIT HUP INT TERM
mkdir "$M"
mount -t tmpfs -o size=272m,nosuid,nodev,noexec tmpfs "$M"
sleep 1
[ -b "$DEV" ]
echo "BEGIN $(date)"; cat /proc/meminfo | head -3; cat /sys/class/thermal/thermal_zone0/temp
for pattern in repeated mixed random; do
    for round in 1 2 3; do
        echo "TRIAL=$round pattern=$pattern baseline=tmpfs"
        "$B" "$M/data" 256 2 "$pattern"
        rm -f "$M/data"
        for algo in lz4 zstd; do
            echo 1 > "$Z/reset" 2>/dev/null || true
            [ "$(cat "$Z/disksize")" = 0 ]
            echo "$algo" > "$Z/comp_algorithm"
            echo 268435456 > "$Z/disksize"
            echo "TRIAL=$round pattern=$pattern algorithm=$algo"
            "$B" "$DEV" 256 2 "$pattern"
            echo "MM_STAT $(cat "$Z/mm_stat")"
        done
    done
done
echo "END $(date)"; cat /sys/class/thermal/thermal_zone0/temp; cat /proc/swaps
