#!/bin/bash
# Единая версия APK берётся из версии прошивки, чтобы Obtainium видел новые релизы.
TVBOX_APP_VERSION_NAME=$(sed -n 's/^ro.tvbox.release=//p' "$R/build-sd.sh")
if [[ ! $TVBOX_APP_VERSION_NAME =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "Некорректная версия ro.tvbox.release: $TVBOX_APP_VERSION_NAME" >&2
    exit 1
fi
major=$((10#${BASH_REMATCH[1]}))
minor=$((10#${BASH_REMATCH[2]}))
patch=$((10#${BASH_REMATCH[3]}))
if (( major > 2147 || minor > 999 || patch > 999 )); then
    echo "Версия не помещается в Android versionCode: $TVBOX_APP_VERSION_NAME" >&2
    exit 1
fi
TVBOX_APP_VERSION_CODE=$((major * 1000000 + minor * 1000 + patch))
TVBOX_AAPT_VERSION_ARGS=(--version-code "$TVBOX_APP_VERSION_CODE" --version-name "$TVBOX_APP_VERSION_NAME")
