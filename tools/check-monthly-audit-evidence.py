#!/usr/bin/env python3
"""Проверить свежесть ежемесячного анализа журналов и сетевого трафика."""
import argparse
import datetime
import json
import pathlib
import sys


def validate(data, today=None, max_age_days=31):
    """Вернуть список отсутствующих, устаревших или небезопасных подтверждений."""
    today = today or datetime.date.today()
    if not isinstance(data, dict):
        return ["корень отчёта должен быть JSON-объектом"]
    errors = []
    try:
        audit_date = datetime.date.fromisoformat(data["audit_date"])
    except (KeyError, TypeError, ValueError):
        errors.append("audit_date отсутствует или имеет неверный формат YYYY-MM-DD")
        audit_date = None
    if audit_date:
        if audit_date > today:
            errors.append("audit_date находится в будущем")
        elif (today - audit_date).days > max_age_days:
            errors.append(f"последний аудит старше {max_age_days} дней")
    if data.get("logs_reviewed") is not True:
        errors.append("анализ журналов не подтверждён")
    if data.get("network_capture_reviewed") is not True:
        errors.append("анализ сетевого захвата не подтверждён")
    if not isinstance(data.get("capture_duration_seconds"), int) or data["capture_duration_seconds"] < 1:
        errors.append("длительность сетевого захвата не указана")
    if not isinstance(data.get("captured_packets"), int) or data["captured_packets"] < 1:
        errors.append("число захваченных пакетов не указано")
    if data.get("raw_capture_retained") is not False:
        errors.append("сырой PCAP должен быть удалён")
    if not data.get("sanitized_summary"):
        errors.append("отсутствует очищенная сводка аудита")
    return errors


def main():
    parser = argparse.ArgumentParser(description="Проверить, что ежемесячный security-аудит свежий и очищен.")
    parser.add_argument("--file", default="docs/security/monthly-audit.json")
    parser.add_argument("--max-age-days", type=int, default=31)
    args = parser.parse_args()
    try:
        data = json.loads(pathlib.Path(args.file).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"Не удалось прочитать отчёт аудита: {error}", file=sys.stderr)
        return 1
    errors = validate(data, max_age_days=args.max_age_days)
    if errors:
        print("Ежемесячный security-аудит не прошёл проверку свежести:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"Ежемесячный аудит от {data['audit_date']}: журналы и сетевой захват проверены; сырой PCAP отсутствует")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
