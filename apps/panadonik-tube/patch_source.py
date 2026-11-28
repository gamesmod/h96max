#!/usr/bin/env python3
"""Точечные изменения зафиксированной версии; несовпадение контекста блокирует сборку."""
import argparse
import json
from pathlib import Path
import subprocess

BASE = "common/src/main/java/com/liskovsoft/smartyoutubetv2/common/"
LOCK = json.loads(Path(__file__).with_name("source.lock.json").read_text())
def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("Контекст патча не уникален: " + old[:120])
    return text.replace(old, new, 1)

def transform(path, text):
    if path == "smarttubetv/build.gradle":
        text = replace_once(text, 'applicationId "app.smarttube.fdroid"', 'applicationId "org.tvbox.panadoniktube"\n            resConfigs "ru", "en"')
        text = replace_once(text, "include 'armeabi-v7a', 'arm64-v8a', 'x86' //", "include 'armeabi-v7a', 'arm64-v8a' //")
        text = replace_once(text, "universalApk true", "universalApk false")
        text = replace_once(text, "abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86'", "// ARM ABI selected by splits")
        # AGP rejects ndk.abiFilters and splits.abi together.
        text = text.replace("abiFilters 'armeabi-v7a', 'arm64-v8a'", "// ARM ABI selected by splits")
        text = replace_once(text, 'def project = "SmartTube"', 'def project = "PanadonikTube"')
        text = replace_once(text, 'versionName "32.63-cn-20261008-r101"', 'versionName "32.63-cn-20261008-r101-panadonik.1"')
        # F-Droid build does not use these plugins. Keep other flavors declared for Gradle variant matching.
        text = replace_once(text, "        classpath 'com.google.gms:google-services:4.3.15'", "")
        text = replace_once(text, "        classpath 'com.google.firebase:firebase-crashlytics-gradle:2.7.1'", "")
        start = text.index('// The first letter of the flavor name is uppercase') if '// The first letter of the flavor name is uppercase' in text else text.index('// The first letter of the flavor name must be in uppercase')
        end = text.index('\nandroid {', start)
        text = text[:start] + '// Panadonik: no Google Services / Crashlytics build plugins.\n' + text[end:]
        return text
    if path == "smarttubetv/src/stfdroid/res/values/strings.xml":
        if text.count("SmartTube F-Droid") != 2:
            raise ValueError("branding context")
        return text.replace("SmartTube F-Droid", "Panadonik Tube")
    if path == "leanbackassistant/src/stfdroid/res/values/strings.xml":
        return replace_once(text, "app.smarttube.fdroid.search_provider", "org.tvbox.panadoniktube.search_provider")
    if path == "leanbackassistant/src/stfdroid/res/xml/searchable.xml":
        if text.count("app.smarttube.fdroid.search_provider") != 2:
            raise ValueError("authority context")
        return text.replace("app.smarttube.fdroid.search_provider", "org.tvbox.panadoniktube.search_provider")
    if path.endswith("/MainUIData.java"):
        return replace_once(text, "mIsCardTextAutoScrollEnabled = Helpers.parseBoolean(split, 8, true);", "mIsCardTextAutoScrollEnabled = Helpers.parseBoolean(split, 8, false);")
    if path.endswith("/ExoMediaSourceFactory.java"):
        text = replace_once(text, "int source = tweaksData.getPlayerDataSource();",
            "int source = isPanadonikProxy() ? PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP : tweaksData.getPlayerDataSource();")
        text = replace_once(text, "    /**\n     * Use OkHttp for networking",
            """    private static boolean isPanadonikProxy() {
        return org.tvbox.panadonikproxy.AutoProxy.enabled();
    }

    /**
     * Use OkHttp for networking""")
        if "OkHttpManager.instance().getMediaClient()" in text:
            old_client = "OkHttpDataSourceFactory dataSourceFactory = new OkHttpDataSourceFactory(OkHttpManager.instance().getMediaClient(), USER_AGENT,"
            client_prefix = """OkHttpClient client = OkHttpManager.instance().getMediaClient();
        if (isPanadonikProxy()) {
            client = client.newBuilder()
                    .proxy(null)
                    .proxySelector(java.net.ProxySelector.getDefault())
                    .build();
        }
        OkHttpDataSourceFactory dataSourceFactory = new OkHttpDataSourceFactory(client, USER_AGENT,"""
        else:
            old_client = "OkHttpClient client = OkHttpManager.instance().getClient();"
            client_prefix = """OkHttpClient client = OkHttpManager.instance().getClient();
        if (isPanadonikProxy()) {
            client = client.newBuilder()
                    .proxy(null)
                    .proxySelector(java.net.ProxySelector.getDefault())
                    .build();
        }"""
        text = replace_once(text, old_client, client_prefix)
        return text
    if path.endswith("/AppUpdatePresenter.java"):
        # Disable even if a later caller accidentally invokes updater for fdroid.
        start = text.index("    public void start(boolean forceCheck) {")
        end = text.index("\n    @Override", start)
        return text[:start] + """    public void start(boolean forceCheck) {
        // Panadonik updates only via the reviewed project release channel.
        if (forceCheck) {
            MessageHelpers.showMessage(getContext(), "Обновление через Panadonik");
        }
    }
""" + text[end:]
    if path.endswith("/MainApplication.java"):
        return replace_once(text, "        setupGlobalExceptionHandler();",
            "        org.tvbox.panadonikproxy.AutoProxy.start(this);\n        setupGlobalExceptionHandler();")
    if path.endswith("/SplashPresenter.java"):
        # The managed selector is installed before network clients initialize.
        return replace_once(text, "if (GeneralData.instance(getContext()).isProxyEnabled()) {",
            "if (!org.tvbox.panadonikproxy.AutoProxy.enabled() && GeneralData.instance(getContext()).isProxyEnabled()) {")
    if path.endswith("/GeneralSettingsPresenter.java"):
        start=text.index("    private void appendProxyManager(AppDialogPresenter settingsPresenter, List<OptionItem> options) {")
        end=text.index("\n    private void appendConscrypt",start)
        method="""    private void appendProxyManager(AppDialogPresenter settingsPresenter, List<OptionItem> options) {
        options.add(UiOptionItem.from("Автопрокси Panadonik (включён по умолчанию)",
                option -> org.tvbox.panadonikproxy.AutoProxy.setEnabled(getContext(), option.isSelected()),
                org.tvbox.panadonikproxy.AutoProxy.configured(getContext())));
        options.add(UiOptionItem.from("Ротация при деградации",
                option -> org.tvbox.panadonikproxy.AutoProxy.setRotation(getContext(), option.isSelected()),
                org.tvbox.panadonikproxy.AutoProxy.rotationConfigured(getContext())));
        options.add(UiOptionItem.from("Обновить список: " + org.tvbox.panadonikproxy.AutoProxy.status(getContext()),
                option -> org.tvbox.panadonikproxy.AutoProxy.refresh(), false));
        options.add(UiOptionItem.from("Ручной HTTP/SOCKS прокси (отключает автопрокси)",
                option -> {
                    if (option.isSelected()) {
                        org.tvbox.panadonikproxy.AutoProxy.setEnabled(getContext(), false);
                        mPlayerTweaksData.setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP);
                        mGeneralData.setProxyEnabled(true);
                        new WebProxyDialog(getContext()).enable(true);
                        settingsPresenter.closeDialog();
                    } else {
                        mGeneralData.setProxyEnabled(false);
                        new WebProxyDialog(getContext()).enable(false);
                    }
                    OkHttpManager.unhold();
                }, mGeneralData.isProxyEnabled() && !org.tvbox.panadonikproxy.AutoProxy.configured(getContext())));
    }
"""
        return text[:start]+method+text[end:]
    if path.endswith("/ErrorFixerController.java"):
        return replace_once(text, """        if (getPlayer() == null) {
            return;
        }

        if (isStreamEnded()) {
""", """        if (getPlayer() == null) {
            return;
        }

        if (!isStreamEnded() && org.tvbox.panadonikproxy.AutoProxy.rotateOnBuffering()) {
            YouTubeServiceManager.instance().invalidateCache();
            mVideoLoaderController.reloadVideo();
            return;
        }
        if (isStreamEnded()) {
""")
    raise ValueError("Unknown patch path")

PATHS = [
    "smarttubetv/build.gradle",
    "smarttubetv/src/stfdroid/res/values/strings.xml",
    "leanbackassistant/src/stfdroid/res/values/strings.xml",
    "leanbackassistant/src/stfdroid/res/xml/searchable.xml",
    BASE + "prefs/MainUIData.java",
    BASE + "exoplayer/ExoMediaSourceFactory.java",
    BASE + "app/presenters/dialogs/AppUpdatePresenter.java",
    "smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/main/MainApplication.java",
    BASE + "app/presenters/SplashPresenter.java",
    BASE + "app/presenters/settings/GeneralSettingsPresenter.java",
    BASE + "app/models/playback/controllers/ErrorFixerController.java",
]

def apply(root):
    root = root.resolve()
    sha = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if sha != LOCK["commit"]:
        raise ValueError("Неожиданная версия SmartTube: " + sha)
    if subprocess.check_output(["git","-C",str(root),"status","--porcelain"],text=True).strip():
        raise ValueError("Требуется чистое дерево исходников")
    shared = root / "SharedModules"
    shared_patch = root / "patches/04-okhttp-media-client-sharedutils.patch"
    subprocess.run(["git","-C",str(shared),"apply","--ignore-space-change","--check",str(shared_patch)],check=True)
    subprocess.run(["git","-C",str(shared),"apply","--ignore-space-change",str(shared_patch)],check=True)
    manager = shared / "sharedutils/src/main/java/com/liskovsoft/sharedutils/okhttp/OkHttpManager.java"
    manager_text = manager.read_text()
    manager_text = replace_once(manager_text,
        "mMediaClient = OkHttpCommons.setupMediaBuilder(new OkHttpClient.Builder()).build();",
        "mMediaClient = panadonikProxy(OkHttpCommons.setupMediaBuilder(new OkHttpClient.Builder())).build();")
    manager_text = replace_once(manager_text,
        "mClient = OkHttpCommons.setupBuilder(new OkHttpClient.Builder()).build();",
        "mClient = panadonikProxy(OkHttpCommons.setupBuilder(new OkHttpClient.Builder())).build();")
    anchor = "    public static long getConnectTimeoutMs() {"
    helper = """    private static OkHttpClient.Builder panadonikProxy(OkHttpClient.Builder builder) {
        String token = System.getProperty("panadonik.proxyToken");
        if (token == null || token.isEmpty()) return builder;
        return builder.proxy(null).proxySelector(java.net.ProxySelector.getDefault())
                .proxyAuthenticator((route, response) -> response.request().newBuilder()
                        .header("Proxy-Authorization", "Panadonik " + token).build());
    }

"""
    manager.write_text(replace_once(manager_text,anchor,helper+anchor))
    changes = {}
    for name in PATHS:
        file = root / name
        raw = file.read_bytes()
        sep = "\r\n" if b"\r\n" in raw else "\n"
        data = raw.decode("utf-8").replace("\r\n", "\n")
        changes[file] = transform(name, data).replace("\n", sep).encode("utf-8")
    for file, data in changes.items():
        file.write_bytes(data)
    # Build overlay belongs to the common module; assets are embedded in the final APK.
    overlay = Path(__file__).with_name("overlay")
    dest = root / "common/src/main/java/org/tvbox/panadonikproxy"
    dest.mkdir(parents=True, exist_ok=True)
    for java in overlay.glob("*.java"):
        (dest / java.name).write_bytes(java.read_bytes())
    assets = root / "smarttubetv/src/main/assets"
    assets.mkdir(parents=True, exist_ok=True)
    (assets / "sources.json").write_bytes((Path(__file__).parent.parent / "smarttube-proxy/sources.json").read_bytes())
    print("Патчи применены:", len(changes))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    apply(parser.parse_args().source)
