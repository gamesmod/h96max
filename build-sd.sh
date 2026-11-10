#!/bin/bash
# Build the vendor-derived system image. Release builds target eMMC; SD boot/update use the stock A12 recovery path.
# Output: build/out-emmc/regions/*.bin.gz + build/out-emmc/manifest.txt (offset_sector size file md5)
set -euo pipefail
R=$(cd "$(dirname "$0")" && pwd)
source "$R/tools/required-apps.sh"
tvbox_require_apks || { echo "Сборка остановлена: комплект приложений неполный" >&2; exit 1; }
T=$R/tools
SRC=$R/dl/a12/Image          # unpacked vendor update.img
LP=$SRC/lp                   # unpacked super partitions (pristine)
W=$R/build/work
O=$R/build/out
MEDIA=${MEDIA:-emmc}               # emmc; custom SD images are not part of the release workflow
if [ "$MEDIA" = emmc ]; then SD_SECTORS=61071360; O=$R/build/out-emmc; BOOTDEV=ff520000.dwmmc
else SD_SECTORS=${SD_SECTORS:-31116288}; BOOTDEV=ff500000.dwmmc; fi
SUPER_SECTORS=$((0x800000))  # 4 GiB super on SD (vendor: 0x614000)
MNT=$W/mnt

log() { echo "[build] $*"; }
need() { command -v "$1" >/dev/null || { echo "missing $1"; exit 1; }; }
for c in sgdisk e2fsck resize2fs debugfs python3 gzip setfattr; do need $c; done

rm -rf "$W" "$O"; mkdir -p "$W" "$O/regions" "$MNT"
log "MEDIA=$MEDIA disk=$SD_SECTORS sectors boot_devices=$BOOTDEV out=$O"

# ---------- 1. boot / recovery ----------
log "boot.img: boot_devices -> SD, fstab without SD voldmanaged"
python3 -I $T/bootimg.py edit $SRC/boot.img /dev/null --rd-get fstab.rk30board=$W/fstab.orig >/dev/null
if [ -n "${VANILLA:-}" ]; then
    cp $W/fstab.orig $W/fstab.sd
else
# SD image: the SD slot is the system disk, not removable storage. eMMC image: keep the SD slot as storage
# (updates from an SD card). Partitions are sized exactly (sgdisk +N = N sectors) so OTA images fit by name.
if [ "$MEDIA" = sd ]; then DROP='ff500000.dwmmc/mmc_host'; else DROP='^$'; fi
grep -v "$DROP" $W/fstab.orig \
    | grep -v '^/dev/block/zram0[[:space:]]' \
    | sed -E 's/,fileencryption=[^,[:space:]]*//; s/,keydirectory=[^,[:space:]]*//' \
    | sed -E '/\/metadata/ s/,first_stage_mount//' > $W/fstab.sd
grep -q 'fileencryption\|keydirectory' $W/fstab.sd && { echo "fstab patch failed"; exit 1; }
[ "$MEDIA" = sd ] && grep -q 'ff500000' $W/fstab.sd && { echo "fstab patch failed (SD slot)"; exit 1; }
fi
if [ -n "${VANILLA:-}" ]; then
python3 -I $T/bootimg.py edit $SRC/boot.img $W/boot.img \
    --cmd-sub "androidboot.boot_devices=ff520000.dwmmc|androidboot.boot_devices=ff500000.dwmmc" \
    --cmd-append "printk.devkmsg=on"
else
python3 -I $T/bootimg.py edit $SRC/boot.img $W/boot.img \
    --cmd-sub "androidboot.boot_devices=ff520000.dwmmc|androidboot.boot_devices=$BOOTDEV" \
    --cmd-sub "androidboot.wificountrycode=HK|androidboot.wificountrycode=US" \
    --cmd-append "printk.devkmsg=on" \
    --rd-put fstab.rk30board=$W/fstab.sd
fi
python3 -I $T/bootimg.py verify $W/boot.img

log "recovery.img: boot_devices -> SD, drop hardcoded eMMC/mmcblk0 lines"
python3 -I $T/bootimg.py edit $SRC/recovery.img /dev/null --rd-get system/etc/recovery.fstab=$W/rfstab.orig >/dev/null
grep -vE 'ff520000|mmcblk0p1' $W/rfstab.orig > $W/rfstab.sd
# OTA trust: only our key (secrets/ota-cert.pem); the stock image trusted the public AOSP testkey
rm -rf $W/otacerts $W/otacerts.zip; mkdir -p $W/otacerts; cp $R/secrets/ota-cert.pem $W/otacerts/tvbox-ota.x509.pem
(cd $W/otacerts && zip -q -X $W/otacerts.zip tvbox-ota.x509.pem)
python3 -I $T/bootimg.py edit $SRC/recovery.img $W/recovery.img \
    --cmd-sub "androidboot.boot_devices=ff520000.dwmmc|androidboot.boot_devices=$BOOTDEV" \
    --rd-put system/etc/recovery.fstab=$W/rfstab.sd \
    --rd-put system/etc/security/otacerts.zip=$W/otacerts.zip
python3 -I $T/bootimg.py verify $W/recovery.img
for i in boot recovery; do
    c=$(python3 -I $T/bootimg.py verify $W/$i.img)
    [[ $c == *"id ok"* && $c == *boot_devices=$BOOTDEV* ]] || { echo "bad $i: $c"; exit 1; }
    if [ "$MEDIA" = sd ] && [[ $c == *ff520000* ]]; then echo "SD image references eMMC: $c"; exit 1; fi
done

# ---------- 2. logical partitions ----------
for p in system system_ext vendor vendor_dlkm odm odm_dlkm product; do cp $LP/$p.img $W/$p.img; done

grow() { e2fsck -fy $W/$1.img >/dev/null || [ $? -le 1 ]; resize2fs $W/$1.img $2 >/dev/null; }
shrink() { e2fsck -fy $W/$1.img >/dev/null || [ $? -le 1 ]; resize2fs -M $W/$1.img >/dev/null 2>&1;
           # leave 8 MiB headroom, keep 4K alignment
           local b; b=$(dumpe2fs -h $W/$1.img 2>/dev/null | awk '/^Block count/{print $3}')
           resize2fs $W/$1.img $((b + 2048)) >/dev/null; truncate -s $(( (b + 2048) * 4096 )) $W/$1.img; }
mnt() { sudo mount -o loop,rw $W/$1.img $MNT; }
umnt() { sync; for i in 1 2 3 4 5; do sudo umount $MNT && return 0; sleep 2; done; sudo umount -l $MNT; }
lbl() { sudo setfattr -h -n security.selinux -v "$1" "${@:2}"; }

grow product 1200M; grow system_ext 200M; grow vendor 256M; grow system 1600M; grow odm 8M
if [ -z "${VANILLA:-}" ]; then

log "debloat: product"
mnt product
for d in app/GoogleCalendarSyncAdapter app/GoogleContactsSyncAdapter app/GoogleLocationHistory \
         app/LatinImeGoogle app/SpeechServicesByGoogle app/TrichromeLibrary app/WebViewGoogle \
         app/YouTube app/com.google.mainline.telemetry \
         priv-app/AndroidAutoStub priv-app/CarrierServices priv-app/ConfigUpdater priv-app/GmsCore \
         priv-app/GoogleOneTimeInitializer priv-app/GooglePartnerSetup priv-app/GoogleRestore \
         priv-app/PhoneskyTv priv-app/Wellbeing \
         overlay/GmsConfigOverlayCommon.apk overlay/GmsConfigOverlayComms.apk \
         overlay/GmsConfigOverlayGSA.apk overlay/GmsConfigOverlayGeotz.apk; do
    [ -e $MNT/$d ] && sudo rm -rf $MNT/$d || echo "  (absent) $d"
done
log "product: open WebView (com.android.webview, LineageOS prebuilt)"
sudo mkdir -p $MNT/app/webview $MNT/tvbox/apk
sudo install -m 0644 $R/dl/webview/webview-arm64.apk $MNT/app/webview/webview.apk
sudo install -m 0644 $R/obtainium-apps.json $MNT/tvbox/obtainium-apps.json
for a in $R/dl/apk/*.apk; do
    case "${a##*/}" in
        com.tvlauncher.apk) continue ;;
        tcandroidwifi45.apk|com.ghisler.tcplugins.wifitransfer.apk) echo "skip removed Total Commander WiFi Transfer plugin"; continue ;;
        com.agateau.tinywheels.android.apk|org.supertux.supertux2.apk|app.flicky.apk) echo "skip removed APK ${a##*/}"; continue ;;
    esac
    sudo install -m 0644 $a $MNT/tvbox/apk/
done
# TV Launcher is a system app (no native libs): installing it at first boot made the system start HOME while the
# package was still frozen by the installer ("Package com.tvlauncher is currently frozen!" x52, ~3 s black screen)
sudo mkdir -p $MNT/app/TvLauncher
sudo install -m 0644 $R/dl/apk/com.tvlauncher.apk $MNT/app/TvLauncher/TvLauncher.apk
sudo chmod 0755 $MNT/app/webview $MNT/app/TvLauncher $MNT/tvbox $MNT/tvbox/apk
lbl u:object_r:system_file:s0 $MNT/app/webview $MNT/app/webview/webview.apk $MNT/app/TvLauncher $MNT/app/TvLauncher/TvLauncher.apk \
    $MNT/tvbox $MNT/tvbox/apk $MNT/tvbox/obtainium-apps.json
# Некоторые внешние APK удалены из сборки; не передавать setfattr пустую маску файлов.
for a in $MNT/tvbox/apk/*.apk; do
    [ -e "$a" ] && lbl u:object_r:system_file:s0 "$a"
done
sudo tee -a $MNT/etc/build.prop >/dev/null <<'EOF'

# tvbox (rk3318-android)
ro.setupwizard.mode=DISABLED
persist.sys.timezone=Europe/Moscow
persist.sys.locale=ru-RU
persist.sys.usb.config=adb
ro.tvbox.release=0.4.5
EOF
echo "ro.tvbox.build_date=$(date +%F)" | sudo tee -a $MNT/etc/build.prop >/dev/null
umnt

# Rockchip boot *video* (/product/media/bootanimation.ts) overrides bootanimation.zip -> remove, disable
mnt product
sudo rm -f $MNT/media/bootanimation.ts
echo "persist.sys.bootvideo.enable=false" | sudo tee -a $MNT/etc/build.prop >/dev/null
umnt

log "debloat: system_ext"
mnt system_ext
for d in priv-app/GoogleFeedback priv-app/GoogleServicesFramework; do
    [ -e $MNT/$d ] && sudo rm -rf $MNT/$d || echo "  (absent) $d"
done
umnt

log "debloat: system"
mnt system
sudo rm -f $MNT/usr/Aging_Test_Video.mp4
# Системные ro-свойства загружаются раньше product и должны быть исправлены в system/build.prop.
if sudo grep -q '^ro.secure=' "$MNT/system/build.prop"; then
    sudo sed -i 's/^ro.secure=.*/ro.secure=1/' "$MNT/system/build.prop"
else
    echo 'ro.secure=1' | sudo tee -a "$MNT/system/build.prop" >/dev/null
fi
if sudo grep -q '^ro.adb.secure=' "$MNT/system/build.prop"; then
    sudo sed -i 's/^ro.adb.secure=.*/ro.adb.secure=1/' "$MNT/system/build.prop"
else
    echo 'ro.adb.secure=1' | sudo tee -a "$MNT/system/build.prop" >/dev/null
fi
# Ограничиваем root-suid: доступ к su оставляем только root и группе shell для доверенной отладки.
if [ -f "$MNT/system/xbin/su" ]; then
    sudo chown 0:2000 "$MNT/system/xbin/su"
    sudo chmod 4750 "$MNT/system/xbin/su"
else
    echo "Не найден $MNT/system/xbin/su — невозможно применить ограничение su" >&2
    exit 1
fi
# vendor "preinstall" bundle (TikTok, Netflix, Prime, Aptoide, APKPure, AirScreen, KDMC, store) installed at first boot
[ -d $MNT/system/preinstall ] && sudo rm -rf $MNT/system/preinstall
# power menu tile (apps/power, privileged: REBOOT/SHUTDOWN/DEVICE_POWER)
sudo mkdir -p $MNT/system/priv-app/TvboxPower
sudo install -m 0644 $R/apps/power/out/TvboxPower.apk $MNT/system/priv-app/TvboxPower/TvboxPower.apk
sudo install -m 0644 $R/apps/power/privapp-permissions-tvbox.xml $MNT/system/etc/permissions/privapp-permissions-tvbox.xml
sudo chmod 0755 $MNT/system/priv-app/TvboxPower
lbl u:object_r:system_file:s0 $MNT/system/priv-app/TvboxPower $MNT/system/priv-app/TvboxPower/TvboxPower.apk $MNT/system/etc/permissions/privapp-permissions-tvbox.xml
# vendor telemetry / factory test daemons: datadis phones home (serial+version -> monitor.hcybox.com:41523),
# user_drmservice listens on UDP 33334 as root, stress_* are endless factory loops
for f in datadis user_drmservice mytest userfun.sh stress_ddr_test stress_wifi_test.sh; do sudo rm -f $MNT/system/bin/$f; done
# TorrServer MatriX.147.1 android-arm64 (service "torrserver" in tvbox.rc, :8090)
sudo install -m 0755 $R/dl/torrserver/TorrServer-android-arm64 $MNT/system/bin/torrserver
lbl u:object_r:system_file:s0 $MNT/system/bin/torrserver
# Stock front-panel clock writer (compatible A12 node is /dev/digital_dis).
sudo install -m 0755 $R/stock/vfd/vfdsrv $MNT/system/bin/tvbox-vfdsrv
lbl u:object_r:system_file:s0 $MNT/system/bin/tvbox-vfdsrv
# boot log viewer tile
sudo mkdir -p $MNT/system/app/TvboxBootLog
sudo install -m 0644 $R/apps/bootlog/out/TvboxBootLog.apk $MNT/system/app/TvboxBootLog/TvboxBootLog.apk
sudo chmod 0755 $MNT/system/app/TvboxBootLog
lbl u:object_r:system_file:s0 $MNT/system/app/TvboxBootLog $MNT/system/app/TvboxBootLog/TvboxBootLog.apk
# IPTV tile (apps/iptv): M3U groups -> channels -> VLC (replaces Kodi pvr.iptvsimple)
sudo mkdir -p $MNT/system/app/TvboxIptv
sudo install -m 0644 $R/apps/iptv/out/TvboxIptv.apk $MNT/system/app/TvboxIptv/TvboxIptv.apk
sudo chmod 0755 $MNT/system/app/TvboxIptv
lbl u:object_r:system_file:s0 $MNT/system/app/TvboxIptv $MNT/system/app/TvboxIptv/TvboxIptv.apk
# merged + checked channel list (tools/iptv-merge.py: no duplicates, working logo and stream)
[ -s $R/overlay/system/etc/tvbox/iptv.m3u ] || { echo "missing overlay/system/etc/tvbox/iptv.m3u (run tools/iptv-merge.py)"; exit 1; }
sudo mkdir -p $MNT/system/etc/tvbox
sudo install -m 0644 $R/overlay/system/etc/tvbox/iptv.m3u $MNT/system/etc/tvbox/iptv.m3u
sudo chmod 0755 $MNT/system/etc/tvbox
lbl u:object_r:system_file:s0 $MNT/system/etc/tvbox $MNT/system/etc/tvbox/iptv.m3u
# USB remotes / keyboards: PageUp/PageDown switch channels (VLC maps CHANNEL_UP/DOWN to next/previous in the
# playlist "ТВ-каналы" hands over; the TV remote sends CHANNEL_UP/DOWN over HDMI-CEC already)
sudo sed -i -E 's/^key 104( +)PAGE_UP$/key 104\1CHANNEL_UP/; s/^key 109( +)PAGE_DOWN$/key 109\1CHANNEL_DOWN/' $MNT/system/usr/keylayout/Generic.kl
grep -q '^key 104 *CHANNEL_UP$' $MNT/system/usr/keylayout/Generic.kl || { echo "Generic.kl patch failed"; exit 1; }
# RAM cleanup tile (apps/clean): stop background apps, show freed memory (like the stock A9 button)
sudo mkdir -p $MNT/system/app/TvboxClean
sudo install -m 0644 $R/apps/clean/out/TvboxClean.apk $MNT/system/app/TvboxClean/TvboxClean.apk
sudo chmod 0755 $MNT/system/app/TvboxClean
lbl u:object_r:system_file:s0 $MNT/system/app/TvboxClean $MNT/system/app/TvboxClean/TvboxClean.apk
# "Антивирус" tile (apps/antivirus): Hypatia on demand via tvboxd (realtime off by default)
sudo mkdir -p $MNT/system/app/TvboxAntivirus
sudo install -m 0644 $R/apps/antivirus/out/TvboxAntivirus.apk $MNT/system/app/TvboxAntivirus/TvboxAntivirus.apk
sudo chmod 0755 $MNT/system/app/TvboxAntivirus
lbl u:object_r:system_file:s0 $MNT/system/app/TvboxAntivirus $MNT/system/app/TvboxAntivirus/TvboxAntivirus.apk
# "Установка и обновление" tile (apps/installer), talks to tvboxd: on SD install/backup/restore the eMMC + updates,
# on eMMC updates only (the app chooses by the boot device)
sudo mkdir -p $MNT/system/app/TvboxInstaller
sudo install -m 0644 $R/apps/installer/out/TvboxInstaller.apk $MNT/system/app/TvboxInstaller/TvboxInstaller.apk
sudo chmod 0755 $MNT/system/app/TvboxInstaller
lbl u:object_r:system_file:s0 $MNT/system/app/TvboxInstaller $MNT/system/app/TvboxInstaller/TvboxInstaller.apk
# QR codes of the web services on the box (apps/links)
sudo mkdir -p $MNT/system/app/TvboxLinks
sudo install -m 0644 $R/apps/links/out/TvboxLinks.apk $MNT/system/app/TvboxLinks/TvboxLinks.apk
sudo chmod 0755 $MNT/system/app/TvboxLinks
lbl u:object_r:system_file:s0 $MNT/system/app/TvboxLinks $MNT/system/app/TvboxLinks/TvboxLinks.apk
# Живой журнал загрузки заменяет статичную заставку, чтобы не показывать старый логотип.
# Оригинальный бинарник сохраняем только как резервную копию, но не запускаем.
sudo cp -f $MNT/system/bin/bootanimation $MNT/system/bin/bootanimation.orig
sudo install -m 0644 $R/apps/bootscreen/out/tvbox-bootscreen.jar $MNT/system/framework/tvbox-bootscreen.jar
sudo install -m 0755 $R/overlay/vendor/bin/tvbox-bootanimation.sh $MNT/system/bin/bootanimation
lbl u:object_r:system_file:s0 $MNT/system/framework/tvbox-bootscreen.jar
# tvboxd root helper (apps/tvboxd), started from vendor tvbox.rc
sudo install -m 0644 $R/apps/tvboxd/out/tvboxd.jar $MNT/system/framework/tvboxd.jar
lbl u:object_r:system_file:s0 $MNT/system/framework/tvboxd.jar
# framework-side OTA verification (RecoverySystem.verifyPackage in tvboxd) uses the same single key
sudo install -m 0644 $W/otacerts.zip $MNT/system/etc/security/otacerts.zip
lbl u:object_r:system_file:s0 $MNT/system/etc/security/otacerts.zip
# VLC defaults (vendor preinstall.sh copies this file into VLC on first boot): subtitles off unless chosen
grep -q subtitle_preferred_language $MNT/system/media/org.videolan.vlc_preferences.xml || \
  sudo sed -i 's|</map>|    <string name="subtitle_preferred_language">none</string>\n</map>|' $MNT/system/media/org.videolan.vlc_preferences.xml
lbl u:object_r:system_file:s0 $MNT/system/media/org.videolan.vlc_preferences.xml
lbl u:object_r:bootanim_exec:s0 $MNT/system/bin/bootanimation
# Резервная копия штатного исполняемого файла не запускается и не должна иметь домен bootanim_exec.
lbl u:object_r:system_file:s0 $MNT/system/bin/bootanimation.orig
# Keep the native renderer's original graphics user and SELinux domain.
lbl u:object_r:system_file:s0 $MNT/system/etc/init/bootanim.rc
for d in app/katniss app/chrome_hcy app/GooglePrintRecommendationService app/RKHcyUpdate \
         app/ProductTest app/RKHcyDeviceTest priv-app/StressTest \
         app/33118V12 app/SimpleAllApp app/PinyinIME app/ExactCalculator app/WifiDisplay app/HidAudio \
         priv-app/DLNA priv-app/MediaCenter app/SkySetupWizard priv-app/ShowDebug priv-app/DynamicSystemInstallationService; do
    [ -e $MNT/system/$d ] && sudo rm -rf $MNT/system/$d || echo "  (absent) $d"
done
umnt

fi # VANILLA skips debloat/apps
log "vendor: tvbox init, eMMC safety, rpmb lock; no Wi-Fi credentials in image"
# Взять полную раскладку AOSP и явно закрепить голосовую клавишу за VID/PID USB-пульта.
debugfs -R "dump /system/usr/keylayout/Generic.kl $W/usb-remote.kl" $LP/system.img >/dev/null
grep -qE '^key[[:space:]]+582[[:space:]]+VOICE_ASSIST' $W/usb-remote.kl || { echo "В раскладке AOSP нет привязки клавиши 582 к VOICE_ASSIST"; exit 1; }
mnt vendor
sudo install -m 0644 $R/overlay/vendor/etc/init/tvbox.rc $MNT/etc/init/tvbox.rc
sudo install -m 0755 $R/overlay/vendor/bin/tvbox-emmc-ro.sh $MNT/bin/tvbox-emmc-ro.sh
sudo install -m 0755 $R/overlay/vendor/bin/tvbox-firstboot.sh $MNT/bin/tvbox-firstboot.sh
sudo install -m 0755 $R/overlay/vendor/bin/tvbox-restore-wifi.sh $MNT/bin/tvbox-restore-wifi.sh
sudo install -m 0755 $R/overlay/vendor/bin/tvbox-startup-summary.sh $MNT/bin/tvbox-startup-summary.sh
sudo install -m 0755 $R/overlay/vendor/bin/tvbox-clock.sh $MNT/bin/tvbox-clock.sh
sudo install -m 0755 $R/overlay/vendor/bin/tvbox-zram.sh $MNT/bin/tvbox-zram.sh
# Удалить штатный запуск подкачки и отключить флаг vendor при каждой загрузке.
sudo sed -i '/^on sys-boot-completed-set \&\& property:persist.sys.zram_enabled=1/,+1d' "$MNT/etc/init/hw/init.rk30board.rc"
lbl u:object_r:vendor_configs_file:s0 "$MNT/etc/init/hw/init.rk30board.rc"
sudo sed -i 's/^persist\.sys\.zram_enabled=.*/persist.sys.zram_enabled=0/' "$MNT/build.prop"
grep -q '^persist\.sys\.zram_enabled=0$' "$MNT/build.prop" || echo 'persist.sys.zram_enabled=0' | sudo tee -a "$MNT/build.prop" >/dev/null
sudo install -m 0755 $R/overlay/vendor/bin/tvbox-bootlog.sh $MNT/bin/tvbox-bootlog.sh
sudo install -m 0755 $R/overlay/vendor/bin/tvbox-wipecheck.sh $MNT/bin/tvbox-wipecheck.sh
sudo install -m 0755 $R/overlay/vendor/bin/tvbox-sdboot.sh $MNT/bin/tvbox-sdboot.sh
# IR remote keylayout from stock A9 (OK=ENTER); key 388 keeps the A12 label (TV_KEYMOUSE_MODE_SWITCH unknown in A12)
sudo install -m 0644 $R/overlay/vendor/usr/keylayout/ff1b0030_pwm.kl $MNT/usr/keylayout/ff1b0030_pwm.kl
sudo install -m 0644 $W/usb-remote.kl $MNT/usr/keylayout/Vendor_1915_Product_1025.kl
sudo mkdir -p $MNT/etc/tvbox; sudo install -m 0644 $R/overlay/vendor/etc/tvbox/update-urls.txt $MNT/etc/tvbox/update-urls.txt
lbl u:object_r:vendor_configs_file:s0 $MNT/etc/tvbox/update-urls.txt
lbl u:object_r:vendor_keylayout_file:s0 $MNT/usr/keylayout/ff1b0030_pwm.kl $MNT/usr/keylayout/Vendor_1915_Product_1025.kl
# vendor OTA server (same vendor as the removed datadis telemetry): no client left, drop the address anyway
sudo sed -i '/^ro\.product\.ota\.host2\?=/d' $MNT/build.prop
lbl u:object_r:vendor_file:s0 $MNT/build.prop
# Kodi removed (2026-10-27): vendor first-boot hook unpacks a Kodi bundle, preinstall grants Kodi perms -> drop both
sudo sed -i 's/^\([[:space:]]*\)start xbmc_copy_folder/\1# start xbmc_copy_folder/' $MNT/etc/init/hw/init.hcy.rc
sudo sed -i '/^service xbmc_copy_folder /,/^[[:space:]]*$/d' $MNT/etc/init/hw/init.hcy.rc   # the service itself too
sudo sed -i '/org\.xbmc\.kodi/d' $MNT/bin/preinstall.sh
sudo rm -f $MNT/bin/xbmc_copy_folder.sh
lbl u:object_r:vendor_configs_file:s0 $MNT/etc/init/hw/init.hcy.rc
lbl u:object_r:preinstall_exec:s0 $MNT/bin/preinstall.sh
# no cameras on this box (user 2026-10-27): drop both camera provider HALs (rc, binaries, VINTF manifest entry),
# so neither starts at boot nor makes cameraserver wait for a declared instance; cameraserver stays (reports 0 cameras)
sudo rm -f $MNT/etc/init/android.hardware.camera.provider@2.4-service.rc $MNT/etc/init/android.hardware.camera.provider@2.4-external-service.rc \
    $MNT/bin/hw/android.hardware.camera.provider@2.4-service $MNT/bin/hw/android.hardware.camera.provider@2.4-external-service
sudo python3 -I - $MNT/etc/vintf/manifest.xml <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
n = re.subn(r'\n[ \t]*<hal format="hidl">\s*<name>android\.hardware\.camera\.provider</name>.*?</hal>', '', s, flags=re.S)
assert n[1] == 1, "camera.provider not found in vendor manifest"
open(p, 'w').write(n[0])
PY
grep -q camera.provider $MNT/etc/vintf/manifest.xml && { echo "camera HAL still in manifest"; exit 1; }
lbl u:object_r:vendor_configs_file:s0 $MNT/etc/vintf/manifest.xml
# vendor_flash_recovery: install-recovery.sh "repairs" recovery from boot with a stock patch on every boot (it only
# fails today because our boot differs) -> drop the service, script and patch; recovery is ours
sudo rm -f $MNT/etc/init/vendor_flash_recovery.rc $MNT/bin/install-recovery.sh $MNT/recovery-from-boot.p
lbl u:object_r:vendor_configs_file:s0 $MNT/etc/init/tvbox.rc
lbl u:object_r:vendor_file:s0 $MNT/bin/tvbox-emmc-ro.sh $MNT/bin/tvbox-firstboot.sh $MNT/bin/tvbox-restore-wifi.sh $MNT/bin/tvbox-startup-summary.sh $MNT/bin/tvbox-bootlog.sh $MNT/bin/tvbox-wipecheck.sh $MNT/bin/tvbox-sdboot.sh
# second-stage fstab (mount_all /vendor/etc/fstab.rk30board) = same patched copy
cmp -s $MNT/etc/fstab.rk30board $W/fstab.orig || { echo "vendor fstab differs from ramdisk fstab"; exit 1; }
sudo install -m 0644 $W/fstab.sd $MNT/etc/fstab.rk30board
lbl u:object_r:vendor_configs_file:s0 $MNT/etc/fstab.rk30board
sudo mkdir -p $MNT/etc/tvbox; sudo chmod 0700 $MNT/etc/tvbox
# Не переносить сетевые пароли в образ или OTA; Wi-Fi при необходимости восстанавливает пользователь.
sudo rm -f $MNT/etc/tvbox/wifi.env
lbl u:object_r:vendor_configs_file:s0 $MNT/etc/tvbox
# Wi-Fi/BT settings proven on this board (stock A9): dhd config (ccode XZ/11) + BT patch
sudo install -m 0644 $R/a9fw/config.txt $MNT/etc/firmware/config.txt
sudo install -m 0644 $R/a9fw/bcm4334.hcd $MNT/etc/firmware/bcm4334b0.hcd
sudo install -m 0644 $R/a9fw/bcm4334.hcd $MNT/etc/firmware/bcm4334.hcd
sudo install -m 0644 $R/a9fw/fw_bcm4334b1_ag.bin $MNT/etc/firmware/fw_bcm4334b1_ag.bin
sudo install -m 0644 $R/a9fw/fw_bcm4334b1_ag_apsta.bin $MNT/etc/firmware/fw_bcm4334b1_ag_apsta.bin
lbl u:object_r:vendor_file:s0 $MNT/etc/firmware/config.txt $MNT/etc/firmware/bcm4334b0.hcd $MNT/etc/firmware/bcm4334.hcd \
    $MNT/etc/firmware/fw_bcm4334b1_ag.bin $MNT/etc/firmware/fw_bcm4334b1_ag_apsta.bin
# old BCM4334 firmware: PMF/OCE in RSN IE -> AP rejects with status 41 (WPA2/WPA3 mixed network)
sudo sed -i 's/^pmf=1/pmf=0/; s/^oce=1/oce=0/' $MNT/etc/wifi/wpa_supplicant.conf
grep -E '^(pmf|oce)=' $MNT/etc/wifi/wpa_supplicant.conf
# Android 12 auto-upgrades WPA2 networks to WPA3-SAE; BCM4334 cannot -> AP status 41. Static RRO disables it.
sudo mkdir -p $MNT/overlay; sudo chmod 0755 $MNT/overlay
sudo install -m 0644 $R/overlay-wifi/TvboxWifiOverlay.apk $MNT/overlay/TvboxWifiOverlay.apk
lbl u:object_r:vendor_overlay_file:s0 $MNT/overlay $MNT/overlay/TvboxWifiOverlay.apk
# hardcoded eMMC baseparameter node -> by-name (resolves to SD)
sudo sed -i 's#/dev/block/mmcblk2p4#/dev/block/by-name/baseparameter#g' $MNT/etc/init/hw/init.rk3328.rc
grep -n 'baseparameter' $MNT/etc/init/hw/init.rk3328.rc
# lock eMMC RPMB nodes (OP-TEE then uses SD "security" partition)
printf '\n/dev/mmcblk*rpmb          0000   root       root\n' | sudo tee -a $MNT/etc/ueventd.rc >/dev/null
umnt

# device name "panadonik" everywhere (CEC OSD name, DHCP, BT default, adb devices list)
for p in system system_ext vendor product odm; do
    mnt $p
    for bp in $MNT/build.prop $MNT/etc/build.prop $MNT/system/build.prop; do
        [ -f $bp ] && sudo sed -i -E 's/^(ro\.product\.([a-z_]+\.)?(model))=.*/\1=panadonik/' $bp
    done
    umnt
done
for p in product system_ext vendor system; do shrink $p; done
for p in system system_ext vendor vendor_dlkm odm odm_dlkm product; do
    e2fsck -fn $W/$p.img >/dev/null 2>&1 && echo "  fsck ok $p $(stat -c %s $W/$p.img)" || { echo "fsck FAIL $p"; exit 1; }
done

# ---------- 3. super ----------
log "super"
python3 -I $T/lpbuild.py $W/super.raw $((SUPER_SECTORS * 512)) \
    system=$W/system.img system_ext=$W/system_ext.img vendor=$W/vendor.img \
    vendor_dlkm=$W/vendor_dlkm.img odm=$W/odm.img odm_dlkm=$W/odm_dlkm.img product=$W/product.img

# ---------- 4. small images ----------
log "cache/metadata ext4, IDB"
truncate -s $((0xc0000 * 512)) $W/cache.img;   mkfs.ext4 -q -F -L cache -b 4096 -O ^orphan_file,^metadata_csum_seed,^metadata_csum $W/cache.img
truncate -s $((0x8000 * 512)) $W/metadata.img; mkfs.ext4 -q -F -L metadata -b 4096 -O ^orphan_file,^metadata_csum_seed,^metadata_csum $W/metadata.img
python3 -I $T/rkidb.py make $R/dl/a12/MiniLoaderAll.bin $W/idb.bin --sdboot-tag 1

# ---------- 5. GPT ----------
log "GPT"
truncate -s $((SD_SECTORS * 512)) $W/gpt.disk
LAST=$((SD_SECTORS - 34))
IMGP_START=$((0x1e1000 + SUPER_SECTORS))
if [ "$MEDIA" = sd ]; then IMGP_SECTORS=$((0x400000)); else IMGP_SECTORS=0; fi   # 2 GiB eMMC image partition (SD only)
UDATA_START=$((IMGP_START + IMGP_SECTORS))
if [ "$MEDIA" = sd ]; then UDATA_N=16; else UDATA_N=15; fi
if [ "$MEDIA" = sd ]; then
    log "tvbox_emmc: eMMC image for the installer tile"
    E=$R/build/out-emmc
    [ -s $E/manifest.txt ] || { echo "build the eMMC variant first: MEDIA=emmc ./build-sd.sh"; exit 1; }
    grep -q "^$(grep '^ro.tvbox.release=' $0 | cut -d= -f2) " $E/release.txt 2>/dev/null || { echo "out-emmc is from another release - rebuild it"; exit 1; }
    rm -rf $W/imgp; mkdir -p $W/imgp/regions
    cp $E/manifest.txt $E/release.txt $W/imgp/; cp $E/regions/*.gz $W/imgp/regions/
    rm -f $W/tvbox_emmc.img; truncate -s $((IMGP_SECTORS * 512)) $W/tvbox_emmc.img
    mkfs.ext4 -q -F -L tvbox_emmc -b 4096 -O ^orphan_file,^metadata_csum_seed,^metadata_csum -d $W/imgp $W/tvbox_emmc.img
    e2fsck -fn $W/tvbox_emmc.img >/dev/null || { echo "tvbox_emmc fsck FAIL"; exit 1; }
fi

sgdisk -Z $W/gpt.disk >/dev/null 2>&1 || true
sgdisk -a 1 \
 -n 1:$((0x2000)):+$((0x2000))     -c 1:security \
 -n 2:$((0x4000)):+$((0x2000))     -c 2:uboot \
 -n 3:$((0x6000)):+$((0x2000))     -c 3:trust \
 -n 4:$((0x8000)):+$((0x2000))     -c 4:misc \
 -n 5:$((0xa000)):+$((0x2000))     -c 5:dtbo \
 -n 6:$((0xc000)):+$((0x800))      -c 6:vbmeta \
 -n 7:$((0xc800)):+$((0x14000))    -c 7:boot \
 -n 8:$((0x20800)):+$((0x36000))   -c 8:recovery \
 -n 9:$((0x56800)):+$((0xba000))   -c 9:backup \
 -n 10:$((0x110800)):+$((0xc0000)) -c 10:cache \
 -n 11:$((0x1d0800)):+$((0x8000))  -c 11:metadata \
 -n 12:$((0x1d8800)):+$((0x800))   -c 12:baseparameter \
 -n 13:$((0x1d9000)):+$((0x8000))  -c 13:logo \
 -n 14:$((0x1e1000)):+$((SUPER_SECTORS)) -c 14:super \
 $( [ "$MEDIA" = sd ] && echo "-n 15:$IMGP_START:+$((IMGP_SECTORS)) -c 15:tvbox_emmc" ) \
 -n $UDATA_N:$UDATA_START:$LAST        -c $UDATA_N:userdata \
 $W/gpt.disk >/dev/null
sgdisk -p $W/gpt.disk | tail -18
dd if=$W/gpt.disk of=$W/gpt-primary.bin bs=512 count=34 status=none
dd if=$W/gpt.disk of=$W/gpt-backup.bin bs=512 skip=$((SD_SECTORS - 33)) count=33 status=none

# ---------- 6. regions ----------
log "regions"
M=$O/manifest.txt; : > $M
n=0
region() { # name offset_sector source_file|zero:BYTES
    local name=$1 off=$2 src=$3 f
    n=$((n + 1)); f=$O/regions/$(printf %02d $n)-$name.bin
    if [[ $src == zero:* ]]; then head -c ${src#zero:} /dev/zero > $f; else cp $src $f; fi
    # pad to 1 MiB unless sector-granular region
    if [ $((off % 2048)) -eq 0 ]; then truncate -s $(( ($(stat -c %s $f) + 1048575) / 1048576 * 1048576 )) $f
    else truncate -s $(( ($(stat -c %s $f) + 511) / 512 * 512 )) $f; fi
    echo "$off $(stat -c %s $f) $(basename $f) $(md5sum < $f | cut -d' ' -f1)" >> $M
}
head -c 1048576 /dev/zero > $W/head.bin
dd if=$W/gpt-primary.bin of=$W/head.bin conv=notrunc status=none
dd if=$W/idb.bin of=$W/head.bin bs=512 seek=64 conv=notrunc status=none
[ $(( 64 * 512 + $(stat -c %s $W/idb.bin) )) -le $((0x2000 * 512)) ] || { echo "IDB too big"; exit 1; }
region head 0 $W/head.bin
region security $((0x2000)) zero:$((0x2000 * 512))
region uboot $((0x4000)) $SRC/uboot.img
region trust $((0x6000)) $SRC/trust.img
region misc $((0x8000)) zero:$((0x2000 * 512))
python3 -I - $SRC/dtbo.img $W/dtbo.img $BOOTDEV <<'PY'
import sys
b = open(sys.argv[1], 'rb').read()
old, new = b'androidboot.boot_devices=ff520000.dwmmc', ('androidboot.boot_devices=' + sys.argv[3]).encode()
assert b.count(old) == 1, 'dtbo: boot_devices not found exactly once'
b = b.replace(old, new)
open(sys.argv[2], 'wb').write(b)
PY
region dtbo $((0xa000)) $W/dtbo.img
region vbmeta $((0xc000)) $SRC/vbmeta.img
region boot $((0xc800)) $W/boot.img
region recovery $((0x20800)) $W/recovery.img
region cache $((0x110800)) $W/cache.img
region metadata $((0x1d0800)) $W/metadata.img
region baseparameter $((0x1d8800)) $SRC/baseparameter.img
region logo $((0x1d9000)) zero:1048576
region super $((0x1e1000)) $W/super.raw
[ "$MEDIA" = sd ] && region tvbox_emmc $IMGP_START $W/tvbox_emmc.img
region userdata $UDATA_START zero:$((16 * 1048576))
region gptb $((SD_SECTORS - 33)) $W/gpt-backup.bin
echo "$(grep '^ro.tvbox.release=' $0 | cut -d= -f2) $(date +%F) $MEDIA" > $O/release.txt
log "compress"
for f in $O/regions/*.bin; do gzip -1 -f "$f"; done
cat $M
du -sh $O/regions
