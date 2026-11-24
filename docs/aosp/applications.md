# Application compatibility — source audit
Repository base fb0725c1bbd9e4b6df2a767e000e80a18f4dd91f. All nine checked own APK source manifests declare minSdk=23 and targetSdk=29: antivirus, bootlog, clean, files, installer, iptv, links, power, voicebridge. This permits Android 9–12 at declaration level only. Voicebridge is not yet part of the existing release pipeline. JARs tvboxd/bootscreen, hidden APIs, shared UID and privileged permissions require separate review.
Important ABI constraint from [pinned third-party inputs](https://github.com/gamesmod/h96max/blob/fb0725c1bbd9e4b6df2a767e000e80a18f4dd91f/tools/third-party-apps.json):
- Obtainium 1.7.4 is explicitly arm64-v8a release APK.
- WebView 155.0.8059.39 is from arm64 prebuilt repository and commit 604ad4cbc41d3b894065ee6e83189c76e1ec06a1.
- TorrServer MatriX.147.1 is android-arm64 native executable.
Pure ARM32 userspace cannot be selected with this existing application set unchanged. ARM64 kernel alone does not provide a 64-bit userspace linker and libraries. First preserve current ABI, compare mixed/64-bit later. Exact APK min SDK and signatures still require actual binaries; no claim that modern WebView works on Android 9.
SmartTube is required for the AOSP contour in task, not in current required-apps.sh. Do not change master allowlist by stealth.
Run offline: `python3 aosp/scripts/app_compat.py` (source manifest scan, pinned hash check, APK native ABI inventory and aapt badging when available). Missing binaries remain NOT_PRESENT; exit success is not a compatibility verdict.
