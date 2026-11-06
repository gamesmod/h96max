#!/system/bin/sh
# Booted from SD: make the internal eMMC (ff520000.dwmmc) read-only at block level
# and remove its RPMB device nodes so OP-TEE cannot touch eMMC RPMB.
stage=$1
# only when the running system came from the SD card; on an eMMC install the eMMC IS the system
grep -q "androidboot.storagemedia=sd" /proc/cmdline || { echo "tvbox: $stage: booted from eMMC, eMMC stays writable" > /dev/kmsg; exit 0; }
i=0
while [ $i -lt 50 ]; do
    found=0
    for d in /sys/block/mmcblk*; do
        [ -e "$d/device" ] || continue
        case "$(readlink -f $d/device)" in
            *ff520000.dwmmc*)
                found=1
                n=$(basename $d)
                echo 1 > $d/force_ro
                rm -f /dev/${n}rpmb /dev/block/${n}rpmb
                echo "tvbox: $stage: $n (eMMC) force_ro=$(cat $d/force_ro) ro=$(cat $d/ro) rpmb_nodes=$(ls /dev/${n}rpmb /dev/block/${n}rpmb 2>/dev/null | wc -l)" > /dev/kmsg
                ;;
        esac
    done
    [ $found = 1 ] && exit 0
    i=$((i + 1))
    sleep 0.1
done
echo "tvbox: $stage: eMMC block device not found" > /dev/kmsg
