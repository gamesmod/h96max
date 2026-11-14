# rk3318-android: исторический снимок сборки (снято 2026-10-26)

> Архивный снимок, не инструкция для текущей сборки. SD-образ исключён из проекта; старые SD-команды, отключение ADB-авторизации и состав ПО ниже не применять. Актуальные команды и условия — `docs/LUNA-WORKFLOW.md`, статус — в `docs/release-qa/`.

## Метод сборки (хост)
- Исходник: вендорский A12 `RK3318_userdebug_H96_Max_V12_12_20221117.1447` → `dl/a12/Image` (распакован `tools/rkunpack.py`), super распакован в `dl/a12/Image/lp/` (`tools/lpunpack.py`).
- Образ: `./build-sd.sh` (SD, по умолчанию) или `MEDIA=emmc ./build-sd.sh` (вариант eMMC → `build/out-emmc`).
  Нужны: sgdisk e2fsck resize2fs debugfs python3 gzip setfattr, sudo (loop-mount), aarch64-linux-gnu-gcc (fblog).
  Выход: `build/out/regions/NN-name.bin.gz` + `build/out/manifest.txt` (offset_sector size file md5).
- Запись на SD (с приставки, загруженной не с SD): `device/sd-write.sh` (проверки ff500000, размер 31116288, не смонтировано; верификация чанками по 64 MiB).
- Что делает build-sd.sh: правит cmdline boot/recovery (boot_devices → ff500000 для SD, wificountrycode US), dtbo (bootargs_ext boot_devices), fstab (без FBE, без SD voldmanaged, /metadata без first_stage_mount) в ramdisk и vendor; recovery.fstab без eMMC; удаляет GMS/Google и вендорский мусор; ставит WebView LineageOS, APK в /product/tvbox/apk, свои приложения, TorrServer, bootanimation; vendor: tvbox.rc + скрипты; Wi-Fi credentials не вшиваются; прошивки Wi‑Fi/BT от A9 (a9fw/), RRO TvboxWifiOverlay, pmf=0/oce=0, baseparameter by-name, блокировка rpmb; модель panadonik; сборка super 4 GiB (`tools/lpbuild.py`), IDB (`tools/rkidb.py`, sdboot_tag=1), GPT (как вендор, super 0x800000 сек.).
- **Сборка выпуска с нуля одной командой:** `tools/build-release.sh` (версия = `ro.tvbox.release` в build-sd.sh, нужен `release/notes/ВЕРСИЯ.txt`): тулчейн → все свои приложения → ota/tvbox-flash → `rm -rf build` → eMMC-образ → SD-образ → `release/ВЕРСИЯ/{sd,emmc}` + SHA256SUMS → подписанные OTA.
- Тулчейн (`tools/setup-sdk.sh`, ничего не ставит в систему): `sdk/jdk` OpenJDK 21 (`javac --release 8`) и `sdk/x` (android-23 android.jar + dx) из `sdk/debs/*.deb` (недостающие — `apt-get download`), `sdk/framework/framework-res.apk` из `dl/a12/Image/lp/system.img` (aapt -I для атрибутов A12, нужен apps/bootlog). Eclipse больше не нужен (удалён с ПК 2026-10-27). Системные: aapt, aarch64-linux-gnu-gcc, sgdisk, e2fsprogs, debugfs, openssl, python3, sudo.
- Свои APK/jar (apps/*/build.sh, у каждого свой): javac → dx → aapt → подпись v1 `tools/v1sign.py` ключом `secrets/tvbox-key.pem`/`tvbox-cert.pem`. apps/allsettings не собирается (убрано из образа).
  Пакеты для sdk/: `apt-get download dalvik-exchange android-sdk-platform-23 libandroid-23-java` + `dpkg -x` в `sdk/x` (раньше лежали в /tmp/r и пропали).
- Бут-анимация Matrix: `tools/mkbootanim.py` → `media/bootanimation.zip` (заменяется экраном логов, см. apps/bootscreen).

## Состав ПО на текущем A12 (живая система, SD)
- Лаунчер: TV Launcher `com.tvlauncher` (домашний по умолчанию, плитки).
- Samba и Termux убраны 2026-10-27 (безопасность + ресурсы): архив, конфиг и APK в dl/apk-removed/.
- Kodi убран 2026-10-27 (пользователь предпочитает VLC): APK и настройки IPTV в dl/apk-removed/; build-sd.sh отключает vendor xbmc_copy_folder и гранты Kodi в preinstall.sh.
- IPTV: своя плитка «ТВ-каналы» `org.tvbox.iptv` (apps/iptv, `./build.sh`) — плейлист из бывших настроек Kodi, кэш в files/, каналы открываются в VLC.
- Пользовательские (из /product/tvbox/apk, ставит tvbox-firstboot.sh): TorrServe `ru.yourok.torrserve`, VLC `org.videolan.vlc`, Obtainium `dev.imranr.obtainium` (+ REQUEST_INSTALL_PACKAGES). Старый клиент Flicky удаляется для пользователя.
- Свои: «Питание» `org.tvbox.power` (/system/priv-app, REBOOT/SHUTDOWN), «Все настройки» `org.tvbox.allsettings`, «Журнал загрузки» `org.tvbox.bootlog` (читает /sdcard/tvbox/).
- WebView: `com.android.webview` (LineageOS prebuilt, /product/app/webview).
- Службы (vendor/etc/init/tvbox.rc): tvbox-emmc-ro.sh (eMMC force_ro при загрузке с SD), tvbox_bootlog (dmesg/logcat → /sdcard/tvbox), tvbox_firstboot, torrserver :8090 (/data/vendor/torrserver), adb tcp 5555.
- Удалено: GMS/Play/YouTube/Google*, /system/preinstall (TikTok, Netflix, Aptoide…), datadis (телеметрия monitor.hcybox.com:41523), user_drmservice (UDP 33334), stress-тесты, katniss, chrome_hcy, RKHcyUpdate и пр. (список в build-sd.sh).

## Настройки
- build.prop: ro.adb.secure=0, ro.setupwizard.mode=DISABLED, persist.sys.timezone=Europe/Moscow, persist.sys.locale=ru-RU, persist.sys.usb.config=adb, persist.sys.bootvideo.enable=false.
- settings: device_name=panadonik, wifi_p2p_device_name=panadonik, device_provisioned=1, user_setup_complete=1, captive_portal_mode=0, wifi_connected_mac_randomization_enabled=0, auto_time=1, screen_off_timeout=2147483647, install_non_market_apps=1, IME LatinIME, OOBE com.mbx.settingsmbox/.OobeActivity отключён.
- Wi‑Fi: страна US (cmdline), WPA3‑апгрейд выключен (RRO), pmf=0/oce=0, сеть пользователя не вшивается в образ.
- SELinux: Permissive (вендорский userdebug).
- Живые отличия от build-sd.sh: ro.product.model на живой ещё H96_Max_V12 (в сборке panadonik); bootlog/allsettings стоят как user-приложения (в сборке /system/app).
