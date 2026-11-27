#!/system/bin/sh
# Seed the offline model once. Preserve later app settings, permissions and assistant choice.
set -eu
ASSETS=${TVBOX_DICIO_ASSETS:-/product/tvbox/dicio}
FILES=${TVBOX_DICIO_FILES:-/data/user/0/org.stypox.dicio/files}
BRIDGE_DATA=${TVBOX_VOICE_DATA:-/data/user/0/org.tvbox.voicebridge}
STATE=${TVBOX_VOICE_STATE:-/data/vendor/tvbox/voice-setup.done}
pm path org.stypox.dicio >/dev/null 2>&1 || exit 0
pm path org.tvbox.voicebridge >/dev/null 2>&1 || exit 0
[ -f "$ASSETS/settings.pb" ] && [ -d "$ASSETS/vosk-model/ivector" ] || {
    echo 'voice: offline assets missing'; exit 1;
}
for p in "$FILES" "$FILES/datastore" "$FILES/datastore/settings.pb" "$FILES/vosk-model" "$FILES/vosk-model-url"; do
    [ ! -L "$p" ] || { echo 'voice: symlink in app data; unchanged'; exit 1; }
done
if [ -e "$FILES/vosk-model" ] && [ ! -d "$FILES/vosk-model/ivector" ]; then
    echo 'voice: existing model incomplete; unchanged'; exit 1
fi
u=$(stat -c %u "${FILES%/files}")
v=$(stat -c %u "$BRIDGE_DATA")
case "$u:$v" in *[!0-9:]*|:|:*|*:) echo 'voice: invalid app identity'; exit 1;; esac
changed=0
if [ ! -e "$FILES/datastore/settings.pb" ] || [ ! -e "$FILES/vosk-model" ]; then
    # Only initial seeding may stop Dicio; normal reboots never interrupt an active session.
    am force-stop org.stypox.dicio
    mkdir -p "$FILES" "$FILES/datastore"
    chown "$u:$u" "$FILES" "$FILES/datastore"
    if [ ! -e "$FILES/datastore/settings.pb" ]; then
        cp "$ASSETS/settings.pb" "$FILES/datastore/settings.pb"
        chown "$u:$u" "$FILES/datastore/settings.pb"
        chmod 600 "$FILES/datastore/settings.pb"
        changed=1
    fi
    if [ ! -e "$FILES/vosk-model" ]; then
        cp -R "$ASSETS/vosk-model" "$FILES/vosk-model"
        cp "$ASSETS/vosk-model-url" "$FILES/vosk-model-url"
        chown -R "$u:$u" "$FILES/vosk-model" "$FILES/vosk-model-url"
        changed=1
    fi
    restorecon -R "$FILES"
fi
# Dicio's upstream debug tag includes transcripts; do not include them in device logs.
setprop log.tag.VoskListener WARN
setprop persist.log.tag.VoskListener WARN
identity="$u:$v"
if [ "$changed" = 1 ] || [ ! -f "$STATE" ] || [ "$(cat "$STATE")" != "$identity" ]; then
    pm grant org.stypox.dicio android.permission.RECORD_AUDIO
    pm grant org.tvbox.voicebridge android.permission.RECORD_AUDIO
    appops set org.tvbox.voicebridge SYSTEM_ALERT_WINDOW allow
    cmd role add-role-holder --user 0 android.app.role.ASSISTANT org.tvbox.voicebridge
    case "$(settings get secure assistant)" in org.tvbox.voicebridge/*) ;; *)
        echo 'voice: assistant assignment failed'; exit 1;; esac
    mkdir -p "${STATE%/*}"
    printf '%s\n' "$identity" > "$STATE"
    chmod 600 "$STATE"
fi
echo 'voice: offline background assistant ready; existing preferences preserved'
