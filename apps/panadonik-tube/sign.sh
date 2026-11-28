#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
R=../..
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="$SDK/build-tools/35.0.0"
for f in "$BT/apksigner" "$BT/zipalign" "$R/secrets/tvbox-key.pem" "$R/secrets/tvbox-cert.pem"; do
    test -s "$f" || { echo "Отсутствует вход подписи: $f" >&2; exit 1; }
done
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
openssl pkcs8 -topk8 -nocrypt -in "$R/secrets/tvbox-key.pem" -outform DER -out "$W/key.der"
expected=$(openssl x509 -in "$R/secrets/tvbox-cert.pem" -outform DER | sha256sum | cut -d' ' -f1)
for abi in armeabi-v7a arm64-v8a; do
    input="out/PanadonikTube-$abi-unsigned.apk"
    output="out/PanadonikTube-$abi.apk"
    test -s "$input"
    "$BT/zipalign" -f -p 4 "$input" "$W/aligned.apk"
    "$BT/apksigner" sign --key "$W/key.der" --cert "$R/secrets/tvbox-cert.pem" --v1-signing-enabled true --v2-signing-enabled true --out "$output" "$W/aligned.apk"
    "$BT/apksigner" verify --verbose --print-certs "$output" > "$W/verify-$abi.txt"
    grep -Fx "Signer #1 certificate SHA-256 digest: $expected" "$W/verify-$abi.txt" >/dev/null
    "$BT/zipalign" -c -p 4 "$output"
done
cp out/PanadonikTube-arm64-v8a.apk out/org.tvbox.panadoniktube.apk
(cd out && sha256sum PanadonikTube-armeabi-v7a.apk PanadonikTube-arm64-v8a.apk org.tvbox.panadoniktube.apk > SIGNED-SHA256SUMS)
