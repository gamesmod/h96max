#!/system/bin/sh
# Boot error screen: started with the boot animation (graphics are up by then).
D=/mnt/tvbox_fbdev
mkdir -p $D
mountpoint -q $D || mount -t devtmpfs devtmpfs $D
exec /vendor/bin/fblog $D 1
