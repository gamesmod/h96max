#!/usr/bin/env python3
"""Проверка доступности каналов в M3U с CSV-отчётом и удалением дублей."""
import argparse
import concurrent.futures
import csv
import datetime
import importlib.util
import pathlib
import sys
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("iptv_merge", ROOT / "iptv-merge.py")
MERGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MERGE)


def main():
    parser = argparse.ArgumentParser(description="Проверить M3U, схлопнуть варианты качества и сохранить отчёт.")
    parser.add_argument("playlist", help="входной M3U")
    parser.add_argument("--report", default="diagnostics/iptv-verify.csv", help="путь CSV-отчёта")
    parser.add_argument("--prune", help="записать только доступные уникальные каналы в этот M3U")
    parser.add_argument("--workers", type=int, default=24, help="число параллельных проверок")
    args = parser.parse_args()
    if args.workers < 1:
        parser.error("--workers должен быть не меньше 1")

    text = pathlib.Path(args.playlist).read_text(encoding="utf-8", errors="replace")
    entries = MERGE.parse(text)
    unique = MERGE.dedupe(entries)
    unique_ids = {id(entry) for entry in unique}
    duplicate_names = {}
    for entry in entries:
        duplicate_names.setdefault(MERGE.norm(entry["name"]), []).append(entry)
    logo_urls = {e["attrs"].get("tvg-logo", "") for e in unique if e["attrs"].get("tvg-logo", "").startswith("http")}
    stream_urls = {e["url"] for e in unique}

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        logo_results = dict(zip(logo_urls, pool.map(MERGE.logo_ok, logo_urls)))
        stream_results = dict(zip(stream_urls, pool.map(MERGE.stream_ok, stream_urls)))

    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    rows, available = [], []
    for entry in entries:
        attrs = entry["attrs"]
        name_key = MERGE.norm(entry["name"])
        canonical = id(entry) in unique_ids
        logo = attrs.get("tvg-logo", "")
        logo_ok = logo_results.get(logo, False)
        stream_ok = stream_results.get(entry["url"], False)
        status = "OK" if canonical and logo_ok and stream_ok else "ДУБЛЬ" if not canonical else "НЕДОСТУПЕН"
        reason = "" if status == "OK" else ("совпадает название после удаления качества" if status == "ДУБЛЬ" else
            "; ".join(part for part, failed in (("нет рабочего логотипа", not logo_ok), ("поток не прошёл проверку", not stream_ok)) if failed))
        rows.append({"проверено_utc": stamp, "канал": entry["name"], "группа": attrs.get("group-title", ""),
                     "качество_дублей": len(duplicate_names[name_key]), "статус": status, "причина": reason,
                     "узел_лого": urllib.parse.urlsplit(logo).hostname or "",
                     "узел_потока": urllib.parse.urlsplit(entry["url"]).hostname or ""})
        if status == "OK":
            available.append(entry)

    report = pathlib.Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0].keys() if rows else
                                ("проверено_utc", "канал", "группа", "качество_дублей", "статус", "причина", "узел_лого", "узел_потока"), delimiter=";")
        writer.writeheader()
        writer.writerows(rows)

    if args.prune:
        MERGE.write(available, args.prune)
    print(f"Каналов: {len(entries)}; уникальных после схлопывания качества: {len(unique)}; доступны: {len(available)}")
    print(f"CSV-отчёт: {report}")
    if args.prune:
        print(f"Очищенный M3U: {args.prune}")
    return 0 if available else 1


if __name__ == "__main__":
    sys.exit(main())
