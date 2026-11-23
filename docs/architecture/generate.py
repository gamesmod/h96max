#!/usr/bin/env python3
"""Generate Panadonik architecture .drawio (stdlib only)."""
from __future__ import annotations

import html
import uuid
from pathlib import Path

OUT = Path(__file__).resolve().with_name("panadonik-architecture.drawio")

P, PS = "#dae8fc", "#6c8ebf"
S, SS = "#d5e8d4", "#82b366"
W, WS = "#fff2cc", "#d6b656"
A, AS = "#ffe6cc", "#d79b00"
N, NS = "#f5f5f5", "#666666"
SEC, SECS = "#e1d5e7", "#9673a6"

FONT = "fontFamily=Helvetica;fontSize=12;"
TITLE = "fontFamily=Helvetica;fontSize=14;fontStyle=1;"
EDGE = (
    "edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;"
    "html=1;endArrow=classic;endFill=1;strokeColor=#666666;"
)


def esc(s: str) -> str:
    return html.escape(s, quote=True).replace("\n", "&#xa;")


class Diagram:
    def __init__(self, name: str):
        self.name = name
        self.id = str(uuid.uuid4())
        self.cells: list[str] = []
        self._n = 2

    def nid(self) -> str:
        i = self._n
        self._n += 1
        return str(i)

    def vertex(
        self,
        value: str,
        x: float,
        y: float,
        w: float,
        h: float,
        style: str,
        parent: str = "1",
        cid: str | None = None,
    ) -> str:
        cid = cid or self.nid()
        self.cells.append(
            f'<mxCell id="{cid}" value="{esc(value)}" style="{style}" '
            f'vertex="1" parent="{parent}">\n'
            f'  <mxGeometry x="{x}" y="{y}" width="{w}" height="{h}" as="geometry"/>\n'
            f"</mxCell>"
        )
        return cid

    def edge(
        self,
        source: str,
        target: str,
        label: str = "",
        style: str = EDGE,
        exit_entry: str = "",
    ) -> str:
        cid = self.nid()
        self.cells.append(
            f'<mxCell id="{cid}" value="{esc(label)}" style="{style}{exit_entry}" '
            f'edge="1" parent="1" source="{source}" target="{target}">\n'
            f'  <mxGeometry relative="1" as="geometry"/>\n'
            f"</mxCell>"
        )
        return cid

    def box(
        self,
        value,
        x,
        y,
        w,
        h,
        fill=P,
        stroke=PS,
        *,
        parent="1",
        extra="",
        cid=None,
    ):
        style = (
            f"rounded=1;whiteSpace=wrap;html=1;convertToSvg=1;"
            f"fillColor={fill};strokeColor={stroke};{FONT}{extra}"
        )
        return self.vertex(value, x, y, w, h, style, parent, cid)

    def ext(self, value, x, y, w, h, *, parent="1", cid=None):
        return self.box(value, x, y, w, h, N, NS, parent=parent, extra="dashed=1;", cid=cid)

    def warn(self, value, x, y, w, h, *, parent="1", cid=None):
        return self.box(value, x, y, w, h, W, WS, parent=parent, cid=cid)

    def ok(self, value, x, y, w, h, *, parent="1", cid=None):
        return self.box(value, x, y, w, h, S, SS, parent=parent, cid=cid)

    def accent(self, value, x, y, w, h, *, parent="1", cid=None):
        return self.box(value, x, y, w, h, A, AS, parent=parent, cid=cid)

    def sec(self, value, x, y, w, h, *, parent="1", cid=None):
        return self.box(value, x, y, w, h, SEC, SECS, parent=parent, cid=cid)

    def title(self, value, x, y, w=1100, h=36):
        style = (
            f"text;html=1;strokeColor=none;fillColor=none;align=left;"
            f"verticalAlign=middle;whiteSpace=wrap;convertToSvg=1;{TITLE}"
        )
        return self.vertex(value, x, y, w, h, style)

    def note(self, value, x, y, w, h, *, parent="1"):
        style = (
            f"text;html=1;strokeColor=none;fillColor=none;align=left;"
            f"verticalAlign=top;whiteSpace=wrap;fontColor=#666666;"
            f"convertToSvg=1;{FONT}"
        )
        return self.vertex(value, x, y, w, h, style, parent)

    def swim(self, value, x, y, w, h, fill=P, stroke=PS, cid=None):
        style = (
            f"swimlane;startSize=28;html=1;whiteSpace=wrap;convertToSvg=1;"
            f"fillColor={fill};strokeColor={stroke};{TITLE}"
        )
        return self.vertex(value, x, y, w, h, style, "1", cid)

    def rhomb(self, value, x, y, w=150, h=90):
        style = (
            f"rhombus;whiteSpace=wrap;html=1;convertToSvg=1;"
            f"fillColor={W};strokeColor={WS};{FONT}"
        )
        return self.vertex(value, x, y, w, h, style)

    def xml(self) -> str:
        body = "\n".join(self.cells)
        return (
            f'<diagram id="{self.id}" name="{esc(self.name)}">\n'
            '<mxGraphModel dx="1400" dy="900" grid="1" gridSize="10" '
            'guides="1" tooltips="1" connect="1" arrows="1" fold="1" '
            'page="1" pageScale="1" pageWidth="1600" pageHeight="1100" '
            'math="0" shadow="0">\n'
            "<root>\n"
            '<mxCell id="0"/>\n'
            '<mxCell id="1" parent="0"/>\n'
            f"{body}\n"
            "</root>\n"
            "</mxGraphModel>\n"
            "</diagram>"
        )


def page_architecture() -> Diagram:
    d = Diagram("1. Архитектура решения")
    d.title("Panadonik — архитектура решения (H96 Max / RK3318·RK3328)", 40, 16, 1200, 32)

    hw = d.swim("Аппаратная платформа", 40, 60, 1520, 160, A, AS)
    d.box(
        "H96 Max\nсиняя ревизия\nSoC Rockchip\nA53 ×4 + Mali-450",
        20, 40, 200, 100, A, AS, parent=hw,
    )
    d.ok("eMMC ~32 ГБ\nОЗУ ~4 ГиБ", 240, 40, 150, 100, parent=hw)
    d.box("HDMI 1080p\nVFD-дисплей", 410, 40, 140, 100, P, PS, parent=hw)
    d.box("USB-пульт\nрадиоадаптер\nмикрофон", 570, 40, 160, 100, P, PS, parent=hw)
    d.sec(
        "Wi‑Fi / BT\nBCM4334 (SDIO+UART)\nfirmware из стоковой A9",
        750, 40, 220, 100, parent=hw,
    )
    d.ext(
        "Сценарии подключения\n• ТВ: HDMI + Wi‑Fi\n• ПК: USB питание+ADB",
        990, 40, 230, 100, parent=hw,
    )
    d.note(
        "Маркетингово RK3318; живой A12 BSP: rk3328_box.\nБаза: Android 12 userdebug, Linux 4.19.232.",
        1240, 45, 260, 80, parent=hw,
    )

    img = d.swim("Разделы образа Android 12 (super / GPT)", 40, 240, 760, 260, P, PS)
    d.accent("boot / recovery\nuboot / trust / dtbo", 20, 40, 220, 70, parent=img)
    d.box("system\nframework, priv-app\ntvboxd.jar, TorrServer", 260, 40, 230, 70, P, PS, parent=img)
    d.box("vendor\ntvbox.rc + скрипты\noverlay Wi‑Fi RRO", 510, 40, 230, 70, P, PS, parent=img)
    d.ok("product\ntvbox/apk (сторонние)\nWebView LineageOS", 20, 130, 250, 90, parent=img)
    d.warn(
        "Сборка: удаление GMS/телеметрии\nOEM OTA / datadis / DRM-UDP\n+ подпись tvbox / ota-ключами",
        290, 130, 280, 90, parent=img,
    )
    d.sec("Ключи secrets/\n(не в публичном Git)\nAPK + OTA", 590, 130, 150, 90, parent=img)

    rt = d.swim("Службы и рантайм на приставке", 820, 240, 740, 260, S, SS)
    d.box("tvbox-firstboot.sh\nплитки лаунчера, APK", 20, 40, 210, 70, S, SS, parent=rt)
    d.box("tvboxd\nroot helper\nOTA / eMMC / FS", 250, 40, 180, 70, S, SS, parent=rt)
    d.box("TorrServer :8090\n/system/bin/torrserver", 450, 40, 210, 70, S, SS, parent=rt)
    d.box("bootscreen / bootlog\nсводка загрузки", 20, 130, 210, 90, S, SS, parent=rt)
    d.accent("Обновление\norg.tvbox.installer\n→ GitHub Releases", 250, 130, 210, 90, parent=rt)
    d.ext("Obtainium\nобновления\nсторонних APK", 480, 130, 180, 90, parent=rt)

    apps = d.swim("Домашний экран (TV Launcher) и приложения", 40, 520, 1000, 230, SEC, SECS)
    for label, x, w in [
        ("ТВ-каналы\norg.tvbox.iptv", 20, 130),
        ("TorrServe", 165, 110),
        ("Файлы\nFileExploresky", 290, 130),
        ("Ссылки\nQR → TorrServer", 435, 140),
        ("Обновление", 590, 110),
        ("Настройки", 715, 110),
        ("Производительность", 840, 140),
    ]:
        d.box(label, x, 45, w, 70, SEC, SECS, parent=apps)
    d.box("Антивирус + Hypatia", 20, 135, 170, 60, SEC, SECS, parent=apps)
    d.box("Питание", 210, 135, 110, 60, SEC, SECS, parent=apps)
    d.ext("VLC (зависимость IPTV/торрент, без плитки)", 340, 135, 300, 60, parent=apps)
    d.note(
        "Порядок плиток задаёт firstboot; пользователь может менять.",
        660, 145, 310, 45, parent=apps,
    )

    ext = d.swim("Внешние источники и потребители", 1060, 520, 500, 230, N, NS)
    d.ext("GitHub Releases\nOTA + clean-install + APK", 20, 40, 220, 70, parent=ext)
    d.ext("IPTV-источники\n≥1080p, русские категории", 260, 40, 210, 70, parent=ext)
    d.ext("Телефон\nQR «Ссылки»\n→ TorrServer → VLC на ТВ", 20, 130, 220, 70, parent=ext)
    d.ext("F-Droid / GitHub\nсторонние APK", 260, 130, 210, 70, parent=ext)

    d.note(
        "Документация: README.md · COMPONENTS.md · docs/install-from-stock.md · AGENTS.md",
        40, 770, 1000, 28,
    )
    return d


def page_build() -> Diagram:
    d = Diagram("2. Подготовка образа")
    d.title("Подготовка образа eMMC и артефактов релиза", 40, 16, 1100, 32)
    d.note(
        "Единая команда: tools/build-release.sh  ·  версия = ro.tvbox.release в build-sd.sh",
        40, 52, 1000, 24,
    )

    inp = d.swim("Входы (секреты и сырой сток не публикуются)", 40, 90, 1520, 130, N, NS)
    d.ext("База A12\ndl/a12 (Git LFS)\nили update.img", 20, 40, 200, 70, parent=inp)
    d.ext("A9 Wi‑Fi/BT\nfirmware BCM4334", 240, 40, 170, 70, parent=inp)
    d.sec("secrets/\ntvbox + ota\nkey/cert", 430, 40, 150, 70, parent=inp)
    d.box("apps/*/\nисходники Panadonik", 600, 40, 180, 70, P, PS, parent=inp)
    d.ext("Сторонние APK\nthird-party-apps.json", 800, 40, 200, 70, parent=inp)
    d.warn("release/notes/ВЕР.txt\nchangelog", 1020, 40, 190, 70, parent=inp)
    d.ext("IPTV sources\niptv-merge + verify", 1230, 40, 180, 70, parent=inp)

    y = 250
    s1 = d.ok("1. prepare-build-inputs\nextract / LFS check", 40, y, 200, 70)
    s2 = d.ok("2. setup-sdk\nJDK21 + android-23", 270, y, 180, 70)
    s3 = d.box("3. apps/*/build.sh\nAPK + tvboxd.jar\nподпись tvbox-key", 480, y, 210, 70)
    s4 = d.box("4. ota/tvbox-flash\nstatic aarch64", 720, y, 170, 70)
    s5 = d.accent("5. MEDIA=emmc\n./build-sd.sh\nsuper + regions", 920, y, 200, 70)
    s6 = d.accent("6. release-ota\n+ clean-install.zip\nподпись ota-key", 1150, y, 230, 70)
    for a, b in ((s1, s2), (s2, s3), (s3, s4), (s4, s5), (s5, s6)):
        d.edge(a, b)

    det = d.swim("Что делает build-sd.sh (eMMC)", 40, 350, 900, 210, P, PS)
    d.box("Правки boot/recovery\ncmdline, fstab, dtbo", 20, 40, 200, 70, parent=det)
    d.warn("Удаление OEM-мусора\nGMS, datadis, stress…", 240, 40, 200, 70, parent=det)
    d.box("Установка своих APK\noverlay vendor/system", 460, 40, 200, 70, parent=det)
    d.sec("Wi‑Fi без credentials\nRRO WPA2, pmf=0", 680, 40, 190, 70, parent=det)
    d.ok("Сборка super / GPT\nmanifest + MD5 регионов", 20, 130, 250, 55, parent=det)
    d.box("bootanimation + IPTV m3u", 290, 130, 220, 55, parent=det)
    d.note("SSID/ключи Wi‑Fi в публичный образ не вшиваются.", 530, 140, 340, 40, parent=det)

    out = d.swim("Выходы и проверки кандидата", 960, 350, 600, 210, S, SS)
    d.ok("release/ВЕР/emmc/\nregions + SHA256SUMS", 20, 40, 220, 70, parent=out)
    d.ok("tvbox-update-ВЕР-emmc.zip\nподписанный OTA", 260, 40, 220, 70, parent=out)
    d.ok("Panadonik-ВЕР-\nemmc-clean-install.zip", 20, 125, 240, 55, parent=out)
    d.box("Obtainium APK ×7", 280, 125, 160, 55, parent=out)

    ci = d.swim("CI и продвижение (GitHub Actions)", 40, 590, 1520, 200, A, AS)
    c1 = d.warn("candidate/* / schedule\nартефакт 3 дня\nне Release", 20, 45, 200, 90, parent=ci)
    c2 = d.box("Автотесты + IPTV\n+ wifi-secrets scan\n+ подпись OTA", 240, 45, 210, 90, parent=ci)
    c3 = d.accent("Аппаратная приёмка\ndocs/release-qa/ВЕР\nна живой приставке", 470, 45, 220, 90, parent=ci)
    c4 = d.vertex(
        "Подтверждение\nпользователя?",
        720,
        40,
        150,
        100,
        f"rhombus;whiteSpace=wrap;html=1;convertToSvg=1;fillColor={W};strokeColor={WS};{FONT}",
        ci,
    )
    c5 = d.ok("Тег dev-vВЕР\n→ prerelease", 900, 50, 180, 80, parent=ci)
    c6 = d.ok("Тег vВЕР\n→ stable Release\nsecurity_review=pass", 1110, 50, 240, 80, parent=ci)
    d.edge(c1, c2)
    d.edge(c2, c3)
    d.edge(c3, c4)
    d.edge(c4, c5, "dev")
    d.edge(c4, c6, "stable")

    d.note(
        "Публикационный тег не отправлять до явного подтверждения. Ночной кандидат ≠ выпуск.",
        40, 820, 1000, 28,
    )
    return d


def page_flash() -> Diagram:
    d = Diagram("3. Загрузка и обновление")
    d.title("Загрузка приставки: чистая установка и OTA", 40, 16, 1100, 32)

    d.accent("Путь A — чистая установка со стока / с нуля", 40, 60, 720, 40)
    d.box("Путь B — обновление уже установленной Panadonik", 800, 60, 720, 40)

    a1 = d.ext("Скачать clean-install.zip\n+ .sha256 + .sig + ota-cert", 40, 120, 260, 70)
    a2 = d.ok("Проверить SHA-256\nи openssl dgst -verify", 330, 120, 220, 70)
    a3 = d.warn("Выключить приставку\nUSB-A↔A → OTG\nрежим Rockchip Loader", 580, 120, 240, 70)
    a4 = d.accent("sudo python3 -I tools/rkusb.py\nflash release/ВЕР/emmc", 850, 120, 270, 70)
    a5 = d.ok("Перезапуск →\nпервая загрузка A12", 1150, 120, 180, 70)
    for x, y in ((a1, a2), (a2, a3), (a3, a4), (a4, a5)):
        d.edge(x, y)

    d.note(
        "Loader USB 2207:320c. Обратная побайтная сверка выше 32 МиБ в Loader ненадёжна — сохранять лог записи.",
        40, 205, 1000, 28,
    )

    b1 = d.ext("GitHub Release\ntvbox-update-ВЕР-emmc.zip", 800, 250, 240, 70)
    b2 = d.box("Плитка «Обновление»\norg.tvbox.installer\nили recovery SD/USB", 1080, 250, 240, 70)
    b3 = d.sec("Проверка подписи\nпрежним ota-ключом\n(otacerts.zip)", 800, 350, 240, 70)
    b4 = d.warn("По умолчанию: очистка userdata\nдва шага подтверждения в UI", 1080, 350, 240, 70)
    d.edge(b1, b2)
    d.edge(b2, b3)
    d.edge(b3, b4)

    fb = d.swim("После записи / OTA: первая загрузка", 40, 250, 720, 230, S, SS)
    d.box("Холодный старт\nдо рабочего стола", 20, 40, 180, 60, parent=fb)
    d.box("tvbox-firstboot\nAPK + плитки лаунчера", 220, 40, 210, 60, parent=fb)
    d.box("torrserver :8090\ntvboxd", 450, 40, 160, 60, parent=fb)
    d.accent("Управление с USB-пульта\n= критерий готовности", 20, 120, 240, 70, parent=fb)
    d.sec("Wi‑Fi: страна эксплуатации\nбез вшитых SSID/ключей", 280, 120, 240, 70, parent=fb)
    d.ext("Obtainium уже настроен\nна сторонние APK", 540, 120, 160, 70, parent=fb)

    conn = d.swim("Два обязательных сценария приёмки", 40, 510, 1520, 200, P, PS)
    d.box(
        "У телевизора\n• HDMI + только Wi‑Fi\n• автоподключение после OTA\n• пульт + Bluetooth отдельно",
        20, 40, 360, 130, parent=conn,
    )
    d.box(
        "У компьютера (без экрана)\n• один USB: питание + данные\n• ADB без диалога на экране\n• сохранить adbkey хоста",
        410, 40, 380, 130, parent=conn,
    )
    d.warn(
        "Sanity после установки\nIPTV ≥1080 · торрент QR\nPAGE ± канал · без swap/zRAM\nсетевой аудит (очищенный)",
        820, 40, 320, 130, parent=conn,
    )
    d.sec(
        "Блокеры выпуска\nсекреты в образе\nнет согласия на wipe\nнет аппаратного gate",
        1170, 40, 300, 130, parent=conn,
    )

    d.note(
        "Инструкция: docs/install-from-stock.md · OTA: apps/installer + recovery · Gate: docs/release-quality-gate.md",
        40, 730, 1200, 28,
    )
    return d


def main() -> None:
    pages = [page_architecture(), page_build(), page_flash()]
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<mxfile host="app.diagrams.net" agent="panadonik-architecture" '
        'version="26.0.0" type="device" pages="3">\n'
        + "\n".join(p.xml() for p in pages)
        + "\n</mxfile>\n"
    )
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(xml, encoding="utf-8")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
