#!/usr/bin/env python3
"""Проверить собранные ext4-разделы на сохранённые Wi-Fi-профили."""
import argparse
import pathlib
import re
import subprocess
import sys


CONFIG_PATHS = (
    "/etc/tvbox/wifi.env",
    "/etc/wifi/wpa_supplicant.conf",
    "/etc/wifi/wpa_supplicant_overlay.conf",
    "/etc/wifi/p2p_supplicant.conf",
    "/etc/misc/wifi/WifiConfigStore.xml",
    "/misc/wifi/WifiConfigStore.xml",
)
NETWORK_BLOCK = re.compile(r"network\s*=\s*\{(.*?)\}", re.S | re.I)
WIFI_FIELD = re.compile(r"^\s*(?:ssid2?|psk|sae_password|wep_key\d*|identity|password)\s*=\s*(\S.*)$", re.I)
XML_WIFI_FIELD = re.compile(
    r"<string\s+name=['\"](?:SSID|PreSharedKey|Password)['\"]\s*>\s*['\"]?\S.*?</string>", re.I | re.S
)
ENV_WIFI_FIELD = re.compile(r"^\s*(?:WIFI_)?(?:SSID|PSK|PASSWORD)\s*=\s*\S+", re.I | re.M)


def configured_networks(text):
    """Найти профили, содержащие SSID или ключ, не показывая значения."""
    if ENV_WIFI_FIELD.search(text) or XML_WIFI_FIELD.search(text):
        return True
    for block in NETWORK_BLOCK.findall(text):
        if any(WIFI_FIELD.match(line) for line in block.splitlines()):
            return True
    return False


def debugfs(image, command):
    """Выполнить read-only команду debugfs и вернуть текст без печати наружу."""
    result = subprocess.run(["debugfs", "-R", command, str(image)],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    return result.stdout.decode("utf-8", "replace"), result.stderr.decode("utf-8", "replace")


def check_image(image):
    """Проверить известные места хранения Wi-Fi-конфигурации в ext4-образе."""
    image = pathlib.Path(image)
    if not image.is_file():
        raise ValueError(f"образ не найден: {image}")
    root_out, root_err = debugfs(image, "stat /")
    if "Type:" not in root_out + root_err:
        raise ValueError(f"не удалось открыть ext4-образ: {image}")
    checked = 0
    for path in CONFIG_PATHS:
        stat_out, stat_err = debugfs(image, f"stat {path}")
        stat_text = stat_out + stat_err
        if "Type:" not in stat_text and "File not found by ext2_lookup" in stat_text:
            # Отсутствие проверяемого файла — ожидаемый безопасный результат.
            continue
        if "Type:" not in stat_text:
            raise ValueError(f"не удалось проверить путь в образе: {path}")
        checked += 1
        if path.endswith("/wifi.env"):
            raise ValueError(f"в образе присутствует запрещённый файл: {path}")
        content, error = debugfs(image, f"cat {path}")
        if "Filesystem not open" in error:
            raise ValueError(f"не удалось прочитать файл конфигурации: {path}")
        if configured_networks(content):
            raise ValueError(f"в образе найден сохранённый Wi-Fi-профиль: {path}")
    return checked


def main():
    parser = argparse.ArgumentParser(description="Искать сохранённые Wi-Fi-профили в системных образах.")
    parser.add_argument("images", nargs="+", help="ext4-образы system/vendor")
    args = parser.parse_args()
    try:
        checked = sum(check_image(image) for image in args.images)
    except (OSError, ValueError) as error:
        print(f"Ошибка проверки Wi-Fi-конфигурации: {error}", file=sys.stderr)
        return 1
    print(f"Wi-Fi credentials не найдены; проверено файлов конфигурации: {checked}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
