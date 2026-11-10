#!/system/bin/sh
# Служба bootanim удерживает загрузку до готовности рабочего стола.
# Рисующий процесс запускается отдельно после готовности APEX через tvbox.rc.
while [ "$(getprop service.bootanim.exit)" != 1 ]; do sleep 0.2; done
