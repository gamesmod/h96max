#!/bin/bash
# Full eMMC dump (read-only) over adb in 512 MiB chunks, sha256-verified device<->host.
TARGET=${ADB_TARGET:?Укажите адрес приставки в ADB_TARGET}; A="adb -s $TARGET"; D=~/h96max/backup/emmc-full; mkdir -p "$D"
$A push ~/h96max/device/emmc-dump-chunk.sh /data/local/tmp/edc.sh >/dev/null
SZ=$($A shell cat /sys/block/mmcblk2/size | tr -d '\r'); echo "eMMC sectors: $SZ" > $D/info.txt
LAST=$(( (SZ / 2048 + 511) / 512 - 1 ))
for N in $(seq 0 $LAST); do
  if [ -f $D/c$N.ok ]; then continue; fi
  [ $(df --output=avail -k $D | tail -1) -gt 5242880 ] || { echo "STOP: less than 5 GiB free on host at chunk $N"; exit 2; }
  for try in 1 2 3; do
    $A shell su 0 sh /data/local/tmp/edc.sh $N || { sleep 5; continue; }
    $A pull /data/local/tmp/edump/c$N.gz $D/c$N.gz >/dev/null && $A pull /data/local/tmp/edump/c$N.sha $D/c$N.sha >/dev/null || { sleep 5; continue; }
    H=$(zcat $D/c$N.gz | sha256sum | cut -d' ' -f1); S=$(tr -d '\r\n' < $D/c$N.sha)
    if [ "$H" = "$S" ]; then touch $D/c$N.ok; echo "chunk $N/$LAST OK $(du -h $D/c$N.gz | cut -f1)"; break; else echo "chunk $N MISMATCH try $try"; fi
  done
  $A shell su 0 rm -f /data/local/tmp/edump/c$N.gz /data/local/tmp/edump/c$N.sha
  [ -f $D/c$N.ok ] || { echo "FAILED at chunk $N"; exit 1; }
done
echo "ALL CHUNKS OK"
