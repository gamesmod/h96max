#!/usr/bin/env python3
"""Проверить публичные источники IPTV и итоговый M3U на URL с учётными данными."""
import argparse
import importlib.util
import pathlib
import re
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


def validate_playlist(entries, min_adult=20):
    """Проверить публичные инварианты итогового M3U перед упаковкой образа."""
    issues = []
    groups = list(dict.fromkeys(entry.get("attrs", {}).get("group-title", "") for entry in entries))
    if not entries or not groups or groups[0] != "Для взрослых":
        issues.append("категория «Для взрослых» должна быть первой")
    adult_count = sum(entry.get("attrs", {}).get("group-title") == "Для взрослых" for entry in entries)
    if adult_count < min_adult:
        issues.append(f"каналов 18+ {adult_count}, требуется минимум {min_adult}")
    if any(any("a" <= ch.lower() <= "z" for ch in group) for group in groups):
        issues.append("найдены категории с латинскими буквами")
    if any(MERGE.is_child_channel(entry) for entry in entries):
        issues.append("найдены детские каналы")
    if len(MERGE.dedupe(entries)) != len(entries):
        issues.append("найдены дубли каналов или варианты качества одного канала")
    for entry in entries:
        name = entry.get("name", "")
        height = 0
        match = re.search(r"\((\d{3,4})p\)$", name, re.IGNORECASE)
        if match:
            height = int(match.group(1))
        elif re.search(r"\(4k\)$", name, re.IGNORECASE):
            height = 2160
        if height < 1080:
            issues.append("есть канал без итоговой метки измеренного качества 1080p или выше")
            break
    return issues


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
        issues = validate_playlist(entries, args.min_adult)
        if issues:
            for issue in issues:
                print(f"Ошибка: {issue}", file=sys.stderr)
            return 1
        for entry in entries:
            urls.extend((entry["url"], entry["attrs"].get("tvg-logo", "")))
    if any(url and not safe_url(url) for url in urls):
        print("Ошибка: URL с учётными данными запрещён в публичном релизе", file=sys.stderr)
        return 1
    if args.playlist:
        print(f"Плейлист проверен: каналов {len(entries)}, категорий 100% русских, дублей и детских каналов нет, качество от 1080p; URL проверено {len(urls)}")
    else:
        print(f"Проверено URL: {len(urls)}; учётные данные не обнаружены")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
