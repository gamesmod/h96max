#!/usr/bin/env python3
"""Проверить публичные источники IPTV и итоговый M3U на URL с учётными данными."""
import argparse
import importlib.util
import pathlib
import sys
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("iptv_merge", ROOT / "iptv-merge.py")
MERGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MERGE)
SENSITIVE = {"token", "auth", "password", "passwd", "username", "user", "api_key", "apikey", "access_token", "signature", "sig"}


def safe_url(url):
    parts = urllib.parse.urlsplit(url)
    if parts.username or parts.password:
        return False
    if any(key.lower() in SENSITIVE for key, _ in urllib.parse.parse_qsl(parts.query, keep_blank_values=True)):
        return False
    return True


def main():
    parser = argparse.ArgumentParser(description="Ищет приватные URL-параметры перед сборкой публичного OTA.")
    parser.add_argument("--sources", default="tools/iptv-sources.txt")
    parser.add_argument("--playlist", help="проверить также сгенерированный общий M3U")
    parser.add_argument("--min-adult", type=int, default=20)
    args = parser.parse_args()
    urls = []
    for line in pathlib.Path(args.sources).read_text(encoding="utf-8").splitlines():
        fields = line.strip().split()
        if fields and not fields[0].startswith("#"):
            urls.append(fields[0])
    if args.playlist:
        entries = MERGE.parse(pathlib.Path(args.playlist).read_text(encoding="utf-8", errors="replace"))
        groups = list(dict.fromkeys(entry["attrs"].get("group-title", "") for entry in entries))
        if groups and groups[0] != "Для взрослых":
            print("Ошибка: категория «Для взрослых» должна быть первой в общем плейлисте", file=sys.stderr)
            return 1
        for entry in entries:
            urls.extend((entry["url"], entry["attrs"].get("tvg-logo", "")))
        adult_count = sum(entry["attrs"].get("group-title") == "Для взрослых" for entry in entries)
        if adult_count < args.min_adult:
            print(f"Ошибка: доступных каналов 18+ {adult_count}, требуется минимум {args.min_adult}", file=sys.stderr)
            return 1
    if any(url and not safe_url(url) for url in urls):
        print("Ошибка: URL с учётными данными запрещён в публичном релизе", file=sys.stderr)
        return 1
    print(f"Проверено URL: {len(urls)}; учётные данные не обнаружены")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
