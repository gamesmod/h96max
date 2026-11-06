#!/system/bin/sh
# Write rk3318-android SD image regions to the SD card. Run from the dir with manifest.txt + regions/.
cd "$(dirname "$0")"
SD=/dev/block/mmcblk0
B=/sys/block/mmcblk0
case "$(readlink -f $B/device)" in *ff500000.dwmmc*) ;; *) echo "ABORT: mmcblk0 is not the SD slot"; exit 1;; esac
[ "$(cat $B/size)" = 31116288 ] || { echo "ABORT: unexpected SD size $(cat $B/size)"; exit 1; }
if grep -q mmcblk0 /proc/mounts; then echo "ABORT: SD mounted"; grep mmcblk0 /proc/mounts; exit 1; fi
fail=0
while read off size name md5; do
    zcat regions/$name.gz > cur.bin || { echo "ABORT: unzip $name"; exit 1; }
    m=$(md5sum < cur.bin | cut -d' ' -f1)
    [ "$m" = "$md5" ] || { echo "ABORT: source md5 $name"; exit 1; }
    if [ $((off % 2048)) -eq 0 ]; then
        dd if=cur.bin of=$SD bs=1048576 seek=$((off / 2048)) 2>/dev/null || { echo "ABORT: dd $name"; exit 1; }
    else
        dd if=cur.bin of=$SD bs=512 seek=$off 2>/dev/null || { echo "ABORT: dd $name"; exit 1; }
    fi
    echo "wrote $name @$off ($size)"
done < manifest.txt
rm -f cur.bin
sync; blockdev --flushbufs $SD; echo 3 > /proc/sys/vm/drop_caches
while read off size name md5; do
    # Android mksh/test use 32-bit integers: sizes >= 2 GiB go through awk (4 GiB super became 0 -> false FAIL)
    if [ "$(awk -v s=$size 'BEGIN{print (s > 268435456)}')" = 1 ]; then
        # large region: compare 64 MiB chunks against the source (single >=4 GiB stream md5 is unreliable)
        zcat regions/$name.gz > cur.bin
        i=0; r=$md5; chunks=$(awk -v s=$size 'BEGIN{print int(s / 67108864)}')
        while [ $i -lt $chunks ]; do
            a=$(dd if=cur.bin bs=1048576 skip=$((i * 64)) count=64 2>/dev/null | md5sum | cut -d' ' -f1)
            b=$(dd if=$SD bs=1048576 skip=$((off / 2048 + i * 64)) count=64 2>/dev/null | md5sum | cut -d' ' -f1)
            [ "$a" = "$b" ] || { r=chunk$i; break; }
            i=$((i + 1))
        done
        rm -f cur.bin
    elif [ $((off % 2048)) -eq 0 ]; then
        r=$(dd if=$SD bs=1048576 skip=$((off / 2048)) count=$(awk -v s=$size 'BEGIN{print int(s / 1048576)}') 2>/dev/null | md5sum | cut -d' ' -f1)
    else
        r=$(dd if=$SD bs=512 skip=$off count=$(awk -v s=$size 'BEGIN{print int(s / 512)}') 2>/dev/null | md5sum | cut -d' ' -f1)
    fi
    if [ "$r" = "$md5" ]; then echo "verify OK $name"; else echo "verify FAIL $name"; fail=1; fi
done < manifest.txt
[ $fail = 0 ] && echo "ALL VERIFIED" || echo "VERIFY FAILED"
