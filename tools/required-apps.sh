#!/bin/bash
# Эти APK нужны на приставке после чистой установки и обновления с очисткой.
TVBOX_REQUIRED_APKS=(
    com.tvlauncher.apk
    com.ghisler.android.TotalCommander.apk
    com.ghisler.tcplugins.FTP.apk
    com.ghisler.tcplugins.LAN.apk
    com.ghisler.tcplugins.SFTP.apk
    dev.imranr.obtainium.apk
    org.maintainteam.hypatia.apk
    org.videolan.vlc.apk
    ru.yourok.torrserve.apk
)

tvbox_require_apks() {
    local name missing=0
    for name in "${TVBOX_REQUIRED_APKS[@]}"; do
        if [ ! -s "$R/dl/apk/$name" ]; then
            echo "Отсутствует обязательное приложение: $name" >&2
            missing=1
        fi
    done
    [ "$missing" -eq 0 ]
}
