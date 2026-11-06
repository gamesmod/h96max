#!/system/bin/sh
# Восстановить сохранённый Wi‑Fi-профиль только при первой загрузке с eMMC.
# Файл содержит пароли; команды и журналы не должны выводить его содержимое.
case "$(cat /proc/cmdline 2>/dev/null)" in *androidboot.boot_devices=ff500000*) exit 0;; esac
SD=/dev/block/mmcblk0p15
MNT=/mnt/tvbox_emmc
SRC=$MNT/network-restore/WifiConfigStore.xml
[ -b "$SD" ] || exit 0
mkdir -p "$MNT"
mount -t ext4 -o ro "$SD" "$MNT" 2>/dev/null || exit 0
if [ ! -s "$SRC" ]; then umount "$MNT"; exit 0; fi

# Android 12 на этой сборке использует APEX-хранилище; оставлены варианты для миграций.
for DIR in /data/misc/apexdata/com.android.wifi /data/misc/wifi /data/misc_ce/0/apexdata/com.android.wifi; do
    mkdir -p "$DIR" || continue
    TMP="$DIR/WifiConfigStore.xml.tvbox-tmp"
    if cp "$SRC" "$TMP"; then
        chown system:wifi "$TMP" 2>/dev/null
        chmod 660 "$TMP"
        restorecon "$DIR" "$TMP" 2>/dev/null
        if mv -f "$TMP" "$DIR/WifiConfigStore.xml"; then
            mkdir -p /data/vendor/tvbox
            touch /data/vendor/tvbox/wifi.done
            umount "$MNT"
            mount -o remount,rw "$SD" "$MNT" 2>/dev/null
            rm -f "$SRC"
            rmdir "$MNT/network-restore" 2>/dev/null
            mount -o remount,ro "$SD" "$MNT" 2>/dev/null
            echo "tvbox: saved Wi-Fi settings restored"
            exit 0
        fi
    fi
    rm -f "$TMP"
done
umount "$MNT"
echo "tvbox: saved Wi-Fi settings could not be restored"
