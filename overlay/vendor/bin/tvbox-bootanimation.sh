#!/system/bin/sh
# Ждём загрузки ART, затем показываем поверх экрана живой журнал старта.
i=0
while [ "$i" -lt 100 ]; do
    case "$(getprop apexd.status)" in activated|ready) break ;; esac
    sleep 0.1
    i=$((i + 1))
done

export CLASSPATH=/system/framework/tvbox-bootscreen.jar
/system/bin/app_process /system/bin org.tvbox.bootscreen.Main

# При сбое не запускаем штатную заставку с остаточным логотипом.
log -t tvbox-bootanimation "Экран загрузки завершился; ожидаю завершения старта Android"
while [ "$(getprop service.bootanim.exit)" != 1 ]; do sleep 0.2; done
