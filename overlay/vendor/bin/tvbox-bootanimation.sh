#!/system/bin/sh
# Ждём загрузки ART, затем показываем поверх экрана живой журнал старта.
i=0
while [ "$i" -lt 300 ]; do
    # activated наступает раньше настройки linkerconfig и не гарантирует запуск app_process.
    if [ "$(getprop apexd.status)" = ready ] \
        && [ -r /apex/com.android.art/lib64/libnativeloader.so ] \
        && grep -q com_android_art /linkerconfig/ld.config.txt 2>/dev/null; then
        break
    fi
    [ "$(getprop service.bootanim.exit)" = 1 ] && exit 0
    sleep 0.1
    i=$((i + 1))
done

if [ "$i" -lt 300 ]; then
    export CLASSPATH=/system/framework/tvbox-bootscreen.jar
    /system/bin/app_process /system/bin org.tvbox.bootscreen.Main
else
    log -t tvbox-bootanimation "ART не подготовлен: экран загрузки не запущен"
fi

# При сбое не запускаем штатную заставку с остаточным логотипом.
log -t tvbox-bootanimation "Экран загрузки завершился; ожидаю завершения старта Android"
while [ "$(getprop service.bootanim.exit)" != 1 ]; do sleep 0.2; done
