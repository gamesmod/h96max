#!/usr/bin/env python3
"""Ограниченная диагностическая проверка из фактической сети запуска.
Требуются Python 3.10+ и curl с поддержкой SOCKS5/HTTPS.
"""
import argparse
import concurrent.futures
import ipaddress
import json
import os
from pathlib import Path
import random
import re
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent
PROTOCOLS = ("socks5", "http")
MAX_SOURCE = 2 * 1024 * 1024
MEDIA_BYTES = 256 * 1024
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def normalize(line, protocol):
    line = line.strip()
    if "://" in line:
        scheme, line = line.split("://", 1)
        if scheme.lower() != protocol:
            return None
    try:
        host, port = line.rsplit(":", 1)
        ip = ipaddress.ip_address(host.strip("[]"))
        if not ip.is_global or not port.isascii() or not port.isdecimal():
            return None
        n = int(port)
        if not 1 <= n <= 65535:
            return None
        return (protocol, str(ip), n)
    except ValueError:
        return None

def parse(data, protocol, fmt):
    if fmt == "geonode":
        entries = json.loads(data)["data"]
        lines = [str(x["ip"]) + ":" + str(x["port"]) for x in entries
                 if protocol in x.get("protocols", [])]
    elif fmt == "txt":
        lines = data.decode("utf-8-sig").splitlines()
    else:
        raise ValueError("unknown source format")
    return {p for line in lines if (p := normalize(line, protocol)) is not None}

def fetch(url):
    if urllib.parse.urlsplit(url).scheme != "https":
        raise ValueError("HTTPS required")
    with OPENER.open(urllib.request.Request(url, headers={"User-Agent":"Panadonik-Proxy-Check/1"}), timeout=10) as response:
        if urllib.parse.urlsplit(response.geturl()).scheme != "https":
            raise ValueError("HTTPS redirect required")
        data = response.read(MAX_SOURCE + 1)
    if len(data) > MAX_SOURCE:
        raise ValueError("source too large")
    return data

def collect_one(source, protocol, cache):
    if not re.fullmatch(r"[a-z0-9_-]{1,40}", source["id"]):
        raise ValueError("invalid source id")
    name = cache / (source["id"] + "-" + protocol + ".cache")
    for url in source["urls"]:
        try:
            raw = fetch(url.replace("{protocol}", protocol))
            proxies = parse(raw, protocol, source["format"])
            if not proxies:
                continue
            fd, tmp = tempfile.mkstemp(dir=cache)
            try:
                with os.fdopen(fd, "wb") as f:
                    f.write(raw)
                os.replace(tmp, name)
            finally:
                if os.path.exists(tmp):
                    os.unlink(tmp)
            return proxies, "fresh"
        except (OSError, ValueError, KeyError, TypeError):
            continue
    try:
        return parse(name.read_bytes(), protocol, source["format"]), "cached"
    except (OSError, ValueError, KeyError, TypeError):
        return set(), "unavailable"

def media_valid(url):
    u = urllib.parse.urlsplit(url)
    return (u.scheme == "https" and u.hostname is not None and
            u.hostname.endswith(".googlevideo.com") and u.path == "/videoplayback"
            and u.username is None and u.password is None and u.port in (None, 443)
            and bool(u.query) and not u.fragment and
            not any(c in url for c in ('"', '\\', '\r', '\n')))

def proxy_url(proxy):
    protocol, ip, port = proxy
    host = "[" + ip + "]" if ":" in ip else ip
    return ("socks5h" if protocol == "socks5" else "http") + "://" + host + ":" + str(port)

def request(proxy, url, media=False, connect_timeout=5, timeout=15):
    # URL в stdin curl, не в аргументах процесса; не выводим тело/ошибки.
    args = ["curl", "-q", "--config", "-", "--silent", "--proto", "=https",
            "--proxy", proxy_url(proxy), "--noproxy", "", "--connect-timeout", str(connect_timeout),
            "--max-time", str(timeout), "--max-filesize", str(MEDIA_BYTES if media else 524288),
            "--range", "0-" + str(MEDIA_BYTES-1 if media else 65535),
            "--output", os.devnull, "--write-out", "%{http_code} %{size_download} %{time_starttransfer} %{speed_download} %{content_type}"]
    try:
        result = subprocess.run(args, input='url = "' + url + '"\n', text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                timeout=timeout+3, check=False)
        fields = result.stdout.split(maxsplit=4)
        if len(fields) < 4:
            return result.returncode, 0, 0, 0.0, 0.0, ""
        return (result.returncode, int(fields[0]), int(float(fields[1])),
                float(fields[2])*1000, float(fields[3])*8/1000000,
                fields[4] if len(fields)>4 else "")
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return -1, 0, 0, 0.0, 0.0, ""

def classify_error(code, http, stage):
    if code in (51, 60, 77, 83, 90, 91):
        return "TLS_ERROR"
    if code in (5, 7, 28, -1):
        return "UNREACHABLE"
    if code == 97 or http == 407:
        return "PROTOCOL_ERROR"
    return "CDN_BLOCKED" if stage == "media" else "YOUTUBE_BLOCKED"

def classify_media(result, minimum):
    code, http, size, latency, mbps, mime = result
    if code or http != 206 or size < 65536 or not mime.lower().startswith(("video/", "audio/")):
        return classify_error(code, http, "media")
    return "WORKING" if mbps >= minimum else "SLOW"

def check(proxy, media_url, minimum, connect_timeout, timeout):
    endpoints = [
        ("https://www.youtube.com/", (200, 301, 302, 303, 307, 308)),
        ("https://youtubei.googleapis.com/", (200, 400, 403, 404, 405)),
        ("https://i.ytimg.com/", (200, 301, 302, 403, 404))]
    for url, allowed in endpoints:
        code, http, _, _, _, _ = request(proxy, url, connect_timeout=connect_timeout, timeout=timeout)
        # 63: намеренно остановленная большая загрузка; TLS/HTTP уже проверены.
        if code not in (0, 63) or http not in allowed:
            return {"status": classify_error(code, http, "api")}
    if not media_url:
        return {"status":"UNKNOWN", "reason":"media_url_required"}
    result = request(proxy, media_url, True, connect_timeout, timeout)
    status = classify_media(result, minimum)
    successes = int(status == "WORKING")
    if successes:
        again = request(proxy, media_url, True, connect_timeout, timeout)
        successes += int(classify_media(again, minimum) == "WORKING")
        if successes == 2:
            status = "STABLE"
    return {"status": status, "mediaBytes": result[2], "latencyMs": round(result[3]),
            "mbps": round(result[4],2), "successfulMediaChecks":successes}

def main():
    parser = argparse.ArgumentParser(description="Проверка источников и YouTube без Google credentials")
    parser.add_argument("--sources", type=Path, default=ROOT/"sources.json")
    parser.add_argument("--cache", type=Path, default=Path.home()/".cache"/"panadonik-proxy")
    parser.add_argument("--media-url-file", type=Path, help="Файл с действительной HTTPS медиассылкой без cookies")
    parser.add_argument("--limit", type=int, default=64)
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--minimum", type=float, default=10)
    parser.add_argument("--connect-timeout", type=int, default=5)
    parser.add_argument("--timeout", type=int, default=15)
    parser.add_argument("--sources-only", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.workers <= 16 or not 1 <= args.limit <= 256 or not 0 < args.minimum or not 1 <= args.connect_timeout <= args.timeout <= 30:
        parser.error("invalid limits")
    if not args.sources_only and not shutil.which("curl"):
        parser.error("curl required")
    media = args.media_url_file.read_text(encoding="utf-8").strip() if args.media_url_file else None
    if media and not media_valid(media):
        parser.error("invalid media URL")
    args.cache.mkdir(mode=0o700, parents=True, exist_ok=True)
    config = json.loads(args.sources.read_text(encoding="utf-8"))
    candidates = set()
    source_results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(args.workers, 8)) as pool:
        jobs = [(s["id"], p, pool.submit(collect_one,s,p,args.cache)) for s in config for p in PROTOCOLS]
        for source, protocol, future in jobs:
            try:
                items, state = future.result()
            except (ValueError, OSError, KeyError, TypeError):
                items, state = set(), "unavailable"
            candidates.update(items)
            source_results.append({"source":source,"protocol":protocol,"state":state,"count":len(items)})
    print(json.dumps({"sources":source_results,"uniqueCandidates":len(candidates)}, ensure_ascii=False), flush=True)
    if args.sources_only:
        return
    # Случайная выборка ограничивает объём и не тестирует тысячи адресов подряд.
    selected = random.sample(sorted(candidates), min(len(candidates),args.limit))
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(lambda p:check(p,media,args.minimum,args.connect_timeout,args.timeout),selected))
    counts = {}
    for result in results:
        counts[result["status"]] = counts.get(result["status"],0)+1
    # Публичный отчёт не содержит адреса прокси и подписанные URL.
    print(json.dumps({"checked":len(results),"statuses":counts,"mediaSupplied":bool(media)},ensure_ascii=False))

if __name__ == "__main__":
    main()
