#!/usr/bin/env python3
"""Проверка доступности каналов в M3U с CSV-отчётом и удалением дублей."""
import argparse
import concurrent.futures
import csv
import datetime
import hashlib
import importlib.util
import json
import pathlib
import sys
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("iptv_merge", ROOT / "iptv-merge.py")
MERGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MERGE)


def channel_key(entry):
    """Стабильный ключ без адреса потока и параметров доступа."""
    identity = entry.get("attrs", {}).get("tvg-id", "").strip().lower()
    if not identity:
        identity = MERGE.norm(entry.get("name", ""))
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()[:24]


def load_history(path):
    """Загрузить JSONL-историю; повреждённые строки пропустить, сохраняя файл как есть."""
    events = []
    history = pathlib.Path(path)
    if not history.is_file():
        return events
    for line in history.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            item = json.loads(line)
            if item.get("channel_key") and item.get("scope") in ("global", "device"):
                events.append(item)
        except (json.JSONDecodeError, AttributeError):
            continue
    return events


def append_history(path, events):
    """Добавить только сводные метаданные; URL потоков и логотипов не сохраняются."""
    history = pathlib.Path(path)
    history.parent.mkdir(parents=True, exist_ok=True)
    with history.open("a", encoding="utf-8") as stream:
        for item in events:
            stream.write(json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n")


def exclusion_status(channel_key_value, events, scope, min_failures=3, spacing_hours=24):
    """Исключать только после серии неудач с интервалом; успех обнуляет серию."""
    scoped = [e for e in events if e.get("channel_key") == channel_key_value and e.get("scope") == scope]
    scoped.sort(key=lambda e: e.get("checked_at", ""))
    streak = []
    previous = None
    for event in scoped:
        if event.get("stream_height", 0) > 0:
            streak = []
            previous = None
            continue
        try:
            checked = datetime.datetime.fromisoformat(event["checked_at"])
        except (KeyError, TypeError, ValueError):
            continue
        if previous is None or checked - previous >= datetime.timedelta(hours=spacing_hours):
            streak.append(checked)
            previous = checked
    return len(streak) >= min_failures


def availability_rating(channel_key_value, events, scope, days=30, now=None):
    """Доля ответивших потоков за окно; None означает, что истории пока нет."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    cutoff = now - datetime.timedelta(days=days)
    recent = []
    for event in events:
        if event.get("channel_key") != channel_key_value or event.get("scope") != scope:
            continue
        try:
            checked = datetime.datetime.fromisoformat(event["checked_at"])
        except (KeyError, TypeError, ValueError):
            continue
        if cutoff <= checked <= now:
            recent.append(event)
    if not recent:
        return None
    return round(100 * sum(e.get("stream_height", 0) > 0 for e in recent) / len(recent))


def has_recent_full_hd(channel_key_value, events, scope, days=30, now=None):
    """Разрешить переждать таймаут только при недавнем подтверждённом замере 1080 строк."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    cutoff = now - datetime.timedelta(days=days)
    for event in events:
        if event.get("channel_key") != channel_key_value or event.get("scope") != scope:
            continue
        if event.get("stream_height", 0) < 1080:
            continue
        try:
            checked = datetime.datetime.fromisoformat(event["checked_at"])
        except (KeyError, TypeError, ValueError):
            continue
        if cutoff <= checked <= now:
            return True
    return False


def main():
    parser = argparse.ArgumentParser(description="Проверить M3U, схлопнуть варианты качества и сохранить отчёт.")
    parser.add_argument("playlist", help="входной M3U")
    parser.add_argument("--report", default="diagnostics/iptv-verify.csv", help="путь CSV-отчёта")
    parser.add_argument("--prune", help="записать только доступные уникальные каналы в этот M3U")
    parser.add_argument("--workers", type=int, default=24, help="число параллельных проверок")
    parser.add_argument("--scope", choices=("global", "device"), default="global",
                        help="где выполнен замер; результаты разных областей не смешиваются")
    parser.add_argument("--history", default="diagnostics/iptv-availability-history.jsonl",
                        help="JSONL-история, в которой не сохраняются потоковые URL")
    parser.add_argument("--min-spaced-failures", type=int, default=3,
                        help="сколько раз канал должен подряд не пройти проверку перед исключением")
    args = parser.parse_args()
    if args.workers < 1:
        parser.error("--workers должен быть не меньше 1")

    if args.min_spaced_failures < 2:
        parser.error("--min-spaced-failures должен быть не меньше 2")

    text = pathlib.Path(args.playlist).read_text(encoding="utf-8", errors="replace")
    previous_events = load_history(args.history)
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
        stream_results = dict(zip(stream_urls, pool.map(MERGE.stream_resolution, stream_urls)))

    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    rows, available, new_events = [], [], []
    for entry in entries:
        attrs = entry["attrs"]
        name_key = MERGE.norm(entry["name"])
        canonical = id(entry) in unique_ids
        logo = attrs.get("tvg-logo", "")
        logo_ok = logo_results.get(logo, False)
        height = stream_results.get(entry["url"], 0)
        stream_ok = height > 0
        qualified = height >= 1080
        key = channel_key(entry)
        if not canonical:
            status = "ДУБЛЬ"
        elif not stream_ok:
            status = "ВРЕМЕННЫЙ СБОЙ"
        elif not qualified:
            status = "НИЖЕ 1080p"
        elif not logo_ok:
            status = "НЕТ ЛОГОТИПА"
        else:
            status = "OK"
        reason = "" if status == "OK" else ("совпадает название/URL с основным вариантом" if status == "ДУБЛЬ" else
            "; ".join(part for part, failed in (("нет рабочего логотипа", not logo_ok),
                                                  ("поток недоступен или разрешение не определено", not stream_ok),
                                                  ("ниже 1080p", stream_ok and not qualified)) if failed))
        if canonical:
            new_events.append({"channel_key": key, "checked_at": stamp, "scope": args.scope,
                               "stream_height": height, "group": attrs.get("group-title", ""),
                               "channel": entry["name"]})
        current_history = previous_events + new_events
        excluded = exclusion_status(key, current_history, args.scope, args.min_spaced_failures)
        if status == "ВРЕМЕННЫЙ СБОЙ" and excluded:
            status = "ИСКЛЮЧЁН"
        rows.append({"проверено_utc": stamp, "канал": entry["name"], "группа": attrs.get("group-title", ""),
                     "качество_дублей": len(duplicate_names[name_key]), "статус": status, "причина": reason,
                     "разрешение_строк": height, "рейтинг_30д": availability_rating(key, current_history, args.scope),
                     "узел_лого": urllib.parse.urlsplit(logo).hostname or "",
                     "узел_потока": urllib.parse.urlsplit(entry["url"]).hostname or ""})
        # Краткий таймаут не стирает недавно измеренное Full HD качество,
        # но неизвестный поток не должен просочиться в итоговый список.
        previously_full_hd = has_recent_full_hd(key, current_history, args.scope)
        if status == "OK" or (status == "ВРЕМЕННЫЙ СБОЙ" and not excluded and previously_full_hd):
            available.append(entry)

    append_history(args.history, new_events)

    report = pathlib.Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0].keys() if rows else
                                ("проверено_utc", "канал", "группа", "качество_дублей", "статус", "причина",
                                 "разрешение_строк", "рейтинг_30д", "узел_лого", "узел_потока"), delimiter=";")
        writer.writeheader()
        writer.writerows(rows)

    if args.prune:
        MERGE.write(available, args.prune)
    excluded_count = sum(row["статус"] == "ИСКЛЮЧЁН" for row in rows)
    print(f"Каналов: {len(entries)}; уникальных после схлопывания качества: {len(unique)}; не ниже 1080p: {len(available)}; устойчиво исключено: {excluded_count}")
    print(f"CSV-отчёт: {report}")
    print(f"История ({args.scope}): {args.history}")
    if args.prune:
        print(f"Очищенный M3U: {args.prune}")
    return 0 if available else 1


if __name__ == "__main__":
    sys.exit(main())
