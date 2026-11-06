#!/system/bin/sh
# Stock A9 clock writer; A12 renamed the compatible display node.+# VfdService still manages the display's status icons.
set -eu
[ -c /dev/digital_dis ] || exit 1
if [ ! -e /dev/fd628_dev ]; then
    ln -s /dev/digital_dis /dev/fd628_dev
fi
[ "$(readlink -f /dev/fd628_dev)" = /dev/digital_dis ] || exit 1
exec /system/bin/tvbox-vfdsrv
