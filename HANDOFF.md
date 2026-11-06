# HANDOFF — H96 Max RK3318: Android 12 без Google (состояние на 2026-10-27 11:20 MSK)

Документ для продолжения работы в новой сессии. Читать целиком перед любыми действиями с приставкой.
Подробности: `GOALS.md` (цели/статус), `BUILD-AND-STATE.md` (сборка/состав), `RELEASE-1.0/1.1` (+ `release/notes/1.2.txt`),
`security-audit.md`, `research-apps.md`. Исходники зеркалятся в приватный `gamesmod/garbage`, ветка `rk3318-tvbox`, папка `rk3318-tvbox/`.

---

## 1. Где мы сейчас (кратко)

| Что | Состояние |
|---|---|
| Приставка грузится | **с SD**, выпуск **1.0** + вживую доставлены: tvboxd (из `/data/local/tmp/tvboxd.jar`, запущен вручную!), «Установка», «Антивирус», «Ссылки», Hypatia, 13 плиток |
| Внутренняя память (eMMC) | **выпуск 1.1** (установлен пользователем через «Установку», все 16 регионов сверены). Стоковый A9 стёрт |
| Образ для eMMC на SD | `/data/vendor/tvbox/emmc-image` = **1.2 emmc** (sha256 сверены) — готов к установке |
| **Следующий шаг пользователя** | «Установка» → «Установить на внутреннюю память» (поставит 1.2 на eMMC) |
| Пакеты обновлений 1.2 | `release/updates/{emmc,sd}/tvbox-update-1.2-*.zip` + `latest.txt`, подписаны, проверены |
| Наблюдение | на ПК крутятся `monitor/run-metrics.sh` и `monitor/run-logcat.sh` (метрики каждые 30 с, журнал W+, тревоги → `monitor/alerts.log`) |
| Диск ПК | свободно ~36 ГБ (старый `~/rk3318-image` удалён пользователем) |

**Важно:** tvboxd на текущей SD (1.0) — тестовый, запущен из `/data/local/tmp`; после перезагрузки SD-1.0 его не будет
(перезапуск: `adb push apps/tvboxd/out/tvboxd.jar /data/local/tmp/tvboxd.jar` и скрипт ниже, §7).

---

## 2. Железо и доступ

- H96 Max, SoC RK3318 (платформа rk3328), **синяя плата**, 4×A53 1.1 ГГц, Mali-450, 4 ГБ ОЗУ, eMMC 32 ГБ (61071360 сект.), Wi‑Fi/BT BCM4334.
- Сеть: Wi‑Fi **10.12.54.40** (Ethernet .41 при кабеле). ПК (хост): **10.12.54.36**.
- adb по сети: `adb connect 10.12.54.40:5555`, root: `adb shell su 0 sh script.sh` (adbd и так root, `ro.adb.secure=0`).
- eMMC = `mmcblk2` (контроллер **ff520000**), SD = `mmcblk0` (**ff500000**). U-Boot грузит **SD в приоритете**, если карта вставлена.
- USB OTG = только host (нет USB-adb). Есть кабель USB A‑A (maskrom на крайний случай).
- Пульты: ИК (раскладка из A9, `overlay/vendor/usr/keylayout/ff1b0030_pwm.kl`) и пульт ТВ через HDMI-CEC — работают.
- ТВ выключается → приставка засыпает по CEC (adb/TorrServer живут). **Не будить без нужды** — CEC включит телевизор.

---

## 3. Проект на ПК

`~/rk3318-android`:

| Путь | Что |
|---|---|
| `build-sd.sh` | сборка образа: `./build-sd.sh` (SD → `build/out`), `MEDIA=emmc ./build-sd.sh` (→ `build/out-emmc`). **Сначала emmc, потом sd** (SD-образ упаковывает eMMC-вариант в раздел tvbox_emmc). Версия: строка `ro.tvbox.release=` в heredoc |
| `apps/*/` | свои приложения, у каждого `build.sh` (javac из `sdk/jdk` → dx → aapt → `tools/v1sign.py`); всё сразу — `tools/build-release.sh` |
| `apps/tvboxd` | root-служба (app_process), jar → `/system/framework/tvboxd.jar` |
| `ota/` | `update-binary` (sh), `tvbox-flash.c` (статический aarch64 помощник) |
| `tools/` | `mkota.py` (сборка+подпись OTA), `release-ota.sh`, `serve-updates.sh`, `sync-garbage.sh`, `lpbuild.py`, `lpunpack.py`, `bootimg.py`, `rkidb.py`, `v1sign.py`, `mkbootanim.py` |
| `overlay/` | файлы в vendor/system (tvbox.rc, firstboot, wipecheck, keylayout, update-urls) |
| `secrets/` | **не в git**: `wifi.env`, `tvbox-key.pem` (APK), **`ota-key.pem`/`ota-cert.pem` (подпись обновлений)** |
| `dl/` | вендорский A12 `dl/a12/Image`, APK (`dl/apk`), удалённое в `dl/apk-removed` |
| `release/1.x/{sd,emmc}` | выпущенные образы (регионы + manifest + SHA256SUMS); `release/updates/` — OTA |
| `backup/emmc-full/` | **полный бэкап стоковой eMMC** (59×512 МиБ gz, sha256, 6 ГБ, снят 2026-10-27 после загрузок стока) |
| `backup/stock-a9-early/` | **самый ранний** бэкап стока (2026-10-21, первые 3233 МиБ + хвост GPT, xz -9e, README) |
| `device/` | `sd-write.sh` (запись SD с самой приставки), `emmc-hash.sh`, `emmc-dump*.sh`, снимки сумм eMMC |
| `monitor/` | наблюдение (см. §1) |

Синхронизация в репозиторий: `tools/sync-garbage.sh "сообщение"` (rsync по белому списку, отказ при секретах, коммит по-русски, push ветки `rk3318-tvbox`). Клон: `~/garbage`, локальная git-идентичность gamesmod.

---

## 4. Состав выпуска 1.2 (последний собранный)

- База: вендорский A12 `RK3318_userdebug_H96_Max_V12_12_20221117.1447`, userdebug, SELinux permissive, без FBE.
- Удалено: GMS/Google, вендорский мусор, телеметрия (datadis, user_drmservice), **Kodi**, **Samba+Termux**, **glmark2**, **MediaCenter**, **SkySetupWizard**, «Все настройки», адрес вендорского OTA (`ota.hcybox.net`).
- Приложения: TV Launcher (дом), VLC (основной плеер), TorrServe + TorrServer :8090, SmartTube, Obtainium для обновления приложений, Hypatia (антивирус), LineageOS WebView, файловый менеджер.
- Свои плитки в текущей раскладке задаёт `tvbox-firstboot.sh`: ТВ-каналы (IPTV→VLC), TorrServe, SmartTube, Total Commander, Obtainium, **Ссылки** (QR), **Установка** (и обновление), Настройки, Производительность, Питание.
- Настройки: тёмная тема всегда, субтитры VLC выкл. по умолчанию (`subtitle_preferred_language=none`), громкость media=15 (HDMI бит-в-бит), VLC MANAGE_EXTERNAL_STORAGE разрешён, ru-RU, Europe/Moscow, имя panadonik.
- Экран живого лога загрузки вместо анимации (`apps/bootscreen`, bootanim.rc → root/su).
- **tvboxd** (init, root, абстрактный сокет `tvboxd`): STATUS, INSTALL/VERIFY/BACKUP/RESTORE eMMC, UPDATE_CHECK/UPDATE_INSTALL, URLS/URL_ADD/URL_DEL, AV_STATUS/AV_REALTIME/HYPATIA_UPDATE, LOG. Доступ: root/shell/«Установка» — всё; «Антивирус» — только AV_*/HYPATIA_UPDATE. Фоновых задач нет.
- **Разметка GPT исправлена**: разделы точного размера (раньше на 1 сектор короче — ломало бы OTA по имени раздела).
- SD-образ: доп. раздел **15 `tvbox_emmc`** (2 ГиБ, ext4 с eMMC-образом для установщика), userdata = раздел 16. eMMC-образ: userdata = 15, SD-слот = хранилище (voldmanaged).
- **Ключ обновлений**: recovery и система доверяют только `secrets/ota-cert.pem` (сток доверял публичному AOSP testkey!).

---

## 5. Установка и обновления — как устроено

**Установка на eMMC (только при загрузке с SD):** «Установка» → tvboxd INSTALL: проверка md5 образа (раздел tvbox_emmc или папка `/data/vendor/tvbox/emmc-image`) → force_ro=0 → запись регионов по смещениям → перечитка/сверка → force_ro=1. Предохранители: только при загрузке с SD, mmcblk2 обязан быть ff520000, eMMC не смонтирована.

**OTA (с SD или eMMC, пакет под свой носитель):**
1. Пакет `tvbox-update-ВЕР-{emmc|sd}.zip`: `update-binary`, `tvbox/{release,media,changelog,parts}.txt`, `tvbox/tvbox-flash`, `images/*.gz` (super, boot, dtbo, vbmeta, recovery). Подпись whole-file (как `signapk -w`) — `tools/mkota.py`.
2. Источники: файл в корне флешки/SD (`/mnt/media_rw/*`), адреса из `/data/vendor/tvbox/update-urls.txt` (иначе `/vendor/etc/tvbox/update-urls.txt` = `http://10.12.54.36:8099/`); на сервере `<база>/<emmc|sd>/latest.txt` (version, file, sha256, size, notes).
3. tvboxd: копия/загрузка в `/data/vendor/tvbox/ota/update.zip` → sha256 → `RecoverySystem.verifyPackage` → проверка media → BCB в misc **по смещениям 0 и 0x4000** (Rockchip) → `reboot,recovery`.
4. recovery проверяет подпись (наш otacerts) и запускает update-binary: проверка `boot_devices` в cmdline против media, преднастройка размеров всех разделов, запись каждого через `tvbox-flash` (запись + fsync + перечитка + md5).
5. «С очисткой»: tvboxd кладёт `wipe-requested`; update-binary **после успеха** пишет `TVBOX-WIPE` в misc @64 КиБ; при загрузке `tvbox-wipecheck.sh` (init `on fs`) обнуляет 16 МиБ userdata → init форматирует (formattable). Recovery Rockchip/AOSP не умеет «установить+очистить» за один проход.
6. После загрузки tvboxd удаляет установленный пакет.

**Выпуск обновления:** `MEDIA=emmc ./build-sd.sh && ./build-sd.sh` → `tools/release-ota.sh ВЕР release/notes/ВЕР.txt` → раздавать `tools/serve-updates.sh` (порт 8099) или класть zip на флешку.
**Интернет-публикация запрещена**, пока в super зашит `wifi.env` (пароль Wi‑Fi).

**Проверено:** подпись (Android verifier принимает наш, отвергает testkey и изменённый байт); update-binary целиком на файлах-«разделах» (сохранение / метка очистки / отказ при малом разделе без записи).
**НЕ проверено:** настоящая установка через recovery. План (советник): на eMMC 1.2 по очереди — пакет-пустышка (только проверки, exit 0), заведомо падающий (что делает recovery при ошибке, управляется ли пультом), очистка; затем реальный пакет.

---

## 6. Решения пользователя (не переспрашивать)

- Основной плеер **VLC**; Kodi удалён со всеми зависимостями; IPTV — своя плитка «ТВ-каналы» (без EPG).
- Samba+Termux удалены. glmark2, MediaCenter, SkySetupWizard удалены. «Все настройки» убраны (есть меню настроек).
- Шрифты CJK и копии APK в /product — **не трогаем**.
- Передний дисплей — как в стоке; ретро-игры — не цель.
- Антивирус: Hypatia (форк MaintainTeam 3.18), **выключен по умолчанию, всё по запросу**, своя плитка.
- **su оставлен для отладки**; adb без авторизации пока тоже (критичные пункты аудита — осознанно).
- Источники обновлений: флешка/SD + адрес в локальной сети + интернет (последнее — после решения вопроса с паролем).
- Обновление: два режима — с сохранением данных и с очисткой.
- На eMMC без SD: плитка «Установка» работает только как обновление (нет пунктов установки/бэкапа).
- Прошивка SD — загрузкой со стока (сейчас стока на eMMC нет → см. §7); перезагружаю сам по adb.
- Изменения вести в `gamesmod/garbage` (папка rk3318-tvbox, ветка rk3318-tvbox; в master не пушить).
- Общение по-русски, коротко; пароль Wi‑Fi в чат не выводить.

---

## 7. Процедуры

**Перезапуск тестовой tvboxd на SD-1.0** (скрипт `td.sh` на приставке в `/data/local/tmp`):
```
export CLASSPATH=/data/local/tmp/tvboxd.jar
pkill -f org.tvbox.daemon.Main; nohup app_process /system/bin org.tvbox.daemon.Main >/data/local/tmp/tvboxd.out 2>&1 &
```
Клиент для отладки: `CLASSPATH=... app_process /system/bin org.tvbox.daemon.Main client STATUS` (также `verify ПАКЕТ CERTS.zip`).

**Записать SD целиком** (SD-образ 1.2 ещё НЕ записан на карту; на ней 1.0):
- раньше: выключить → вынуть SD → включить (грузится eMMC) → вставить SD → `adb push release/1.2/sd/. /data/local/tmp/sd10/` → сверить SHA256SUMS → `su 0 sh sd-write.sh` (проверяет ff500000, размер, не смонтирована; пишет и сверяет; размеры >2 ГиБ через awk) → удалить временное → `adb reboot`.
- теперь на eMMC не сток, а наш Android 12 (1.1, затем 1.2) — та же схема работает (adb root есть).

**Установить на eMMC:** только кнопкой пользователя в «Установке» (двойное подтверждение). Я слежу: `STATUS` (BUSY) и `/data/vendor/tvbox/last-operation.log`.

**Новый выпуск:** правки → `apps/*/build.sh` → `MEDIA=emmc ./build-sd.sh` → `./build-sd.sh` (**не править build-sd.sh во время сборки**) → сверка образов (распаковать super `tools/lpunpack.py`, смонтировать ro) → `release/X` + `RELEASE-X.md` → `tools/release-ota.sh` → `tools/sync-garbage.sh`.

---

## 8. Безопасность (сводка, подробно `security-audit.md`)

- Вредоносного кода нет (BADBOX 2.0/Vo1d/Triada IOC, домены C2 — проверено). Исходящих соединений кроме LAN нет.
- Открыто осознанно: su для всех (отладка), adb без ключа, SELinux permissive, TorrServer :8090 без пароля и UPnP.
- Исправлено: Samba (удалена), OTA-ключ (свой), вендорский OTA-адрес (удалён).
- Пароль Wi‑Fi в образе (`/vendor/etc/tvbox/wifi.env`) → образы и OTA не публиковать.

---

## 9. Уроки (выстрадано — проверять перед похожими шагами)

- **mksh и `[ -gt ]` на Android 32-битные**: 4 ГиБ → 0. Большие числа — awk или на ПК. (Ложный «verify FAIL super».)
- toybox `head -c` и `md5sum` по ≥4 ГиБ потоку врут — сверять кусками по 64 МиБ.
- Не передавать конвейеры в `su 0 sh -c '…'` через `adb shell "…"` — пушить скрипт и запускать файлом.
- `pkill -f`/`pgrep -f` с шаблоном из собственной команды убивают свою оболочку (exit 144) — сначала список PID.
- **Не править `build-sd.sh` во время сборки** (bash читает скрипт по ходу: «se.txt: command not found»). Не запускать сборку jar/APK в фоне вместе с образом.
- TV Launcher: плитки в `shared_prefs/selected_apps.xml`, `apps_order`, разделитель U+001E (`&#30;`), максимум 14; файл писать **до** force-stop (лаунчер = HOME, сразу перезапускается).
- Вызов REQUEST_DISCOVERABLE через окно выбора оставил ввод без фокуса (пульты «умерли») — запускать компонент TV-настроек явно; лечение — `am start HOME`.
- toybox sed без `\s` → `[[:space:]]`; `sed -i` теряет метку SELinux → chcon/lbl.
- Recovery Rockchip: BCB в misc по 0 и 0x4000; `--update_package` и `--wipe_data` взаимоисключающие; `/sbin/busybox` 32-битный — использовать `unzip`(ziptool)/`gzip`(toybox) 64-бит.
- Паритет «живое ↔ образ» включает и данные в /data (плитки, настройки приложений), не только /system /vendor /product.
- Вендорский preinstall.sh копирует prefs VLC с гонкой — настройки приложений ставить в tvbox-firstboot.sh после установок.
- Проверка безопасности Claude Code блокирует `rm -rf $VAR` и удаление рабочего каталога — `"${VAR:?}"`, явные пути; рабочий каталог удаляет пользователь.

---

## 10. Открытые задачи (по порядку)

1. **Пользователь ставит 1.2 на eMMC** (кнопка «Установить на внутреннюю память»), я слежу за журналом.
2. Загрузить eMMC 1.2 (вынуть SD) и испытать OTA через recovery: пустышка → падающий → с очисткой → настоящий (пакеты-тесты собрать `tools/mkota.py` с фейковым out-каталогом, media=emmc). Проверить: монтирует ли recovery /data, BCB, поведение при ошибке, управление пультом в recovery.
3. Записать SD-образ 1.2 на карту (с загрузки eMMC, `device/sd-write.sh`), чтобы SD-запасной был той же версии.
4. Решить вопрос пароля Wi‑Fi в образе (вынести из образа → включить интернет-канал обновлений; нужен публичный хостинг).
5. Шум adbd «FUNCTIONFS_BIND» (~1/с) — убрать USB-режим adb, не потеряв сетевой adb.
7. Безопасность по решению пользователя: su/adb/SELinux enforcing/пароль TorrServer.
8. Проверить субтитры `none` в VLC на файле с субтитрами; QR в «Ссылках» — телефоном.

## 2026-10-28 — чистая установка eMMC 0.3.3 по USB (успешно)
- `adb reboot loader` (кабель A-A в OTG) → `sudo python3 -I tools/rkusb.py flash release/0.3.3/emmc` (свой rockusb-клиент) → `sudo sdk/rk/usr/bin/rkdeveloptool rd`.
- tools/flash-emmc-usb.sh (rkdeveloptool 1.32) НЕ использовать: чтение с LBA >= 65536 в Loader отдаёт 0xCC, сверка невозможна; rkusb.py сверяет <32 МиБ по USB, остальное — из системы (dd 64 МиБ + md5 против manifest).
- Результат: загрузка 0.3.3 с ff520000 за ~105 с, /data чистый (221M). Сверка 84 кусков: super/boot/recovery/uboot/trust/GPT совпали; security/misc/cache/metadata/userdata отличаются — их пишет сама система (ожидаемо).
- Открыто: загрузка с SD (зависает на логотипе → fastboot, secure:yes unlocked:no).
