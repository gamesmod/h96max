#!/system/bin/sh
# Snapshot real device metrics and present a short, static startup report after firstboot provisions the UI.
D=/data/vendor/tvbox
S=/data/media/0/tvbox
mkdir -p "$S"
RAW="$D/perf-snapshot.txt"
CLASSPATH=/system/framework/tvboxd.jar /system/bin/app_process /system/bin org.tvbox.daemon.Main perf > "$RAW" 2>/dev/null
value() { sed -n "s/^$1 //p" "$RAW" | head -n 1; }
CPU=$(value CPU); CORES=$(value CORES | tr ',' '/'); FREQ=$(value CPU_MHZ)
GPU=$(value GPU); TEMP=$(value TEMP); LOAD=$(value LOAD); UPTIME=$(value UPTIME)
MEM=$(value MEM_MB); set -- $MEM; MEM_USED=${1:-?}; MEM_TOTAL=${2:-?}
SWAP=$(value SWAP_MB); set -- $SWAP; SWAP_USED=${1:-?}; SWAP_TOTAL=${2:-?}
ZRAM=$(value ZRAM_ACTIVE_MB)
DISK=$(df -k /data 2>/dev/null | awk 'END{if(NF>=5)printf "%d MB free · %s used",$4/1024,$5}')
TOP=$(sed -n 's/^PROC //p' "$RAW" | head -n 3 | tr '\n' ';')
STAMP=$(date '+%H:%M:%S')
{
    echo "Снимок: $(date '+%d.%m.%Y %H:%M:%S')"
    echo "Устройство: PANADONIK · Rockchip RK3318 · Android 12"
    echo "Вывод HDMI: 1920 × 1080 · GPU: Mali-450 MP"
    echo "$STAMP  ПРОЦЕССОР: ${CPU:-?}% · ${FREQ:-?} МГц · ядра ${CORES:-?}% · нагрузка ${LOAD:-?}"
    echo "$STAMP  ГРАФИКА: ${GPU:-?}% · аппаратное ускорение Mali"
    echo "$STAMP  ПАМЯТЬ: ${MEM_USED:-?} / ${MEM_TOTAL:-?} МБ · zRAM ${ZRAM:-?} МБ · swap ${SWAP_USED:-?}/${SWAP_TOTAL:-?} МБ"
    echo "$STAMP  ТЕМПЕРАТУРА: ${TEMP:-?} °C · время работы ${UPTIME:-?} с"
    echo "$STAMP  НАКОПИТЕЛЬ: свободно ${DISK:-?}"
    [ -n "$TOP" ] && echo "$STAMP  НАИБОЛЬШАЯ ПАМЯТЬ: $TOP"
    echo ""
    echo "ЭТАПЫ ЗАПУСКА"
    awk '/^== boot/{delete lines; n=0; next} {lines[++n]=$0} END {start=n-5; if(start<1)start=1; for(i=start;i<=n;i++)print lines[i]}' "$D/firstboot.log" 2>/dev/null
} > "$S/startup-summary.txt"
chown media_rw:media_rw "$S/startup-summary.txt" 2>/dev/null
chmod 0664 "$S/startup-summary.txt"
restorecon "$S/startup-summary.txt" 2>/dev/null
sync
if pm path org.tvbox.bootlog >/dev/null 2>&1; then
    am start -n org.tvbox.bootlog/.MainActivity --ez startup true >/dev/null 2>&1
fi
