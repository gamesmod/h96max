#!/system/bin/sh
# Журнал загрузки: дождаться готовности общего хранилища и сохранить сводку для экрана диагностики.
D=/cache/tvbox
mkdir -p $D
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done
# После очистки userdata emulated storage может появиться с задержкой после boot_completed.
n=0
while [ $n -lt 60 ] && [ ! -d /data/media/0 ]; do n=$((n + 1)); sleep 1; done
dmesg -r > $D/dmesg.txt 2>&1
logcat -d -b all '*:E' > $D/logcat-errors.txt 2>&1
getprop > $D/props.txt
# Общее хранилище доступно приложению диагностики без root.
S=/data/media/0/tvbox
mkdir -p $S
cp $D/dmesg.txt $S/dmesg.txt
cp $D/logcat-errors.txt $S/logcat-errors.txt
# no RTC: wait (max 2 min) for network time before stamping
t=0; while [ $t -lt 24 ] && ! logcat -d -b events 2>/dev/null | grep -q -iE "ntp|network_time|time_detector"; do t=$((t+1)); sleep 5; done
n=$(( $(cat $D/boots 2>/dev/null || echo 0) + 1 )); echo $n > $D/boots
echo "$(date '+%d.%m.%Y %H:%M'), загрузка №$n, время загрузки $(cut -d. -f1 /proc/uptime) с" > $S/updated.txt
chown -R media_rw:media_rw $S; chmod 0775 $S; chmod 0664 $S/*
restorecon -R $S 2>/dev/null
sync
