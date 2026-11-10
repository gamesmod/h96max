#!/usr/bin/env python3
"""Read-only health check for the connected H96 Max Android device via ADB.

The report is intentionally concise and redacted. No commands that change the
device are issued, and raw logcat output is never written to disk or printed.
"""
import argparse
import os
import re
import tempfile
import subprocess
import sys
import time
from datetime import datetime, timezone


def adb(args, timeout=15):
    p = subprocess.run(["adb", *args], text=True, stdout=subprocess.PIPE,
                       stderr=subprocess.DEVNULL, timeout=timeout, check=False)
    if p.returncode:
        return ""
    return p.stdout.strip()


def shell(command, timeout=15):
    return adb(["shell", command], timeout)


def getprop(name):
    return shell(f"getprop {name}")


def safe_value(value):
    value = re.sub(r"(?i)(ssid|password|passwd|psk|token|serial|mac|ip|bssid)\s*[:=]\s*\S+", r"\1=<скрыто>", value)
    value = re.sub(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", "<адрес скрыт>", value)
    value = re.sub(r"\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\b", "<MAC скрыт>", value)
    return value[:240]


def parse_meminfo(raw):
    vals = {}
    for line in raw.splitlines():
        m = re.match(r"(MemTotal|MemAvailable):\s+(\d+)", line)
        if m:
            vals[m.group(1)] = int(m.group(2))
    if "MemTotal" in vals and "MemAvailable" in vals:
        return vals["MemTotal"], vals["MemAvailable"]
    return None


def security_posture():
    props = {
        "ro.adb.secure": getprop("ro.adb.secure"),
        "ro.debuggable": getprop("ro.debuggable"),
        "SELinux": shell("getenforce"),
        "сетевой ADB": shell("getprop service.adb.tcp.port"),
    }
    findings = []
    if props["ro.adb.secure"] == "0":
        findings.append(("КРИТИЧНО", "ADB работает без проверки ключа авторизации.", "Отключить незащищённый ADB в конфигурации системы и проверить доступ только с доверенного USB/ключа."))
    if props["сетевой ADB"] not in ("", "-1", "0"):
        findings.append(("ВЫСОКИЙ", "Включён ADB через TCP.", "Отключить сетевой ADB вне контролируемой отладки; проверить, что порт не доступен из пользовательской сети."))
    if props["ro.debuggable"] == "1":
        findings.append(("ВЫСОКИЙ", "Система собрана с ro.debuggable=1.", "Для повседневной эксплуатации использовать production-конфигурацию либо ограничить устройство доверенной тестовой сетью."))
    if props["SELinux"].lower() == "permissive":
        findings.append(("ВЫСОКИЙ", "SELinux работает в permissive.", "Проверить отказы и устранить причины, затем включить enforcing и повторить функциональную проверку."))
    root = shell("command -v su")
    if root:
        findings.append(("ПРОВЕРИТЬ", "В системе обнаружена команда su.", "Проверить владельца, права и доступность su приложениям; наличие файла само по себе не доказывает доступ root."))
    return props, findings


def traffic_capture(interface, seconds):
    """Capture briefly, summarize protocol indicators, and always delete the PCAP."""
    fd, path = tempfile.mkstemp(prefix="h96-traffic-", suffix=".pcap")
    os.close(fd)
    try:
        cmd = ["tcpdump", "-i", interface, "-nn", "-s", "256", "-U", "-w", path]
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(seconds)
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
        read = subprocess.run(["tcpdump", "-nn", "-r", path], text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                              timeout=20, check=False)
        lines = read.stdout.splitlines()
        ports = {"HTTP/80": 0, "DNS/53": 0, "TLS/443": 0, "other": 0}
        for line in lines:
            if ".80:" in line or "> 80" in line:
                ports["HTTP/80"] += 1
            elif ".53:" in line or "> 53" in line:
                ports["DNS/53"] += 1
            elif ".443:" in line or "> 443" in line:
                ports["TLS/443"] += 1
            else:
                ports["other"] += 1
        payload = subprocess.run(["tcpdump", "-A", "-nn", "-r", path], text=True,
                                 stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                 timeout=20, check=False).stdout
        markers = sum(bool(re.search(pattern, payload, re.I)) for pattern in
                      (r"authorization:\s*(?:basic|bearer)", r"cookie:",
                       r"set-cookie:", r"(?:password|passwd|psk|token)\s*[:=]"))
        return len(lines), ports, markers
    finally:
        try:
            os.unlink(path)
        except FileNotFoundError:
            pass


def main():
    ap = argparse.ArgumentParser(description="Снять краткий read-only отчёт о состоянии приставки")
    ap.add_argument("--serial", help="серийный номер ADB-устройства (не включается в отчёт)")
    ap.add_argument("--capture-interface", help="интерфейс компьютера для захвата (например, wlan0)")
    ap.add_argument("--controlled-network", action="store_true", help="подтвердить, что приставка и сеть находятся в контролируемом тестовом сегменте")
    ap.add_argument("--capture-seconds", type=int, default=30, help="длительность захвата, 5–120 секунд (по умолчанию 30)")
    ns = ap.parse_args()
    adb_args = ["-s", ns.serial] if ns.serial else []
    global adb
    original_adb = adb
    def selected(args, timeout=15):
        return original_adb([*adb_args, *args], timeout)
    adb = selected

    devices = [x.split()[0] for x in adb([*adb_args, "devices"]).splitlines()[1:] if "\tdevice" in x]
    if not devices:
        print("Приставка не найдена через ADB. Проверьте USB, отладку и `adb devices`.")
        return 2
    if not ns.serial and len(devices) > 1:
        print(f"Найдено устройств: {len(devices)}. Укажите --serial (значение не попадёт в отчёт).")
        return 2
    if ns.capture_interface and (not ns.controlled_network or not 5 <= ns.capture_seconds <= 120):
        print("Для захвата укажите --controlled-network и длительность от 5 до 120 секунд.")
        return 2

    findings = []
    model = getprop("ro.product.model") or "не определена"
    release = getprop("ro.build.version.release") or "неизвестна"
    build = getprop("ro.tvbox.release") or getprop("ro.build.display.id") or "неизвестна"
    uptime = shell("cat /proc/uptime").split()
    mem = parse_meminfo(shell("cat /proc/meminfo"))
    load = shell("cat /proc/loadavg").split()
    temps = []
    for f in ("/sys/class/thermal/thermal_zone0/temp", "/sys/class/thermal/thermal_zone1/temp"):
        v = shell(f"cat {f}")
        if v.isdigit():
            n = int(v)
            temps.append(n / 1000 if n > 1000 else float(n))
    disk = shell("df -h /data /system /vendor 2>/dev/null")
    errors = shell("logcat -d -t 1200 -v brief *:E", timeout=25)
    error_lines = [safe_value(x) for x in errors.splitlines() if x.strip() and not re.search(r"(?i)password|passwd|\bpsk\b|ssid|token=", x)]

    if not uptime:
        findings.append(("КРИТИЧНО", "Не удалось прочитать время работы устройства; ADB-команды возвращают неполные данные. Переподключите ADB и повторите сбор.", "Сначала проверьте `adb devices`, затем USB/сеть отладки."))
    else:
        seconds = int(float(uptime[0]))
        if seconds < 300:
            findings.append(("ВНИМАНИЕ", f"Устройство работает только {seconds} с: возможна недавняя перезагрузка.", "Сопоставьте время с последним включением; при повторении проверьте питание и ошибки загрузки."))
    if mem:
        total, avail = mem
        percent = 100 * (total - avail) / total
        if percent >= 90:
            findings.append(("ВЫСОКИЙ", f"Доступно {avail // 1024} МиБ из {total // 1024} МиБ памяти ({percent:.0f}% занято).", "Найдите приложение с высоким потреблением памяти; снимите `dumpsys meminfo` для конкретного процесса перед изменениями."))
    if temps and max(temps) >= 80:
        findings.append(("ВЫСОКИЙ", f"Датчик температуры показывает до {max(temps):.1f} °C.", "Проверьте вентиляцию и охлаждение; повторите измерение после нагрузки и без неё."))
    if disk and any(int(p.group(1)) >= 90 for line in disk.splitlines()
                    if (p := re.search(r"\s(\d+)%\s", line))):
        findings.append(("ВЫСОКИЙ", "Один из проверенных разделов почти заполнен.", "Проверьте размер пользовательских данных и кэш; не удаляйте данные без просмотра списка и подтверждения."))
    if error_lines:
        findings.append(("ПРОВЕРИТЬ", f"В выборке logcat найдено строк с уровнем Error: {len(error_lines)}.", "Просмотрите очищенные примеры ниже и проверьте повторяемость ошибки и затронутое приложение."))
    posture, security_findings = security_posture()
    findings.extend(security_findings)
    if not findings:
        findings.append(("НОРМА", "По доступным базовым показателям явных проблем не обнаружено.", "Для проверки IPTV, Wi‑Fi, USB-пульта и воспроизведения запустите соответствующие функции и повторите отчёт."))

    print("Отчёт мониторинга H96 Max (только чтение)")
    print(f"Время UTC: {datetime.now(timezone.utc).isoformat(timespec='seconds')}")
    print(f"Модель: {safe_value(model)} | Android: {safe_value(release)} | Сборка: {safe_value(build)}")
    print(f"Uptime: {uptime[0]} с" if uptime else "Uptime: недоступно")
    print(f"Load average: {' '.join(load[:3])}" if load else "Load average: недоступно")
    if mem:
        print(f"Память: {mem[1] // 1024} МиБ свободно из {mem[0] // 1024} МиБ")
    else:
        print("Память: недоступно")
    print(f"Температуры: {', '.join(f'{x:.1f} °C' for x in temps) if temps else 'недоступно'}")
    print("Разделы:")
    print("\n".join("  " + safe_value(x) for x in disk.splitlines()) if disk else "  недоступно")
    print("\nОценка и рекомендации:")
    for level, evidence, advice in findings:
        print(f"- [{level}] {evidence}\n  Рекомендация: {advice}")
    if error_lines:
        print("\nПримеры очищенных ошибок (не более 8):")
        for line in error_lines[-8:]:
            print("  " + line)
    print("\nПроверка безопасности Android:")
    for name, value in posture.items():
        print(f"  {name}: {safe_value(value) if value else 'не задано/недоступно'}")
    if ns.capture_interface:
        print(f"\nСетевой захват: интерфейс {safe_value(ns.capture_interface)}, {ns.capture_seconds} с")
        try:
            count, ports, markers = traffic_capture(ns.capture_interface, ns.capture_seconds)
            print(f"  Пакетов: {count}; HTTP/80: {ports['HTTP/80']}; DNS/53: {ports['DNS/53']}; TLS/443: {ports['TLS/443']}; прочие: {ports['other']}")
            if ports["HTTP/80"]:
                print("  [ВНИМАНИЕ] Есть трафик на HTTP/80: проверьте, содержит ли он чувствительные данные и ожидаем ли этот узел.")
            else:
                print("  HTTP/80 в захвате не обнаружен; это не исключает другие незашифрованные протоколы или утечки.")
            if markers:
                print(f"  [ВЫСОКИЙ] Найдены шаблоны чувствительных HTTP-заголовков/полей ({markers}); значения не отображаются. Проверьте приложения и узел назначения.")
            else:
                print("  Известные шаблоны Authorization/Cookie/password/token в видимой части пакетов не обнаружены.")
            print("  PCAP удалён после подсчёта. Сводка не определяет назначение узлов; проверьте ожидаемость соединений в контролируемой сети.")
        except (FileNotFoundError, subprocess.SubprocessError, OSError):
            print("  Захват не выполнен. Проверьте tcpdump, имя интерфейса и права; временный PCAP удалён.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except FileNotFoundError:
        print("Не найден adb. Установите Android platform-tools и добавьте adb в PATH.")
        sys.exit(2)
    except subprocess.TimeoutExpired:
        print("ADB-команда не ответила вовремя. Проверьте доступность приставки и повторите сбор.")
        sys.exit(2)
