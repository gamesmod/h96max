#!/usr/bin/env python3
"""Inspect existing source manifests and locally available pinned APKs; never install."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
NS = "{http://schemas.android.com/apk/res/android}"
APIS = (28, 29, 30, 31, 32)
def sdk_value(value):
    return int(value) if value and value.isdecimal() else None
def api_status(minimum, api):
    return "UNKNOWN" if minimum is None else "MIN_SDK_OK" if minimum <= api else "MIN_SDK_BLOCK"
def source_manifest(path):
    root = ET.parse(path).getroot()
    if root.tag != "manifest":
        raise ValueError("not an Android manifest")
    sdk = root.find("uses-sdk")
    minimum = sdk_value(sdk.get(NS+"minSdkVersion")) if sdk is not None else None
    target = sdk_value(sdk.get(NS+"targetSdkVersion")) if sdk is not None else None
    return {"package":root.get("package"), "min_sdk":minimum, "target_sdk":target,
            "shared_uid":root.get(NS+"sharedUserId"),
            "permissions":sorted(x.get(NS+"name","") for x in root.findall("uses-permission")),
            "api_status":{str(api):api_status(minimum, api) for api in APIS},
            "evidence":"SOURCE_DECLARATION_ONLY"}
def digest(path):
    h=hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda:f.read(1024*1024), b""):
            h.update(block)
    return h.hexdigest()
def apk_abis(path):
    with zipfile.ZipFile(path) as z:
        return sorted({n.split("/")[1] for n in z.namelist()
                       if re.fullmatch(r"lib/[^/]+/[^/]+\.so",n)})
def apk_record(root, item):
    p=(root/item["dest"]).resolve()
    if not p.is_relative_to(root.resolve()):
        raise ValueError("APK path outside repository")
    r={"id":item["id"],"version":item["version"],"expected_sha256":item["sha256"],"status":"NOT_PRESENT"}
    if not p.is_file():
        return r
    if digest(p)!=item["sha256"]:
        r["status"]="HASH_MISMATCH"
        return r
    r["status"]="HASH_MATCH"
    if p.suffix!=".apk":
        r["kind"]="non-APK binary"
        with p.open("rb") as f:
            hdr=f.read(20)
        if len(hdr)==20 and hdr[:4]==b"\x7fELF" and hdr[5] in (1,2):
            r["elf_machine"]=int.from_bytes(hdr[18:20],"little" if hdr[5]==1 else "big")
            r["elf_class"]=hdr[4]
        return r
    r["native_abis"]=apk_abis(p)
    r["abi_note"]="No lib/ entries is not proof of pure Java; bundled native code may be elsewhere."
    aapt=shutil.which("aapt")
    if not aapt:
        r["metadata_status"]="AAPT_NOT_AVAILABLE"
        return r
    out=subprocess.run([aapt,"dump","badging",str(p)],capture_output=True,text=True,check=True,timeout=60).stdout
    name=re.search(r"^package: name='([^']+)'",out,re.M)
    mi=re.search(r"^sdkVersion:'(\d+)'",out,re.M)
    ta=re.search(r"^targetSdkVersion:'(\d+)'",out,re.M)
    minimum=int(mi.group(1)) if mi else None
    r.update(package=name.group(1) if name else None,min_sdk=minimum,target_sdk=int(ta.group(1)) if ta else None,
             api_status={str(a):api_status(minimum,a) for a in APIS},metadata_status="DECLARATION_ONLY")
    if r["package"]!=item["id"]:
        r["status"]="PACKAGE_MISMATCH"
    return r
def audit(root):
    apps=[{"path":str(p.relative_to(root)),**source_manifest(p)} for p in sorted((root/"apps").glob("*/AndroidManifest.xml"))]
    items=json.loads((root/"tools/third-party-apps.json").read_text())["items"]
    return {"source_apps":apps,"pinned_inputs":[apk_record(root,i) for i in items],
            "status":"STATIC_ONLY","not_tested":["signature trust","runtime APIs","privapp permissions","native dependencies outside lib/","hardware","application behavior"]}
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root",type=Path,default=ROOT)
    a=p.parse_args()
    try:
        r=audit(a.root.resolve())
        print(json.dumps(r,ensure_ascii=False,indent=2))
        return 1 if any(x["status"] in ("HASH_MISMATCH","PACKAGE_MISMATCH") for x in r["pinned_inputs"]) else 0
    except (OSError,ValueError,KeyError,ET.ParseError,zipfile.BadZipFile,subprocess.SubprocessError) as e:
        print("Audit failed: "+type(e).__name__,file=sys.stderr)
        return 1
if __name__=="__main__":
    sys.exit(main())
