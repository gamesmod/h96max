#!/system/bin/sh
# Private OTA handoff. No credentials/host addresses are embedded in the image.
set -eu
umask 077
BACKUP=/cache/recovery/tvbox-connectivity
WIFI=/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml
KEYS=/data/misc/adb/adb_keys
STATE=/data/vendor/tvbox/ota-connectivity
POLICY=/data/vendor/tvbox/adb-trusted-host
[ "$(id -u)" = 0 ] || exit 1

fail() { echo 'OTA connectivity: validation/action failed' >&2; exit 1; }
valid_ip() {
    printf '%s\n' "$1" | awk -F. 'NF != 4 {exit 1} {for(i=1;i<=4;i++) if($i !~ /^[0-9]+$/ || $i ~ /^0[0-9]/ || $i>255) exit 1; if(!($1==10 || ($1==172 && $2>=16 && $2<=31) || ($1==192 && $2==168))) exit 1}'
}
valid_port() {
    case "$1" in ''|0*|*[!0-9]*) return 1;; esac
    [ "$1" -gt 0 ] && [ "$1" -le 65535 ]
}
private_dir() {
    [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1")" = 0 ] && [ "$(stat -c %a "$1")" = 700 ]
}
private_file() {
    [ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1")" = 0 ] && [ "$(stat -c %a "$1")" = 600 ]
}
mark_handoff() {
    # Persist the transaction identity before any credential file can be restored.
    cp "$BACKUP/package-sha256" "$1.tmp"
    chmod 600 "$1.tmp"
    sync
    mv "$1.tmp" "$1"
    sync
}
validate_backup() {
    private_dir "$BACKUP" || return 1
    for n in ready wifi.xml adb_keys target-version package-sha256 SHA256SUMS; do
        private_file "$BACKUP/$n" || return 1
    done
    # Only these fixed names may occur in the checksum list.
    awk 'NF!=2 || $1 !~ /^[0-9a-f]+$/ || length($1)!=64 || $2 !~ /^(wifi.xml|adb_keys|target-version|package-sha256|tcp-port|trusted-host)$/ {exit 1}' "$BACKUP/SHA256SUMS" || return 1
    for n in wifi.xml adb_keys target-version package-sha256 tcp-port trusted-host; do
        if [ -e "$BACKUP/$n" ]; then
            private_file "$BACKUP/$n" || return 1
            grep -q "  $n\$" "$BACKUP/SHA256SUMS" || return 1
        fi
    done
    (cd "$BACKUP" && sha256sum -c SHA256SUMS >/dev/null 2>&1) || return 1
    grep -qE '^[0-9a-f]{64}$' "$BACKUP/package-sha256" || return 1
    grep -qE '^[A-Za-z0-9._-]{1,64}$' "$BACKUP/target-version" || return 1
    if [ -e "$BACKUP/trusted-host" ] || [ -e "$BACKUP/tcp-port" ]; then
        [ -s "$BACKUP/trusted-host" ] && [ -s "$BACKUP/tcp-port" ] || return 1
        valid_ip "$(cat "$BACKUP/trusted-host")" && valid_port "$(cat "$BACKUP/tcp-port")" || return 1
    fi
}

case "${1:-}" in
prepare)
    [ "$#" -ge 3 ] && [ "$#" -le 4 ] || fail
    VERSION=$2; SHA=$3
    printf '%s\n' "$VERSION" | grep -qE '^[A-Za-z0-9._-]{1,64}$' || fail
    printf '%s\n' "$SHA" | grep -qE '^[0-9a-f]{64}$' || fail
    if [ -e "$BACKUP" ]; then
        validate_backup && [ "$(cat "$BACKUP/target-version")" = "$VERSION" ] && [ "$(cat "$BACKUP/package-sha256")" = "$SHA" ] || fail
        echo 'OTA connectivity: matching private handoff already prepared'
        exit 0
    fi
    HAS_WIFI=0
    if [ -s "$WIFI" ]; then
        [ ! -L "$WIFI" ] && [ "$(stat -c %s "$WIFI")" -le 1048576 ] || fail
        grep -q '<WifiConfigStoreData' "$WIFI" || fail
        # Keystore-encrypted credentials cannot survive a userdata wipe by copying XML.
        if grep -qE 'EncryptedData|EncryptedPreSharedKey' "$WIFI"; then fail; fi
        if grep -q '<Network>' "$WIFI"; then HAS_WIFI=1; fi
    fi
    if [ "$HAS_WIFI" = 0 ]; then
        # A USB-only system with no saved network must still be updatable.
        STATUS=$(cmd wifi status 2>/dev/null) || fail
        if printf '%s\n' "$STATUS" | grep -qi 'Wifi is connected'; then fail; fi
    fi
    [ -s "$KEYS" ] && [ ! -L "$KEYS" ] && [ "$(stat -c %s "$KEYS")" -le 65536 ] || fail
    awk 'NF {if($1 !~ /^[A-Za-z0-9+\/=]+$/ || length($1)<600 || length($1)>2048) exit 1; n++} END {if(n<1) exit 1}' "$KEYS" || fail
    mkdir -p /cache/recovery
    TMP=$(mktemp -d "$BACKUP.tmp.XXXXXXXX")
    trap '[ -z "${TMP:-}" ] || rm -rf -- "$TMP"' EXIT
    if [ "$HAS_WIFI" = 1 ]; then cp "$WIFI" "$TMP/wifi.xml"; else : > "$TMP/wifi.xml"; fi
    cp "$KEYS" "$TMP/adb_keys"
    printf '%s\n' "$VERSION" > "$TMP/target-version"
    printf '%s\n' "$SHA" > "$TMP/package-sha256"
    PEER=${4:-}
    if [ -n "$PEER" ]; then
        [ -f "$PEER" ] && [ ! -L "$PEER" ] || fail
        HOST=$(cat "$PEER"); valid_ip "$HOST" || fail
        PORT=$(getprop service.adb.tcp.port); valid_port "$PORT" || fail
        [ "$(getprop ro.adb.secure)" = 1 ] || fail
        printf '%s\n' "$HOST" > "$TMP/trusted-host"
        printf '%s\n' "$PORT" > "$TMP/tcp-port"
    elif private_dir "$POLICY"; then
        private_file "$POLICY/trusted-host" && private_file "$POLICY/tcp-port" || fail
        valid_ip "$(cat "$POLICY/trusted-host")" && valid_port "$(cat "$POLICY/tcp-port")" || fail
        cp "$POLICY/trusted-host" "$TMP/trusted-host"; cp "$POLICY/tcp-port" "$TMP/tcp-port"
    fi
    chmod 600 "$TMP"/*
    (cd "$TMP" && sha256sum wifi.xml adb_keys target-version package-sha256 > SHA256SUMS
     if [ -f trusted-host ]; then sha256sum trusted-host tcp-port >> SHA256SUMS; fi)
    : > "$TMP/ready"; chmod 600 "$TMP/ready" "$TMP/SHA256SUMS"
    chown -R 0:0 "$TMP"; chmod 700 "$TMP"
    mv "$TMP" "$BACKUP"; TMP=''
    sync
    validate_backup || fail
    echo 'OTA connectivity: private handoff prepared; no update requested'
    ;;
data)
    [ -e "$BACKUP" ] || exit 0
    validate_backup || fail
    [ "$(getprop ro.tvbox.release)" = "$(cat "$BACKUP/target-version")" ] || exit 0
    # Only fresh userdata or this exact interrupted transaction may receive the handoff.
    # A copied Wi-Fi file must not prevent a retry from restoring missing ADB keys.
    [ ! -e "$STATE/data-restored" ] || exit 0
    RESUMING=0
    if [ -e "$STATE/restore-in-progress" ] || [ -L "$STATE/restore-in-progress" ]; then
        private_dir "$STATE" && private_file "$STATE/restore-in-progress" &&
            cmp -s "$STATE/restore-in-progress" "$BACKUP/package-sha256" || fail
        RESUMING=1
        # Never overwrite credentials changed by the user after a partial restore.
        if [ -e "$WIFI" ] || [ -L "$WIFI" ]; then
            [ -f "$WIFI" ] && [ ! -L "$WIFI" ] && cmp -s "$WIFI" "$BACKUP/wifi.xml" || fail
        fi
        if [ -e "$KEYS" ] || [ -L "$KEYS" ]; then
            [ -f "$KEYS" ] && [ ! -L "$KEYS" ] && cmp -s "$KEYS" "$BACKUP/adb_keys" || fail
        fi
    elif [ -s "$WIFI" ] || [ -s "$KEYS" ]; then
        exit 0
    fi
    if [ -e "$STATE" ] || [ -L "$STATE" ]; then private_dir "$STATE" || fail; fi
    mkdir -p "${KEYS%/*}" "$STATE"
    chown 1000:2000 "${KEYS%/*}"; chmod 2750 "${KEYS%/*}"
    chown 0:0 "$STATE"; chmod 700 "$STATE"
    if [ "$RESUMING" = 0 ]; then mark_handoff "$STATE/restore-in-progress"; fi
    if [ -s "$BACKUP/wifi.xml" ]; then
        mkdir -p "${WIFI%/*}"
        chown 0:1000 "${WIFI%/*}"; chmod 771 "${WIFI%/*}"
        cp "$BACKUP/wifi.xml" "$WIFI.tvbox-restore"; chown 1000:1000 "$WIFI.tvbox-restore"; chmod 600 "$WIFI.tvbox-restore"
        mv "$WIFI.tvbox-restore" "$WIFI"; restorecon -RF "${WIFI%/*}"
        cmp -s "$BACKUP/wifi.xml" "$WIFI" || fail
        : > "$STATE/wifi-restored"; chmod 600 "$STATE/wifi-restored"
    fi
    cp "$BACKUP/adb_keys" "$KEYS.tvbox-restore"; chown 1000:2000 "$KEYS.tvbox-restore"; chmod 640 "$KEYS.tvbox-restore"
    mv "$KEYS.tvbox-restore" "$KEYS"; restorecon -RF "${KEYS%/*}"
    cmp -s "$BACKUP/adb_keys" "$KEYS" || fail
    if [ -s "$BACKUP/trusted-host" ]; then
        mkdir -p "$POLICY"; chown 0:0 "$POLICY"; chmod 700 "$POLICY"
        cp "$BACKUP/trusted-host" "$POLICY/trusted-host"; cp "$BACKUP/tcp-port" "$POLICY/tcp-port"
        chown 0:0 "$POLICY"/*; chmod 600 "$POLICY"/*; restorecon -RF "$POLICY"
    fi
    chown 0:0 "$STATE"; chmod 700 "$STATE"
    mark_handoff "$STATE/data-restored"
    rm -f "$STATE/restore-in-progress"
    restorecon -RF "$STATE"
    sync
    echo 'OTA connectivity: data restored; cache backup retained until sanity acknowledgement'
    ;;
network)
    if [ -f "$STATE/wifi-restored" ] && [ ! -e "$STATE/wifi-enabled" ]; then
        if cmd wifi set-wifi-enabled enabled >/dev/null 2>&1; then
            : > "$STATE/wifi-enabled"; chmod 600 "$STATE/wifi-enabled"
        else
            echo 'OTA connectivity: Wi-Fi enable request pending; manual recovery may be needed' >&2
        fi
    fi
    # Default images have no policy and do not start TCP ADB.
    [ -e "$POLICY" ] || exit 0
    private_dir "$POLICY" && private_file "$POLICY/trusted-host" && private_file "$POLICY/tcp-port" || fail
    HOST=$(cat "$POLICY/trusted-host"); PORT=$(cat "$POLICY/tcp-port")
    valid_ip "$HOST" && valid_port "$PORT" && [ "$(getprop ro.adb.secure)" = 1 ] || fail
    command -v iptables >/dev/null && command -v ip6tables >/dev/null || fail
    # Install both families' restrictions before enabling any listening port.
    # Existing chains are not flushed: a partial/unknown policy fails closed.
    if ! iptables -nL TVBOX_OTA_ADB4 >/dev/null 2>&1; then
        iptables -N TVBOX_OTA_ADB4
        iptables -A TVBOX_OTA_ADB4 -s "$HOST" -j ACCEPT
        iptables -A TVBOX_OTA_ADB4 -j DROP
    fi
    RULES4=$(iptables -S TVBOX_OTA_ADB4 | sed -n '/^-A /p')
    EXPECT4=$(printf '%s\n' "-A TVBOX_OTA_ADB4 -s $HOST/32 -j ACCEPT" '-A TVBOX_OTA_ADB4 -j DROP')
    [ "$RULES4" = "$EXPECT4" ] || fail
    if ! ip6tables -nL TVBOX_OTA_ADB6 >/dev/null 2>&1; then
        ip6tables -N TVBOX_OTA_ADB6
        ip6tables -A TVBOX_OTA_ADB6 -j DROP
    fi
    RULES6=$(ip6tables -S TVBOX_OTA_ADB6 | sed -n '/^-A /p')
    [ "$RULES6" = '-A TVBOX_OTA_ADB6 -j DROP' ] || fail
    # Prepend restrictions even if an older identical jump exists lower in INPUT.
    iptables -I INPUT 1 -p tcp --dport "$PORT" -j TVBOX_OTA_ADB4
    ip6tables -I INPUT 1 -p tcp --dport "$PORT" -j TVBOX_OTA_ADB6
    OLD_PORT=$(getprop service.adb.tcp.port)
    setprop service.adb.tcp.port "$PORT"
    if [ "$OLD_PORT" != "$PORT" ]; then setprop ctl.restart adbd; fi
    echo 'OTA connectivity: authenticated network debugging restricted to saved host'
    ;;
acknowledge)
    validate_backup || fail
    [ "$(getprop ro.tvbox.release)" = "$(cat "$BACKUP/target-version")" ] || fail
    [ -s "$STATE/data-restored" ] && cmp -s "$STATE/data-restored" "$BACKUP/package-sha256" || fail
    rm -rf -- "$BACKUP"
    echo 'OTA connectivity: private cache handoff removed after acknowledgement'
    ;;
*) fail;;
esac
