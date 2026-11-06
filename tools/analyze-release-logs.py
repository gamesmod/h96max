#!/usr/bin/env python3
"""Классифицировать журналы сборки/устройства и сохранить безопасную сводку."""
import argparse
import pathlib
import re
import sys


CRITICAL = re.compile(
    r"FATAL EXCEPTION|ForegroundServiceDidNotStartInTimeException:|Traceback \(most recent call last\)|"
    r"fatal error:|segmentation fault|ANR in |BUILD FAILED|Installation aborted|Unhandled exception",
    re.I,
)
ERROR = re.compile(
    r"\bE/[\w.]+|\sE\s[\w.-]+\s*:|\bERROR:|\[ERROR(?:\]|:)|\berror:|"
    r"init\s*:\s*Command .* (?:failed|error):|\bFAIL(?:ED)? \((?:errors|failures)=\d+\)", re.I)
WARNING = re.compile(r"\bW/[\w.]+|\sW\s[\w.-]+\s*:|\bWARN(?:ING)?\b|\bwarning:", re.I)
URL = re.compile(r"https?://[^\s\]>)\"']+", re.I)
SECRET = re.compile(r"(?i)\b(password|passwd|token|api[_-]?key|authorization|secret)=([^&\s,;]+)")
IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")


def sanitize(line):
    """Удалить из примеров URL, адреса и распространённые значения секретов."""
    line = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", line)
    line = URL.sub("[URL скрыт]", line)
    line = SECRET.sub(r"\1=[СКРЫТО]", line)
    return IPV4.sub("[IP скрыт]", line).strip()


def analyze(lines):
    grouped = {}
    findings = []
    counts = {"critical": 0, "error": 0, "warning": 0}
    for number, line in enumerate(lines, 1):
        if CRITICAL.search(line):
            level = "Критично"
            counts["critical"] += 1
        elif ERROR.search(line):
            level = "Ошибка"
            counts["error"] += 1
        elif WARNING.search(line):
            level = "Предупреждение"
            counts["warning"] += 1
        else:
            continue
        snippet = sanitize(line)[:300]
        fingerprint = re.sub(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+", "", snippet)
        key = (level, fingerprint)
        if key not in grouped:
            grouped[key] = [number, 0, snippet]
        grouped[key][1] += 1
    for (level, _), (number, occurrences, snippet) in grouped.items():
        findings.append((level, number, occurrences, snippet))
    return counts, findings


def main():
    parser = argparse.ArgumentParser(description="Сводный разбор журнала без публикации сырых строк и URL.")
    parser.add_argument("log", help="путь к журналу")
    parser.add_argument("--output", required=True, help="путь к Markdown-отчёту")
    parser.add_argument("--source", default="журнал", help="название источника журнала")
    parser.add_argument("--fail-on-critical", action="store_true", help="вернуть ошибку при критических строках")
    parser.add_argument("--fail-on-errors", action="store_true", help="вернуть ошибку при ошибках или критических строках")
    args = parser.parse_args()

    path = pathlib.Path(args.log)
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    counts, findings = analyze(lines)
    out = pathlib.Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as report:
        report.write(f"# Анализ журналов: {args.source}\n\n")
        report.write(f"Строк просмотрено: {len(lines)}. Критично: {counts['critical']}; "
                     f"ошибок: {counts['error']}; предупреждений: {counts['warning']}.\n\n")
        if not findings:
            report.write("Совпадений с критическими ошибками, ошибками или предупреждениями не найдено.\n")
        else:
            report.write("Сырые журналы не включены. URL, IPv4 и распространённые значения секретов скрыты.\n\n")
            report.write("| Уровень | Строка | Повторов | Фрагмент |\n|---|---:|---:|---|\n")
            for level, number, occurrences, snippet in findings[:200]:
                report.write(f"| {level} | {number} | {occurrences} | `{snippet.replace('|', '&#124;')}` |\n")
            if len(findings) > 200:
                report.write(f"\nПоказаны первые 200 из {len(findings)} совпадений.\n")
        report.write("\nТребуется ручная проверка каждого предупреждения и ошибки перед выпуском.\n")

    print(f"Анализ {args.source}: строк {len(lines)}; критично {counts['critical']}; "
          f"ошибок {counts['error']}; предупреждений {counts['warning']}; отчёт {out}")
    if args.fail_on_errors and (counts["critical"] or counts["error"]):
        return 1
    return 1 if args.fail_on_critical and counts["critical"] else 0


if __name__ == "__main__":
    sys.exit(main())
