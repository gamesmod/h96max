#!/usr/bin/env python3
"""Проверка состава ABI и ID unsigned APK, не доверяем имени файла."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

def verify(source, output, tools):
    expected = {"armeabi-v7a", "arm64-v8a"}
    seen = set()
    for apk in sorted(source.glob("*.apk")):
        with zipfile.ZipFile(apk) as z:
            abis = {name.split("/")[1] for name in z.namelist()
                    if name.startswith("lib/") and name.endswith(".so")}
            if any("crashlytics" in name.lower() for name in z.namelist()):
                raise ValueError("Crashlytics resource")
        if len(abis) != 1 or not abis <= expected or abis & seen:
            raise ValueError("Unexpected ABI: " + str(abis))
        info = subprocess.check_output([str(tools/"aapt"), "dump", "badging", str(apk)], text=True)
        if not re.search(r"package: name='org\.tvbox\.panadoniktube'", info):
            raise ValueError("Unexpected package")
        match = re.search(r"sdkVersion:'(\d+)'", info)
        if not match or int(match[1]) > 32:
            raise ValueError("Android 12 incompatible minSdk")
        abi = abis.pop()
        seen.add(abi)
        shutil.copyfile(apk, output / ("PanadonikTube-" + abi + "-unsigned.apk"))
    if seen != expected:
        raise ValueError("Missing APK ABI: " + str(expected-seen))
    files = sorted(output.glob("PanadonikTube-*-unsigned.apk"))
    (output/"SHA256SUMS").write_text("".join(hashlib.sha256(f.read_bytes()).hexdigest()+"  "+f.name+"\n" for f in files))
if __name__ == "__main__":
    p=argparse.ArgumentParser()
    p.add_argument("source", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("tools", type=Path)
    a=p.parse_args()
    verify(a.source,a.output,a.tools)
