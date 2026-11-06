#!/bin/bash
# Recreate sdk/ (the toolchain for apps/*) from sdk/debs and the vendor base, without installing anything system-wide:
#   sdk/x         android-23 platform (android.jar) + dx (dalvik-exchange) + apksigner/apksig (tools/apkcert.sh)
#                 <- sdk/debs/{android-sdk-platform-23,libandroid-23-java,dalvik-exchange,apksigner,libapksig-java}*.deb
#   sdk/jdk       OpenJDK 21 (javac --release 8)                               <- sdk/debs/openjdk-21-{jdk,jre}-headless*.deb
#   sdk/rk        rkdeveloptool (Rockchip rockusb, tools/flash-emmc-usb.sh) <- sdk/debs/rkdeveloptool_*.deb
#   sdk/framework framework-res.apk of the vendor A12 (aapt -I for A12 attributes) <- dl/a12/Image/lp/system*.img
# Debs missing from sdk/debs are fetched with `apt-get download` (Ubuntu archive). --check only verifies.
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd); S=$R/sdk; D=$S/debs
JBIN=$S/jdk/usr/lib/jvm/java-21-openjdk-amd64/bin
check() {
    [ -x $JBIN/javac ] && $JBIN/javac -version >/dev/null 2>&1 \
        && [ -f $S/x/usr/lib/android-sdk/platforms/android-23/android.jar ] \
        && [ -f $S/x/usr/lib/android-sdk/build-tools/debian/lib/dx.jar ] \
        && [ -f $S/framework/framework-res.apk ] && [ -f $S/x/usr/share/java/apksig.jar ] && command -v aapt >/dev/null
}
if [ "${1:-}" = --check ]; then check && { echo "sdk ok"; exit 0; } || { echo "sdk incomplete: run tools/setup-sdk.sh"; exit 1; }; fi
mkdir -p $D
(cd $D
 ls android-sdk-platform-23_*.deb libandroid-23-java_*.deb dalvik-exchange_*.deb apksigner_*.deb libapksig-java_*.deb >/dev/null 2>&1 \
     || apt-get download android-sdk-platform-23 libandroid-23-java dalvik-exchange apksigner libapksig-java
 ls openjdk-21-jdk-headless_*.deb openjdk-21-jre-headless_*.deb >/dev/null 2>&1 \
     || apt-get download openjdk-21-jdk-headless openjdk-21-jre-headless
 ls rkdeveloptool_*.deb >/dev/null 2>&1 || apt-get download rkdeveloptool)
rm -rf $S/x $S/jdk; mkdir -p $S/x $S/jdk
for d in $D/android-sdk-platform-23_*.deb $D/libandroid-23-java_*.deb $D/dalvik-exchange_*.deb $D/apksigner_*.deb $D/libapksig-java_*.deb; do dpkg-deb -x $d $S/x; done
for d in $D/openjdk-21-*.deb; do dpkg-deb -x $d $S/jdk; done
rm -rf $S/rk; mkdir -p $S/rk; dpkg-deb -x $D/rkdeveloptool_*.deb $S/rk
# the JDK's conf/security files are absolute links into /etc/java-21-openjdk: point them at the unpacked copy
find $S/jdk/usr/lib/jvm/java-21-openjdk-amd64 -type l -lname '/etc/*' | while read -r l; do ln -sfn "$S/jdk$(readlink "$l")" "$l"; done
mkdir -p $S/framework
SYS=$R/dl/a12/Image/lp/system.img
debugfs -R "dump /system/framework/framework-res.apk $S/framework/framework-res.apk" "$SYS" >/dev/null 2>&1
check && echo "sdk ok" || { echo "sdk setup failed"; exit 1; }
