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
        text = replace_once(text, 'applicationId "app.smarttube.fdroid"', 'applicationId "org.tvbox.panadoniktube"')
        text = replace_once(text, "include 'armeabi-v7a', 'arm64-v8a', 'x86' //", "include 'armeabi-v7a', 'arm64-v8a' //")
        text = replace_once(text, "universalApk true", "universalApk false")
        text = replace_once(text, "abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86'", "abiFilters 'armeabi-v7a', 'arm64-v8a'")
        text = replace_once(text, 'def project = "SmartTube"', 'def project = "PanadonikTube"')
        text = replace_once(text, 'versionName "32.83"', 'versionName "32.83-panadonik.1"')
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
        return "127.0.0.1".equals(System.getProperty("https.proxyHost"))
                && "18765".equals(System.getProperty("https.proxyPort"));
    }

    /**
     * Use OkHttp for networking""")
        text = replace_once(text, "OkHttpClient client = OkHttpManager.instance().getClient();",
            """OkHttpClient client = OkHttpManager.instance().getClient();
        if (isPanadonikProxy()) {
            client = client.newBuilder()
                    .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                            new java.net.InetSocketAddress("127.0.0.1", 18765)))
                    .build();
        }""")
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
    raise ValueError("Unknown patch path")

PATHS = [
    "smarttubetv/build.gradle",
    "smarttubetv/src/stfdroid/res/values/strings.xml",
    "leanbackassistant/src/stfdroid/res/values/strings.xml",
    "leanbackassistant/src/stfdroid/res/xml/searchable.xml",
    BASE + "prefs/MainUIData.java",
    BASE + "exoplayer/ExoMediaSourceFactory.java",
    BASE + "app/presenters/dialogs/AppUpdatePresenter.java",
]

def apply(root):
    root = root.resolve()
    sha = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if sha != LOCK["commit"]:
        raise ValueError("Неожиданная версия SmartTube: " + sha)
    if subprocess.check_output(["git","-C",str(root),"status","--porcelain"],text=True).strip():
        raise ValueError("Требуется чистое дерево исходников")
    changes = {}
    for name in PATHS:
        file = root / name
        raw = file.read_bytes()
        sep = "\r\n" if b"\r\n" in raw else "\n"
        data = raw.decode("utf-8").replace("\r\n", "\n")
        changes[file] = transform(name, data).replace("\n", sep).encode("utf-8")
    for file, data in changes.items():
        file.write_bytes(data)
    print("Патчи применены:", len(changes))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    apply(parser.parse_args().source)
