# Выбор базы прошивки (2026-10-23)

## Факты с железа (сток A9, adb root)
- DTB живого Android: `stock/fdt.dtb` → `stock/stock.dts`.
- Передний дисплей: `fd628_dev`, clk = GPIO2_C6 (`<gpio2 0x16>`), dat = GPIO2_C5 (`<gpio2 0x15>`), числа в hex.
  Демон `/system/bin/vfdsrv` раз в секунду делает `write(/dev/fd628_dev, 5 байт)`: 4 разряда в
  7-сегментной кодировке (0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,0x7f,0x6f) и байт двоеточия.
  `ioctl 0x80045402` читает режим 12/24 ч. «FUCK» = `71 3e 39 75 00`.
- Светодиод: gpio-leds `power-red` = GPIO2_C7 (`0x17`).
- ИК: `rockchip,remotectl-pwm`, NEC usercode 0x4040/0xff00/0x1dcc/0xfe01/0x7f80.
- Wi-Fi/BT: bcmdhd, `fw_bcm4334b1_ag.bin`, `bcm4334.hcd` в /vendor/etc/firmware.
- u-boot на eMMC (2017.09, sha256 a2da5047…) содержит `rkimg_bootdev`: `if mmc dev 1 && rkimgtest mmc 1 → Boot from SDcard`.
  Значит, SD с образом Rockchip имеет приоритет над eMMC.

## Доступные образы
| Образ | Android | Ядро | Где взять | Статус |
|---|---|---|---|---|
| сток (на eMMC) | 9, user, патч 2019 | 4.4.159 | бэкап eMMC | работает всё |
| RK3318_userdebug_H96_Max_V11_11_20211029.1055 | 11 | ? | yadi.sk/d/ThkDwMEs3kd8pg | 4PDA: «заводит Wi-Fi на синей плате» |
| RK3318_userdebug_H96_Max_RK3318_11_20230327 | 11 | ? | Google Drive (папка пустая) | недоступен |
| RK3318_userdebug_H96_Max_V12_12_20221117.1447 | 12, userdebug, патч 2022-06-05 | 4.19.232 | mega.nz (ссылка из описания видео на Rutube) | скачан: dl/fw, sha256 в a12.sha256 |
| AOSP/LineageOS для rk3328 | — | — | не найдено | — |

## A12 V12 против нашей платы (DTB из resource boot.img)
- Передний дисплей: `fddis_dev` с теми же clk 0x16 / dat 0x15 на gpio2. Драйвер в ядре переименован в FDDIS, VfdService.apk и ChangeLed на месте.
- Светодиод power-red на gpio2 0x17, как в стоке. GPIO BT те же (power 0x15, wake 0x1a, rts 0x0a).
- ИК: те же 5 usercode плюс 0xfd01 и 0xef10.
- Wi-Fi: `wifi_chip_type="rtl8822bs"` в DT (Rockchip определяет чип по SDIO ID), `vendor_dlkm/lib/modules/bcmdhd.ko`,
  `fw_bcm4334b1_ag.bin`, `nvram_4334.txt` (ccode=ALL), `bcm4334b0.hcd`. Бинарники отличаются от стоковых A9,
  при проблемах подложить стоковые.
- Google: GmsCore, PhoneskyTv, GSF, katniss, YouTube, WebViewGoogle/Trichrome, SkySetupWizard, chrome_hcy
  лежат в product/system_ext/system app-каталогах и удаляются офлайн.
- Риск SD-загрузки: first-stage fstab использует `/dev/block/by-name/*` (подробности ниже).

## Добавлено после проверки
- В cmdline boot.img A12 зашито `androidboot.boot_devices=ff520000.dwmmc` (eMMC). На SD-образе обязательно
  заменить на `ff500000.dwmmc`, иначе by-name укажет на eMMC, а userdata/metadata помечены formattable.
- В fstab `/devices/platform/ff500000.dwmmc/mmc_host* voldmanaged=sdcard1`: на SD-образе убрать.
- В vendor `init.rk3328.rc` и `init.tune_io.rc` жёстко прописаны `mmcblk2` и `mmcblk2p4` (baseparameter eMMC):
  на SD-образе перенаправить. Бинарники HAL baseparameter проверить отдельно.
- vbmeta A12: flags=2 (проверка выключена), boot.img можно править.
- A11 V11 20211029: ядро 4.19.193, патч 2021-06-05, те же fddis/LED/BT, bcm4334 есть. Отличий от A12 в нашу пользу нет.
- apex в A12 — модули `com.google.android.*` (ART, media, permission, statsd…). Это код AOSP с подписью Google,
  телеметрии GMS в них нет, без замены не вырезаются. Их оставляем и объясняем в критерии `pm list | grep google`.
- F-Droid: FLauncher в основном репозитории нет (заброшен). Живые TV-лаунчеры: TV Launcher (com.tvlauncher, 2026-10),
  Couchy, Luncher. Клиенты: F-Droid 2.0.2, Droid-ify 0.7.8, Neo Store 1.3.2. TorrServe MatriX FOSS (ru.yourok.torrserve)
  есть в F-Droid; есть Kodi 21.3, RetroArch 1.22.2, LibreTorrent.
