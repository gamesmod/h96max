#!/system/bin/sh
# Запускается после каждого boot_completed; каждый шаг можно безопасно повторять.
D=/data/vendor/tvbox
mkdir -p $D
exec >>$D/firstboot.log 2>&1
echo "== boot $(date)"
mkdir -p /sdcard/tvbox
if [ ! -f /sdcard/tvbox/obtainium-apps.json ] && [ -f /product/tvbox/obtainium-apps.json ]; then
    cp /product/tvbox/obtainium-apps.json /sdcard/tvbox/obtainium-apps.json
fi
settings put global device_provisioned 1
settings put secure user_setup_complete 1
settings put global adb_enabled 1
# На этой приставке не использовать случайный MAC ни для одной Wi-Fi-сети.
settings put global wifi_connected_mac_randomization_enabled 0
# Отключить анимации окон, переходов и свойств интерфейса Android TV.
settings put global window_animation_scale 0
settings put global transition_animation_scale 0
settings put global animator_duration_scale 0
# Без Google: не проверять connectivitycheck.gstatic.com, чтобы не получать статус частичного подключения.
settings put global captive_portal_mode 0
settings put global captive_portal_detection_enabled 0
# Всегда использовать тёмную тему.
cmd uimode night yes
# Для побитового HDMI-звука выставить максимальную громкость Android; уровень меняется на ТВ через CEC.
cmd media_session volume --stream 3 --set 15 >/dev/null 2>&1

if [ ! -f $D/wifi.done ] && [ -f /vendor/etc/tvbox/wifi.env ]; then
    . /vendor/etc/tvbox/wifi.env
    cmd wifi set-wifi-enabled enabled
    sleep 8
    if cmd wifi connect-network "$SSID0" wpa2 "$PSK0" -r none >/dev/null 2>&1 || cmd wifi connect-network "$SSID0" wpa2 "$PSK0" >/dev/null; then
        touch $D/wifi.done
        echo "wifi: network added"
    else
        echo "wifi: connect-network failed"
    fi
    unset SSID0 PSK0
fi

for apk in /product/tvbox/apk/*.apk; do
    [ -f "$apk" ] || continue
    pkg=$(basename "$apk" .apk)
    if ! pm path "$pkg" >/dev/null 2>&1; then
        echo "install $pkg: $(pm install -r "$apk" 2>&1)"
    fi
done
# В сборке без игр удалить их копии, оставшиеся в /data от прежних выпусков.
for p in com.agateau.tinywheels.android org.supertux.supertux2 app.flicky; do
    if pm path "$p" >/dev/null 2>&1; then pm uninstall --user 0 "$p"; fi
done
appops set dev.imranr.obtainium REQUEST_INSTALL_PACKAGES allow
# VLC нужен полный доступ к файлам для локальных папок и USB-накопителей.
appops set org.videolan.vlc MANAGE_EXTERNAL_STORAGE allow
# Hypatia сканирует общие файлы пользователя.
appops set org.maintainteam.hypatia MANAGE_EXTERNAL_STORAGE allow
# «Журнал загрузки» читает /sdcard/tvbox/*.txt; без runtime-разрешения A12 возвращает EACCES.
pm grant org.tvbox.bootlog android.permission.READ_EXTERNAL_STORAGE 2>/dev/null
# «ТВ-каналы» записывает плейлист для VLC в /sdcard/tvbox/iptv-now.m3u, чтобы переключать CH+/CH−.
appops set org.tvbox.iptv MANAGE_EXTERNAL_STORAGE allow
# Total Commander получает доступ ко всем файлам, чтобы не спрашивать при каждом запуске.
appops set com.ghisler.android.TotalCommander MANAGE_EXTERNAL_STORAGE allow
# Один раз выключить субтитры VLC по умолчанию; последующие настройки пользователя сохраняются.
V=/data/data/org.videolan.vlc/shared_prefs/org.videolan.vlc_preferences.xml
if pm path org.videolan.vlc >/dev/null 2>&1 && ! grep -q subtitle_preferred_language $V 2>/dev/null; then
    am force-stop org.videolan.vlc
    mkdir -p ${V%/*}
    { grep -v '</map>' $V 2>/dev/null || printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
      echo '    <string name="subtitle_preferred_language">none</string>'; echo '</map>'; } > $V.new && mv $V.new $V
    u=$(stat -c %u /data/data/org.videolan.vlc)
    chown $u:$u ${V%/*} $V; chmod 771 ${V%/*}; chmod 660 $V; restorecon -R ${V%/*}
    echo "vlc: subtitles off by default"
fi
# Один раз установить VLC сетевой буфер 3 секунды для IPTV и торрентов по Wi‑Fi; пользователь может его изменить.
if pm path org.videolan.vlc >/dev/null 2>&1 && ! grep -q network_caching_value $V 2>/dev/null; then
    am force-stop org.videolan.vlc
    mkdir -p ${V%/*}
    { grep -v '</map>' $V 2>/dev/null || printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
      echo '    <int name="network_caching_value" value="3000" />'; echo '    <string name="network_caching">3000</string>'
      echo '</map>'; } > $V.new && mv $V.new $V
    u=$(stat -c %u /data/data/org.videolan.vlc)
    chown $u:$u ${V%/*} $V; chmod 771 ${V%/*}; chmod 660 $V; restorecon -R ${V%/*}
    echo "vlc: network caching 3000 ms"
fi
# Без настроек SmartTube выбирает 4K60 VP9, которое тормозит на этом RK3328. Один раз задать предел 1080p30 AVC.
# Формат PlayerData.restoreState проекта yuliskov/SmartTube: поля разделены %OB%; поле 9 — ExoFormatItem, 12 — буфер.
S=/data/data/org.smarttube.stable/files/app_prefs/video_player_data
if pm path org.smarttube.stable >/dev/null 2>&1 && [ ! -e $S ]; then
    am force-stop org.smarttube.stable
    mkdir -p ${S%/*}
    printf '%s' '0%OB%3%OB%null%OB%1%OB%false%OB%true%OB%true%OB%0%OB%null%OB%0,0,null,avc,1920,1080,30.0,null,true,-1,false%OB%null%OB%null%OB%0' > $S
    u=$(stat -c %u /data/data/org.smarttube.stable)
    chown $u:$u /data/data/org.smarttube.stable/files ${S%/*} $S; chmod 700 /data/data/org.smarttube.stable/files ${S%/*}; chmod 600 $S
    restorecon -R /data/data/org.smarttube.stable/files
    echo "smarttube: video preset 1080p30 avc"
fi
# Выдать штатным приложениям необходимые разрешения заранее, чтобы запросы не появлялись на экране ТВ.
# Микрофон разрешается только Алисе, если она установлена; камера, геолокация и телефон приложениям не разрешены.
g() { pm path $1 >/dev/null 2>&1 || return 0; shift_pkg=$1; shift; for perm in "$@"; do pm grant $shift_pkg android.permission.$perm 2>/dev/null; done; }
g org.videolan.vlc READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE BLUETOOTH_CONNECT
g ru.yourok.torrserve READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE
g org.smarttube.stable READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE
g org.maintainteam.hypatia READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE
# Алиса получает доступ к микрофону только если пользователь установил приложение.
g com.yandex.aliceapp RECORD_AUDIO
if pm path com.yandex.aliceapp >/dev/null 2>&1; then
    # Применить разрешение и роль после установки Алисы, даже если её поставили уже после запуска системы.
    appops set com.yandex.aliceapp RECORD_AUDIO allow
    A=$(cmd role get-role-holders --user 0 android.app.role.ASSISTANT 2>/dev/null)
    case "$A" in *com.yandex.aliceapp*) ;; *)
        cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.yandex.aliceapp >/dev/null 2>&1
    esac
    A=$(cmd role get-role-holders --user 0 android.app.role.ASSISTANT 2>/dev/null)
    case "$A" in *com.yandex.aliceapp*) echo "alice: default assistant ready; USB voice key uses VOICE_ASSIST";; *) echo "alice: enable as default assistant in Android settings";; esac
fi
for p in com.ghisler.android.TotalCommander com.ghisler.tcplugins.LAN com.ghisler.tcplugins.FTP com.ghisler.tcplugins.SFTP \
         com.ghisler.tcplugins.wifitransfer; do g $p READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE; done
pm path ru.yourok.torrserve >/dev/null 2>&1 && appops set ru.yourok.torrserve MANAGE_EXTERNAL_STORAGE allow
# Один раз выбрать VLC для TorrServe, чтобы убрать диалог выбора плеера (ключ player содержит пакет приложения).
TS=/data/data/ru.yourok.torrserve/shared_prefs/ru.yourok.torrserve_preferences.xml
if pm path ru.yourok.torrserve >/dev/null 2>&1 && pm path org.videolan.vlc >/dev/null 2>&1 && ! grep -q 'name="player"' $TS 2>/dev/null; then
    am force-stop ru.yourok.torrserve
    mkdir -p ${TS%/*}
    { grep -v '</map>' $TS 2>/dev/null || printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
      echo '    <string name="player">org.videolan.vlc</string>'; echo '</map>'; } > $TS.new && mv $TS.new $TS
    u=$(stat -c %u /data/data/ru.yourok.torrserve)
    chown $u:$u ${TS%/*} $TS; chmod 771 ${TS%/*}; chmod 660 $TS; restorecon -R ${TS%/*}
    echo "torrserve: player = VLC"
fi
# Назначить TV Launcher главным экраном и пропустить штатный мастер первого запуска.
pm path com.tvlauncher >/dev/null 2>&1 && cmd package set-home-activity com.tvlauncher/.MainActivity
# Плитки TV Launcher. U+001E сохраняется как &#30; (AppManager.ORDER_DELIMITER проекта wiiguy/lightweight_androidtv_launcher).
# Сверить плитки один раз после чистой установки; дальнейшие изменения пользователя сохранять.
L=/data/data/com.tvlauncher/shared_prefs/selected_apps.xml
if pm path com.tvlauncher >/dev/null 2>&1 && [ ! -e "$D/launcher-tiles.initialized" ]; then
    # Атомарно записать настройки до остановки HOME; особенности запуска описаны в HANDOFF.md.
    OLD=$(sed -n 's:.*<string name="apps_order">\([^<]*\)</string>.*:\1:p' "$L" 2>/dev/null | head -n 1)
    ALLOWED="org.tvbox.iptv ru.yourok.torrserve org.smarttube.stable com.ghisler.android.TotalCommander dev.imranr.obtainium org.tvbox.links org.tvbox.installer org.tvbox.bootlog com.android.tv.settings org.tvbox.clean org.tvbox.power"
    T=""; EXTRA=""; N=0
    # Сохранить установленные функции и их порядок; удалить дубли, игры и устаревшие плитки.
    for p in $(printf '%s' "$OLD" | sed 's/\&\#30;/ /g'); do
        case "$p" in com.agateau.tinywheels.android|org.supertux.supertux2) continue;; esac
        pm path "$p" >/dev/null 2>&1 || continue
        case " $T $EXTRA " in *" $p "*) continue;; esac
        case " $ALLOWED " in *" $p "*) T="${T:+$T&#30;}$p"; N=$((N+1));; *) EXTRA="${EXTRA:+$EXTRA }$p";; esac
    done
    # Добавить отсутствующие штатные плитки в описанном порядке (по умолчанию 11; лаунчер поддерживает 14).
    for p in $ALLOWED; do
        pm path "$p" >/dev/null 2>&1 || continue
        case " $T " in *" $p "*) ;; *)
            if [ "$N" -lt 14 ]; then T="${T:+$T&#30;}$p"; N=$((N+1)); fi;; esac
    done
    # После штатных функций сохранить не более одной пользовательской плитки.
    for p in $EXTRA; do
        case " $T " in *" $p "*) ;; *) if [ "$N" -lt 14 ]; then T="${T:+$T&#30;}$p"; N=$((N+1)); fi;; esac
    done
    if [ "$T" != "$OLD" ] || [ ! -f "$L" ]; then
        mkdir -p "${L%/*}"
        if [ -f "$L" ]; then
            REPL=$(printf '%s' "$T" | sed 's/\&/\\\&/g')
            if grep -q '<string name="apps_order">' "$L"; then
                sed "s|<string name=\"apps_order\">[^<]*</string>|<string name=\"apps_order\">$REPL</string>|" "$L" > "$L.new"
            else
                sed "s|</map>|    <string name=\"apps_order\">$REPL</string>\n</map>|" "$L" > "$L.new"
            fi
        else
            printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"apps_order\">%s</string>\n</map>\n" "$T" > "$L.new"
        fi
        u=$(stat -c %u /data/data/com.tvlauncher)
        chown "$u:$u" "${L%/*}" "$L.new"; chmod 771 "${L%/*}"; chmod 660 "$L.new"; mv "$L.new" "$L"; restorecon -R "${L%/*}"
        echo "tvlauncher: repaired tile order ($N installed tiles)"
        am force-stop com.tvlauncher
        am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
    fi
    touch "$D/launcher-tiles.initialized"
fi

# Однократно вернуть журнал загрузки в существующую раскладку, не меняя порядок пользователя.
if pm path com.tvlauncher >/dev/null 2>&1 && pm path org.tvbox.bootlog >/dev/null 2>&1 &&
        [ ! -e "$D/launcher-bootlog-tile-added" ] && [ -f "$L" ]; then
    OLD=$(sed -n 's:.*<string name="apps_order">\([^<]*\)</string>.*:\1:p' "$L" 2>/dev/null | head -n 1)
    OLD_SPACED=$(printf '%s' "$OLD" | sed 's/\&#30;/ /g')
    case " $OLD_SPACED " in *" org.tvbox.bootlog "*) touch "$D/launcher-bootlog-tile-added";;
        *)
            N=$(printf '%s' "$OLD" | sed 's/\&#30;/ /g' | wc -w)
            if [ "$N" -lt 14 ]; then
                T="${OLD:+$OLD&#30;}org.tvbox.bootlog"
                REPL=$(printf '%s' "$T" | sed 's/\&/\\\&/g')
                sed "s|<string name=\"apps_order\">[^<]*</string>|<string name=\"apps_order\">$REPL</string>|" "$L" > "$L.new"
                u=$(stat -c %u /data/data/com.tvlauncher)
                chown "$u:$u" "$L.new"; chmod 660 "$L.new"; mv "$L.new" "$L"; restorecon "$L"
                am force-stop com.tvlauncher
                am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
                echo "tvlauncher: added boot log tile without changing user order"
            else
                echo "tvlauncher: full tile row; boot log tile not added"
            fi
            touch "$D/launcher-bootlog-tile-added";;
    esac
fi
# При переходе с Flicky на Obtainium заменить только старый пакет плитки и сохранить её позицию.
L=/data/data/com.tvlauncher/shared_prefs/selected_apps.xml
if pm path com.tvlauncher >/dev/null 2>&1 && [ ! -e "$D/launcher-flicky-removed" ] && [ -f "$L" ]; then
    OLD=$(sed -n 's:.*<string name="apps_order">\([^<]*\)</string>.*:\1:p' "$L" 2>/dev/null | head -n 1)
    T=""; N=" "
    for p in $(printf '%s' "$OLD" | sed 's/\&\#30;/ /g'); do
        [ "$p" = app.flicky ] && p=dev.imranr.obtainium
        pm path "$p" >/dev/null 2>&1 || continue
        case "$N" in *" $p "*) continue;; esac
        T="${T:+$T&#30;}$p"; N="$N$p "
    done
    if [ "$T" != "$OLD" ]; then
        REPL=$(printf '%s' "$T" | sed 's/\&/\\\&/g')
        sed "s|<string name=\"apps_order\">[^<]*</string>|<string name=\"apps_order\">$REPL</string>|" "$L" > "$L.new"
        u=$(stat -c %u /data/data/com.tvlauncher)
        chown "$u:$u" "${L%/*}" "$L.new"; chmod 771 "${L%/*}"; chmod 660 "$L.new"
        mv "$L.new" "$L"; restorecon -R "${L%/*}"
        am force-stop com.tvlauncher
        am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
        echo "tvlauncher: replaced Flicky tile with Obtainium"
    fi
    touch "$D/launcher-flicky-removed"
fi
pm disable com.mbx.settingsmbox/.OobeActivity >/dev/null 2>&1
# Показать поверх экрана запуска краткую сводку ресурсов с временными метками; TV Launcher остаётся HOME-приложением.
/vendor/bin/tvbox-startup-summary.sh &
echo "done"
