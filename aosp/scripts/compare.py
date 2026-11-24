#!/usr/bin/env python3
"""Offline comparison of inputs; reports differences, never asserts runtime compatibility."""
import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

def config(path):
    result = {}
    for line in path.read_text().splitlines():
        m = re.fullmatch(r"(CONFIG_[A-Z0-9_]+)=(.*)", line)
        n = re.fullmatch(r"# (CONFIG_[A-Z0-9_]+) is not set", line)
        if m:
            key, value = m.groups()
        elif n:
            key, value = n.group(1), "n"
        else:
            continue
        if key in result and result[key] != value:
            raise ValueError("conflicting config: " + key)
        result[key] = value
    return result

def diff_maps(a, b):
    return {"only_left": sorted(a.keys() - b.keys()),
            "only_right": sorted(b.keys() - a.keys()),
            "changed": {k: {"left": a[k], "right": b[k]} for k in sorted(a.keys() & b.keys()) if a[k] != b[k]},
            "matching": sum(a[k] == b[k] for k in a.keys() & b.keys())}

def manifest(path):
    root = ET.parse(path).getroot()
    if root.tag != "manifest":
        raise ValueError("not a repo manifest")
    default = root.find("default")
    defaults = default.attrib if default is not None else {}
    remotes = {r.attrib["name"]: r.attrib for r in root.findall("remote")}
    projects = {}
    for p in root.findall("project"):
        name = p.attrib["name"]
        key = p.get("path", name)
        remote = p.get("remote", defaults.get("remote"))
        rev = p.get("revision") or remotes.get(remote, {}).get("revision") or defaults.get("revision")
        if key in projects:
            raise ValueError("duplicate manifest path: " + key)
        projects[key] = {"name": name, "remote": remote, "fetch": remotes.get(remote, {}).get("fetch"),
                         "revision": rev, "groups": p.get("groups"), "nested": bool(p.findall("project"))}
    unsupported = sorted({x.tag for x in root if x.tag not in {"project", "default", "remote", "notice"}})
    if any(p["nested"] for p in projects.values()):
        unsupported.append("nested-project")
    return projects, unsupported

def compare_manifests(left, right):
    a, au = manifest(left)
    b, bu = manifest(right)
    return {"left_count": len(a), "right_count": len(b), "left_unresolved": au,
            "right_unresolved": bu, "differences": diff_maps(a, b),
            "status": "PARTIAL_UNRESOLVED" if au or bu else "STATIC_DIFF_ONLY"}

def vintf(path):
    """Exact declaration inventory, not checkvintf; matrix regex and version ranges not resolved."""
    root = ET.parse(path).getroot()
    if root.tag not in {"manifest", "compatibility-matrix"}:
        raise ValueError("not a VINTF XML")
    out = []
    for hal in root.findall("hal"):
        name = hal.findtext("name")
        if not name:
            raise ValueError("HAL without name")
        interfaces = []
        for iface in hal.findall("interface"):
            interfaces.append({"name": iface.findtext("name"),
                               "instances": sorted(x.text or "" for x in iface.findall("instance")),
                               "regex_instances": sorted(x.text or "" for x in iface.findall("regex-instance"))})
        out.append({"name": name, "format": hal.get("format", "hidl"),
                    "optional": hal.get("optional"), "override": hal.get("override"),
                    "versions": sorted(x.text or "" for x in hal.findall("version")),
                    "transport": hal.findtext("transport"),
                    "fqnames": sorted(x.text or "" for x in hal.findall("fqname")),
                    "interfaces": sorted(interfaces, key=lambda x: json.dumps(x, sort_keys=True))})
    return {"type": root.tag, "attributes": root.attrib, "hals": out}

def compare_vintf(left, right):
    a, b = vintf(left), vintf(right)
    def group(doc):
        result = {}
        for h in doc["hals"]:
            result.setdefault(h["format"] + ":" + h["name"], []).append(h)
        return {k: sorted(v, key=lambda x: json.dumps(x, sort_keys=True)) for k, v in result.items()}
    return {"left_type": a["type"], "right_type": b["type"],
            "left_attributes": a["attributes"], "right_attributes": b["attributes"],
            "differences": diff_maps(group(a), group(b)),
            "status": "STATIC_DIFF_ONLY", "required_next": "AOSP checkvintf on full partition trees with SKU, kernel and properties"}

def blob_map(path):
    entries = json.loads(path.read_text())
    result = {}
    for e in entries:
        k = e["partition"] + "/" + e["path"]
        if k in result:
            raise ValueError("duplicate blob path: " + k)
        result[k] = {"sha256": e["sha256"], "elf_class": e.get("elf_class"),
                     "needed": sorted(e["needed"]) if "needed" in e else None}
    return result

def compare_blobs(left, right):
    a, b = blob_map(left), blob_map(right)
    def deps(rows):
        return {k: sorted(v["needed"]) if v["needed"] is not None else None for k, v in rows.items()}
    return {"differences": diff_maps(a,b), "needed_differences": diff_maps(deps(a),deps(b)),
            "status": "STATIC_DIFF_ONLY", "required_next": "ELF symbols, SONAME, linker namespaces, VNDK and runtime ABI"}

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("kind", choices=["kernel-config", "manifest", "vintf", "blobs"])
    p.add_argument("left", type=Path)
    p.add_argument("right", type=Path)
    args = p.parse_args()
    try:
        if args.kind == "kernel-config":
            r = {"status": "STATIC_DIFF_ONLY", "differences": diff_maps(config(args.left), config(args.right))}
        else:
            r = {"manifest": compare_manifests, "vintf": compare_vintf, "blobs": compare_blobs}[args.kind](args.left, args.right)
        print(json.dumps(r, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, ET.ParseError) as e:
        print("Comparison failed: " + type(e).__name__, file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
