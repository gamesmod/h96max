#!/bin/bash
# Verify APK signatures (v1/v2/v3) and print the signer certificate SHA-256: tools/apkcert.sh FILE.apk...
# Uses apksigner + apksig from sdk/x (tools/setup-sdk.sh), JDK from sdk/jdk.
R=$(cd "$(dirname "$0")/.." && pwd); X=$R/sdk/x/usr/share/java
exec $R/sdk/jdk/usr/lib/jvm/java-21-openjdk-amd64/bin/java -cp $X/apksigner.jar:$X/apksig.jar \
    com.android.apksigner.ApkSignerTool verify --print-certs -v "$@"
