#!/system/bin/sh
# usage: emmc-dump-chunk.sh N  -> /data/local/tmp/edump/cN.gz + cN.sha (sha256 of raw 512MiB chunk, read via dd)
N=$1; E=/dev/block/mmcblk2; O=/data/local/tmp/edump
case "$(readlink -f /sys/block/mmcblk2/device)" in *ff520000*) ;; *) echo ABORT not eMMC; exit 1;; esac
mkdir -p $O
dd if=$E bs=1048576 skip=$((N*512)) count=512 2>/dev/null | sha256sum | cut -d" " -f1 > $O/c$N.sha
dd if=$E bs=1048576 skip=$((N*512)) count=512 2>/dev/null | gzip -1 > $O/c$N.gz
chmod 644 $O/c$N.*
