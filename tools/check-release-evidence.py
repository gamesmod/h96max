#!/usr/bin/env python3
"""Проверить протокол аппаратной приёмки перед созданием публичного релиза."""
import argparse
import datetime
import hashlib
import json
import pathlib
import sys


FUNCTIONAL_CHECKS = (
    "cold_boot_ready", "boot_log_review", "qr_vlc",
    "wired_network", "wifi_network", "iptv_playback", "torrent_playback", "graphics_video",
    "graphics_stress", "swap_disabled", "network_capture_review", "usb_remote_iptv_channels",
)
STABLE_SECURITY_CHECKS = ("security_review",)


def main():
    parser = argparse.ArgumentParser(description="Проверить аппаратный протокол выпуска.")
    parser.add_argument("--file", required=True, help="JSON с результатами аппаратной приёмки")
    parser.add_argument("--version", required=True, help="ожидаемая версия выпуска")
    parser.add_argument("--ota", required=True, help="OTA-файл, который предполагается выпустить")
    parser.add_argument("--channel", choices=("dev", "stable"), default="stable",
                        help="для dev security_review публикуется как известный риск, но не блокирует выпуск")
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
    candidate = data.get("candidate", {})
    expected_sha = candidate.get("ota_sha256") if isinstance(candidate, dict) else None
    try:
        digest = hashlib.sha256()
        with open(args.ota, "rb") as ota:
            for block in iter(lambda: ota.read(1024 * 1024), b""):
                digest.update(block)
        if not isinstance(expected_sha, str) or digest.hexdigest() != expected_sha.lower():
            failures.append("SHA-256 OTA не совпадает с артефактом в протоколе приёмки")
    except OSError:
        failures.append("не удалось прочитать OTA для проверки SHA-256")
    tests = data.get("automated_tests", {})
    if tests.get("status") != "pass" or type(tests.get("count")) is not int or tests["count"] < 1:
        failures.append("автоматические тесты не отмечены как пройденные")
    checks = data.get("checks", {})
    security_analysis = data.get("security_analysis", {})
    if (not isinstance(security_analysis, dict) or security_analysis.get("reviewed") is not True or
            not isinstance(security_analysis.get("summary"), str) or not security_analysis["summary"].strip()):
        failures.append("анализ безопасности обязателен для dev и stable; нужны отметка и сводка")
    if args.channel == "dev" and checks.get("security_review") != "pass":
        print("Примечание: security_review не блокирует dev-релиз; известные проблемы должны остаться в prerelease notes.")
    required_checks = FUNCTIONAL_CHECKS
    if args.channel == "stable":
        required_checks += STABLE_SECURITY_CHECKS
    for name in required_checks:
        if checks.get(name) != "pass":
            failures.append(f"проверка {name}: статус должен быть pass")
    if checks.get("network_capture_review") == "pass":
        hot_test = data.get("hot_test", {})
        capture = hot_test.get("network_capture", {}) if isinstance(hot_test, dict) else {}
        if not isinstance(capture, dict):
            capture = {}
        try:
            captured_at = datetime.date.fromisoformat(capture["captured_at"])
            age_days = (datetime.date.today() - captured_at).days
            if age_days < 0 or age_days > 31:
                failures.append("захват сетевого трафика должен быть не старше 31 дня")
        except (KeyError, TypeError, ValueError):
            failures.append("в протоколе нет корректной даты сетевого захвата")
        if type(capture.get("duration_seconds")) is not int or capture["duration_seconds"] < 1:
            failures.append("не указана длительность сетевого захвата")
        if type(capture.get("packets")) is not int or capture["packets"] < 1:
            failures.append("не указано число пакетов сетевого захвата")
        if capture.get("raw_capture_retained") is not False:
            failures.append("сырой PCAP должен быть удалён")
        if type(capture.get("auth_cookie_or_token_patterns")) is not int or capture["auth_cookie_or_token_patterns"] < 0:
            failures.append("не задокументирована проверка credentials в сетевом захвате")
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
    print(f"Аппаратный протокол {args.version} ({args.channel}): все обязательные проверки пройдены")
    return 0


if __name__ == "__main__":
    sys.exit(main())
