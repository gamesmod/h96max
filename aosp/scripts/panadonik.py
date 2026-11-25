#!/usr/bin/env python3
"""Read-only AOSP discovery and baseline; deliberately no flash or release path."""
import argparse
import csv
import hashlib
import html
import json
import os
from pathlib import Path
import platform
import re
import shutil
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
AOSP = ROOT / "aosp"
PROPS = ("ro.build.fingerprint", "ro.build.id", "ro.build.version.sdk",
         "ro.build.version.security_patch", "ro.vendor.build.security_patch",
         "ro.vendor.build.fingerprint", "ro.vendor.build.version.sdk",
         "ro.product.first_api_level", "ro.board.first_api_level",
         "ro.vndk.version", "ro.rksdk.version", "ro.product.cpu.abilist",
         "ro.product.cpu.abilist32", "ro.product.cpu.abilist64",
         "ro.hardware", "ro.board.platform", "ro.build.type",
         "ro.tvbox.release", "ro.boot.verifiedbootstate")
PARTITIONS = ("system", "vendor", "product", "system_ext", "odm",
              "vendor_dlkm", "odm_dlkm")
BLOCKED = 3

def save(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)

def run(args, timeout=60):
    return subprocess.run(args, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout, check=True).stdout.strip()

def sha(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def lfs_pointer(path):
    with path.open("rb") as f:
        return f.read(48).startswith(b"version https://git-lfs.github.com/spec/v1")

def doctor(work):
    mem = {}
    p = Path("/proc/meminfo")
    if p.exists():
        for line in p.read_text().splitlines():
            key, _, value = line.partition(":")
            if key in ("MemTotal", "MemAvailable", "SwapTotal"):
                mem[key] = int(value.split()[0]) * 1024
    tools = {t: bool(shutil.which(t)) for t in
             ("git", "repo", "java", "adb", "debugfs", "readelf", "dtc", "python3")}
    disk = shutil.disk_usage(work).free
    reasons = []
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        reasons.append("Linux x86_64 required")
    if disk < 400_000_000_000:
        reasons.append("less than 400 GB free (conservative full AOSP policy)")
    if mem.get("MemTotal", 0) < 64_000_000_000:
        reasons.append("less than 64 GB RAM (conservative no-swap full build policy)")
    if mem.get("SwapTotal", 0):
        reasons.append("swap enabled; no automatic modification")
    reasons += ["missing " + t for t in ("git", "repo", "java") if not tools[t]]
    result = {"status": "BLOCKED" if reasons else "READY_FOR_SOURCE_PREFLIGHT",
              "cpu": os.cpu_count(), "memory_bytes": mem, "disk_free_bytes": disk,
              "tools": tools, "reasons": reasons}
    save(work / "doctor.json", result)
    print(json.dumps(result, indent=2))
    return BLOCKED if reasons else 0

def properties(text):
    result = {}
    for line in text.splitlines():
        k, sep, v = line.partition("=")
        if sep and k in PROPS:
            result[k] = v.strip()
    return result

def inspect_manifest(path):
    root = ET.parse(path).getroot()
    if root.tag != "manifest":
        raise ValueError("not a repo manifest")
    default = root.find("default")
    default_rev = default.get("revision") if default is not None else None
    projects = [{"name": p.get("name"), "path": p.get("path", p.get("name")),
                 "remote": p.get("remote"), "revision": p.get("revision", default_rev)}
                for p in root.iter("project")]
    includes = [p.get("name") for p in root.findall("include")]
    unsupported = sorted({p.tag for p in root if p.tag not in
                          {"remote", "default", "project", "include", "notice"}})
    pinned = bool(projects) and all(re.fullmatch(r"[0-9a-fA-F]{40}", p["revision"] or "")
                                    for p in projects)
    return {"projects": projects, "includes": includes,
            "unresolved_directives": unsupported,
            "fully_pinned_flat_manifest": pinned and not includes and not unsupported}

def github(url):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "panadonik-aosp-research"}
    # Optional local token; never written to output.
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=20) as r:
        return json.load(r)

def research(work, manifest=None):
    entries = json.loads((AOSP / "research/sources.json").read_text())
    results = []
    for entry in entries:
        row = dict(entry)
        try:
            name = row["repository"]
            api = "https://api.github.com/repos/" + name
            data = github(api)
            branch = data["default_branch"]
            from urllib.parse import quote
            commit = github(api + "/commits/" + quote(branch, safe=""))
            row.update({"availability": "metadata_verified", "default_branch": branch,
                        "commit": commit["sha"], "updated_at": data.get("pushed_at"),
                        "license": (data.get("license") or {}).get("spdx_id"),
                        "archived": data.get("archived"), "size_kib": data.get("size"),
                        "sdk_completeness": "UNVERIFIED"})
        except (OSError, ValueError, KeyError) as e:
            row.update({"availability": "UNVERIFIED", "error": type(e).__name__,
                        "http_status": getattr(e, "code", None)})
        results.append(row)
        save(work / "repositories.json", results)
    if manifest:
        save(work / "manifest-inspection.json", inspect_manifest(manifest))
    rows = "".join("<tr>" + "".join("<td>" + html.escape(str(r.get(k, ""))) +
                   "</td>" for k in ("repository", "category", "availability",
                   "default_branch", "commit", "license", "notes")) + "</tr>" for r in results)
    (work / "research.html").write_text(
        '<!doctype html><meta charset="utf-8"><title>Panadonik source inventory</title>'
        '<style>body{font:15px system-ui;margin:2em}td,th{padding:8px;border:1px solid #bbb}'
        'table{border-collapse:collapse}th{cursor:pointer}</style>'
        '<h1>Source inventory — metadata is not SDK completeness</h1>'
        '<input id="q" placeholder="Filter"><table><thead><tr>' +
        "".join("<th>" + k + "</th>" for k in ("Repository","Class","Status","Branch","SHA","License","Notes")) +
        '</tr></thead><tbody>' + rows + '</tbody></table>'
        '<script>const b=document.querySelector("tbody");q.oninput=()=>[...b.rows].forEach(r=>'
        'r.hidden=!r.innerText.toLowerCase().includes(q.value.toLowerCase()));'
        'document.querySelectorAll("th").forEach((h,i)=>h.onclick=()=>'
        '[...b.rows].sort((a,c)=>a.cells[i].innerText.localeCompare(c.cells[i].innerText))'
        '.forEach(r=>b.append(r)))</script>', encoding="utf-8")
    return 0 if any(r["availability"] == "metadata_verified" for r in results) else BLOCKED

def inventory(work, source):
    """Hash original input images; extract via existing read-only debugfs wrapper."""
    source = source.resolve()
    paths = sorted(p for p in source.glob("*.img") if p.is_file())
    lp = source / "lp"
    paths += sorted(p for p in lp.glob("*.img") if p.is_file())
    if not paths or any(lfs_pointer(p) for p in paths):
        print("BLOCKED: missing or unsmudged stock images", file=sys.stderr)
        return BLOCKED
    images = [{"path": str(p.relative_to(source)), "sha256": sha(p), "bytes": p.stat().st_size}
              for p in paths]
    save(work / "images.json", images)
    if not (lp / "system.img").is_file() or not (lp / "vendor.img").is_file():
        print("BLOCKED: system/vendor LP required", file=sys.stderr)
        return BLOCKED
    dump = work / "dump"
    if dump.exists():
        print("BLOCKED: use fresh work directory; existing dump not overwritten", file=sys.stderr)
        return BLOCKED
    subprocess.run(["bash", str(ROOT / "tools/extract-vendor-blobs.sh"),
                    "--from-images", str(lp), "--dump", str(dump)], check=True)
    all_props = {}
    inventory_rows = []
    for part in PARTITIONS:
        directory = dump / part
        if not directory.is_dir():
            continue
        for p in sorted(directory.rglob("build.prop")):
            if p.is_file() and not p.is_symlink() and p.resolve().is_relative_to(directory.resolve()):
                all_props[str(p.relative_to(dump))] = properties(p.read_text(errors="replace"))
        if part not in ("vendor", "odm", "vendor_dlkm", "odm_dlkm"):
            continue
        for p in sorted(directory.rglob("*")):
            if p.is_symlink() or not p.is_file() or not p.resolve().is_relative_to(directory.resolve()):
                continue
            rel = p.relative_to(directory)
            if not set(rel.parts[:-1]).intersection({"lib", "lib64", "hw", "firmware", "modules", "vintf"}):
                continue
            row = {"partition": part, "path": str(rel), "sha256": sha(p),
                   "bytes": p.stat().st_size, "license": "UNKNOWN", "redistribution": "NOT_APPROVED"}
            with p.open("rb") as f:
                elf = f.read(4) == b"\x7fELF"
            if elf and shutil.which("readelf"):
                try:
                    info = run(["readelf", "-h", "-d", str(p)])
                    row["elf_class"] = next((x.split(":", 1)[1].strip() for x in info.splitlines()
                                             if "Class:" in x), None)
                    row["needed"] = re.findall(r"\(NEEDED\).*?\[(.*?)\]", info)
                except (subprocess.SubprocessError, OSError):
                    row["elf_status"] = "UNREADABLE"
            inventory_rows.append(row)
    save(work / "properties.json", all_props)
    save(work / "vendor-inventory.json", inventory_rows)
    print("Private inventory saved; no redistribution authorized.")
    return 0 if all_props and inventory_rows else BLOCKED

def parse_meminfo(text):
    return {k: int(v) for k, v in re.findall(r"^(MemTotal|MemAvailable|SwapTotal):\s+(\d+)\s+kB", text, re.M)}

def cpu_ticks(text):
    line = text.splitlines()[0].split()
    if line[0] != "cpu":
        raise ValueError("unexpected /proc/stat")
    n = [int(x) for x in line[1:9]]
    return sum(n), n[3] + n[4]

def cpu_percent(a, b):
    total, idle = b[0] - a[0], b[1] - a[1]
    if total <= 0 or not 0 <= idle <= total:
        raise ValueError("invalid CPU delta")
    return 100 * (total - idle) / total

def parse_loadavg(text):
    fields = text.split()
    if len(fields) < 3:
        raise ValueError("unexpected /proc/loadavg")
    return {"load_1m": float(fields[0]), "load_5m": float(fields[1]),
            "load_15m": float(fields[2])}

def parse_thermal(text):
    """Parse `type<TAB>temp` rows and normalize millidegrees to Celsius."""
    result = {}
    for line in text.splitlines():
        name, sep, raw = line.partition("\t")
        if not sep:
            continue
        try:
            value = float(raw.strip())
        except ValueError:
            continue
        if abs(value) >= 1000:
            value /= 1000.0
        result[name.strip() or "unknown"] = value
    return result

def parse_named_ints(text):
    result = {}
    for line in text.splitlines():
        name, sep, raw = line.partition("\t")
        if not sep:
            continue
        try:
            result[name.strip()] = int(raw.strip())
        except ValueError:
            continue
    return result

def parse_gfxinfo(text):
    def number(pattern, cast=int):
        match = re.search(pattern, text, re.I)
        return cast(match.group(1).replace(",", "")) if match else None
    return {
        "total_frames": number(r"Total frames rendered:\s*([\d,]+)"),
        "janky_frames": number(r"Janky frames:\s*([\d,]+)"),
        "janky_percent": number(r"Janky frames:\s*[\d,]+\s*\(([\d.]+)%\)", float),
        "p95_ms": number(r"95th percentile:\s*([\d.]+)ms", float),
        "p99_ms": number(r"99th percentile:\s*([\d.]+)ms", float),
    }

def parse_boot_events(text):
    events = []
    for line in text.splitlines():
        name = re.search(r"\b(boot_progress_[a-z0-9_]+)\b", line, re.I)
        if not name:
            continue
        numbers = [int(value) for value in re.findall(r"(?<![.\d])(\d{3,})(?![.\d])", line)]
        if not numbers:
            continue
        elapsed = numbers[-1]
        events.append({"event": name.group(1).lower(), "elapsed_ms": elapsed})
    # Event buffers can contain duplicates; keep the latest value for each named milestone.
    latest = {row["event"]: row["elapsed_ms"] for row in events}
    ordered = sorted(({"event": key, "elapsed_ms": value} for key, value in latest.items()),
                     key=lambda row: row["elapsed_ms"])
    previous = 0
    for row in ordered:
        row["stage_duration_ms"] = max(0, row["elapsed_ms"] - previous)
        previous = row["elapsed_ms"]
    return ordered

def summarize_samples(samples):
    numeric = ("cpu_busy_pct_all_cores", "mem_available_kib", "load_1m",
               "thermal_max_c", "cpu_frequency_max_khz")
    medians = {}
    for key in numeric:
        values = [sample[key] for sample in samples if sample.get(key) is not None]
        if values:
            medians[key] = statistics.median(values)
    temperatures = [sample["thermal_max_c"] for sample in samples
                    if sample.get("thermal_max_c") is not None]
    if temperatures:
        medians["thermal_peak_c"] = max(temperatures)
    return medians

def benchmark(work, serial, repetitions=5, interval=2, package="com.tvlauncher"):
    if not shutil.which("adb") or not serial:
        save(work / "benchmark.json", {"status": "BLOCKED", "reason": "ADB and explicit serial required"})
        return BLOCKED
    adb = ["adb", "-s", serial]
    if run(adb + ["get-state"]) != "device":
        raise ValueError("ADB device not online")
    def shell(*args):
        return run(adb + ["shell", *args])
    def optional(*args):
        try:
            return shell(*args)
        except (OSError, subprocess.SubprocessError):
            return ""
    props = {p: shell("getprop", p) for p in PROPS}
    if shell("getprop", "sys.boot_completed") != "1":
        raise ValueError("Android has not completed boot")
    thermal_command = ('for z in /sys/class/thermal/thermal_zone*; do '
                       '[ -r "$z/temp" ] || continue; '
                       'printf "%s\\t%s\\n" "$(cat "$z/type" 2>/dev/null)" "$(cat "$z/temp")"; done')
    frequency_command = ('for c in /sys/devices/system/cpu/cpu[0-9]*; do '
                         '[ -r "$c/cpufreq/scaling_cur_freq" ] || continue; '
                         'printf "%s\\t%s\\n" "${c##*/}" "$(cat "$c/cpufreq/scaling_cur_freq")"; done')
    gpu_command = ('for d in /sys/class/devfreq/*; do '
                   '[ -r "$d/cur_freq" ] || continue; '
                   'printf "%s\\t%s\\n" "${d##*/}" "$(cat "$d/cur_freq")"; done')
    static = {
        "display_size": optional("wm", "size"),
        "display_density": optional("wm", "density"),
        "data_filesystem": optional("df", "-k", "/data"),
        "cpu_possible": optional("cat", "/sys/devices/system/cpu/possible"),
        "cpu_present": optional("cat", "/sys/devices/system/cpu/present"),
        "cpu_online": optional("cat", "/sys/devices/system/cpu/online"),
        "media_codecs_sha256": hashlib.sha256(optional("dumpsys", "media.codec").encode()).hexdigest(),
    }
    boot_event_text = optional("logcat", "-d", "-b", "events", "-v", "brief",
                               "boot_progress_start:I", "boot_progress_preload_start:I",
                               "boot_progress_preload_end:I", "boot_progress_system_run:I",
                               "boot_progress_pms_start:I", "boot_progress_pms_system_scan_start:I",
                               "boot_progress_pms_data_scan_start:I", "boot_progress_pms_scan_end:I",
                               "boot_progress_pms_ready:I", "boot_progress_ams_ready:I",
                               "boot_progress_enable_screen:I", "*:S")
    boot_timeline = parse_boot_events(boot_event_text)
    boot_properties = {key: value for key, value in
                       (line.strip("[]").split("]: [", 1) for line in optional("getprop").splitlines()
                        if line.startswith("[ro.boottime."))}
    gfx_before = parse_gfxinfo(optional("dumpsys", "gfxinfo", package)) if package else {}
    samples = []
    for _ in range(repetitions):
        a = cpu_ticks(shell("cat", "/proc/stat"))
        time.sleep(interval)
        b = cpu_ticks(shell("cat", "/proc/stat"))
        m = parse_meminfo(shell("cat", "/proc/meminfo"))
        loads = parse_loadavg(shell("cat", "/proc/loadavg"))
        thermals = parse_thermal(optional("sh", "-c", thermal_command))
        frequencies = parse_named_ints(optional("sh", "-c", frequency_command))
        gpu_frequencies = parse_named_ints(optional("sh", "-c", gpu_command))
        samples.append({"cpu_busy_pct_all_cores": cpu_percent(a, b),
                        "mem_available_kib": m.get("MemAvailable"),
                        "mem_total_kib": m.get("MemTotal"),
                        "swap_total_kib": m.get("SwapTotal"),
                        **loads,
                        "uptime_seconds": float(shell("cat", "/proc/uptime").split()[0]),
                        "thermal_max_c": max(thermals.values()) if thermals else None,
                        "thermal_zones_c": thermals,
                        "cpu_frequency_min_khz": min(frequencies.values()) if frequencies else None,
                        "cpu_frequency_max_khz": max(frequencies.values()) if frequencies else None,
                        "cpu_frequencies_khz": frequencies,
                        "gpu_frequencies_hz": gpu_frequencies})
    medians = summarize_samples(samples)
    gfx_after = parse_gfxinfo(optional("dumpsys", "gfxinfo", package)) if package else {}
    warnings = []
    if samples[0].get("swap_total_kib"):
        warnings.append("swap is configured")
    if medians.get("thermal_peak_c", 0) >= 85:
        warnings.append("thermal peak reached 85 C or higher")
    if gfx_after.get("janky_percent") is not None and gfx_after["janky_percent"] > 5:
        warnings.append("graphics jank exceeds 5 percent")
    result = {"status": "MEASURED_EXTENDED", "properties": props, "kernel": shell("uname", "-r"),
              "selinux": shell("getenforce"), "interval_seconds": interval,
              "sample_count": repetitions, "samples": samples, "medians": medians,
              "static": static, "graphics_package": package,
              "graphics_before": gfx_before, "graphics_after": gfx_after,
              "boot": {"boot_id": optional("cat", "/proc/sys/kernel/random/boot_id"),
                       "timeline": boot_timeline, "boottime_properties": boot_properties,
                       "scope": "current boot; compare multiple cold boots separately"},
              "warnings": warnings,
              "idle_condition": "operator must confirm; not enforced",
              "not_tested": ["cold boot timing", "interactive input latency", "GPU/VPU stress",
                             "video playback quality", "network throughput", "storage throughput",
                             "24h stability", "OTA", "recovery"]}
    save(work / "benchmark.json", result)
    with (work / "benchmark.csv").open("w", newline="") as f:
        scalar_fields = [key for key, value in samples[0].items()
                         if not isinstance(value, (dict, list))]
        w = csv.DictWriter(f, fieldnames=scalar_fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(samples)
    return 0

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("doctor", "research", "extract", "benchmark",
        "test", "sync", "configure", "kernel", "build", "package", "verify"))
    parser.add_argument("--work", type=Path, default=AOSP / "work")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--images", type=Path, default=ROOT / "dl/a12/Image")
    parser.add_argument("--serial")
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--interval", type=float, default=2)
    parser.add_argument("--package", default="com.tvlauncher",
                        help="package whose cumulative gfxinfo is recorded; empty disables it")
    a = parser.parse_args()
    os.umask(0o077)
    a.work.mkdir(parents=True, exist_ok=True)
    try:
        if a.command == "doctor":
            return doctor(a.work)
        if a.command == "research":
            return research(a.work, a.manifest)
        if a.command == "extract":
            return inventory(a.work, a.images)
        if a.command == "benchmark":
            if a.repetitions < 5 or a.interval < 1:
                parser.error("at least five repetitions and one-second interval")
            return benchmark(a.work, a.serial, a.repetitions, a.interval, a.package)
        if a.command == "test":
            return subprocess.call([sys.executable, "-B", "-m", "unittest", "discover",
                                    "-s", str(AOSP / "tests"), "-v"])
        print("BLOCKED: " + a.command + " requires verified source manifest and device product; "
              "no stock fallback, signing, flashing or fake build", file=sys.stderr)
        return BLOCKED
    except (OSError, ValueError, subprocess.SubprocessError, ET.ParseError) as e:
        print("FAILED: " + type(e).__name__, file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
