#!/usr/bin/env python3
"""Проверить протокол аппаратной приёмки перед созданием публичного релиза."""
import argparse
import json
import pathlib
import sys


REQUIRED_CHECKS = (
    "cold_boot_le_10s", "boot_log_review", "security_review", "qr_vlc", "qr_total_commander",
    "wired_network", "wifi_network", "iptv_playback", "torrent_playback", "graphics_video",
    "graphics_stress", "swap_disabled",
)


def main():
    parser = argparse.ArgumentParser(description="Проверить аппаратный протокол выпуска.")
    parser.add_argument("--file", required=True, help="JSON с результатами аппаратной приёмки")
    parser.add_argument("--version", required=True, help="ожидаемая версия выпуска")
    args = parser.parse_args()
    path = pathlib.Path(args.file)
    if not path.is_file():
        print(f"Нет протокола аппаратной приёмки: {path}", file=sys.stderr)
        return 1
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"Не удалось прочитать протокол: {error}", file=sys.stderr)
        return 1

    failures = []
    if data.get("version") != args.version:
        failures.append("версия в протоколе не совпадает с тегом")
    if not data.get("device"):
        failures.append("не указано целевое устройство")
    tests = data.get("automated_tests", {})
    if tests.get("status") != "pass" or not isinstance(tests.get("count"), int) or tests["count"] < 1:
        failures.append("автоматические тесты не отмечены как пройденные")
    checks = data.get("checks", {})
    for name in REQUIRED_CHECKS:
        if checks.get(name) != "pass":
            failures.append(f"проверка {name}: статус должен быть pass")
    elapsed = data.get("cold_boot_seconds")
    if not isinstance(elapsed, (int, float)) or elapsed > 10:
        failures.append("холодная загрузка не подтверждена в пределах 10 секунд")
    logs = data.get("log_analysis", {})
    if logs.get("reviewed") is not True:
        failures.append("разбор журналов не отмечен как выполненный")
    if logs.get("untriaged_errors") != 0:
        failures.append("в журналах остались ошибки без разбора")
    if not logs.get("summary"):
        failures.append("в протоколе отсутствует краткий итог анализа журналов")

    if failures:
        print("Релиз не прошёл аппаратный gate:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print(f"Аппаратный протокол {args.version}: все проверки пройдены")
    return 0


if __name__ == "__main__":
    sys.exit(main())
