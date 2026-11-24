# Компоненты образа rk3318-android: источники и сборка

Правило (решение пользователя, 2026-10-27): каждый компонент образа — **либо ссылка на проверяемый источник
(с версией, SHA-256 файла и отпечатком подписи), либо инструкция по сборке из исходников этого репозитория**.
Подписи APK проверяются `tools/apkcert.sh ФАЙЛ.apk` (apksigner из `sdk/x`, см. `tools/setup-sdk.sh`).
Выпуск целиком собирается с нуля одной командой: `tools/build-release.sh` (подробно — BUILD-AND-STATE.md).

## 1. Базовая прошивка (вендор)
| Что | Версия | Проверка | Источник |
|---|---|---|---|
| Образ Rockchip `update.img` | RK3318_userdebug_H96_Max_V12_12_20221117.1447 (Android 12, ядро 4.19.232) | sha256 `e93ccc90e5a41fbc430c1f9b3f4621989945ff5a6e9685de6d228f5e7cc5ad58` (`dl/fw/a12.sha256`) | mega.nz из описания видео на Rutube; **точная ссылка — уточнить**. Вход в сборку: `tools/prepare-build-inputs.sh` (вызывается из `build-release.sh` / `build-sd.sh` / release CI). По умолчанию проверяет очищенные LP из Git LFS; из сырого стока: `--update /path/to/update.img` или `TVBOX_UPDATE_IMG=…` → `tools/extract-vendor-blobs.sh` (как [LineageOS Wiki](https://wiki.lineageos.org/extracting_blobs_from_zips_manually)) + `prepare-ci-partitions` → `dl/a12`. Файловое дерево: `extract-vendor-blobs.sh --from-images dl/a12/Image/lp --dump build/system_dump`. `super.img` во входах сборки не держат; политика `tools/unused-stock-apps.txt`, отчёт `dl/a12/Image/lp/cleanup-report.json`, сводка [`docs/stock-cleanup.md`](docs/stock-cleanup.md). |

Из прошивки удаляются GMS, телеметрия (datadis, user_drmservice), стресс-тесты, OEM MediaCenter, камера (HAL) и прочий вендорский мусор по `tools/unused-stock-apps.txt` / `build-sd.sh`.

## 2. Сторонние приложения (ставятся при первой загрузке из /product/tvbox/apk; Obtainium проверяет обновления из источников приложений)
| Пакет | Версия | Источник (скачано отсюда) | SHA-256 файла | SHA-256 сертификата |
|---|---|---|---|---|
| `org.videolan.vlc` (VLC) | 3.7.2 | https://f-droid.org/repo/org.videolan.vlc_13070206.apk | `e748c3e0bb9ec447f3e49f71d5c73e0b48c2c0309faa7fb79806d3afb5601b66` | `80535be61eedb9a03b0476a6f493d496c3498770404339ea7a8000f5e61d22c0` |
| `ru.yourok.torrserve` (TorrServe) | MatriX.143.FOSS | https://f-droid.org/repo/ru.yourok.torrserve_177.apk | `c1021faf83e99bd9cdb7359581ec9c7dabf306ca37edaf286685b30d73e772a6` | `ce166643e2c6aad3f7433a9e2c8e67a34f88dcd0edea74fd244f9737f01a0287` |
| `dev.imranr.obtainium` (Obtainium) | 1.7.4 | [официальный GitHub-релиз](https://github.com/ImranR98/Obtainium/releases/tag/v1.7.4), arm64-v8a APK | `f9216f721a7713b75929e0806bc18586f8fcf7328fbbff99b6aef1debb9eef80` | `b353601f6a1d5fd6603ae2f50be80cf301367b86b6ab8b1f66243da96cd57362` |
| `com.tvlauncher` (TV Launcher) | 1.6.2 | [F-Droid APK](https://f-droid.org/repo/com.tvlauncher_10602.apk); [исходники](https://github.com/wiiguy/lightweight_androidtv_launcher) | `7afde83f96ccf30d1bc69ca06692d385800d2d9a14dd5f7d85fb4ad4f3c5c210` | `9e9f185a5b3cfb6ff65ac2907d3180c150483705ba129787c60667088647a767` |
| `org.maintainteam.hypatia` (Hypatia, антивирус-движок) | 3.18 | https://apt.izzysoft.de/fdroid/repo/org.maintainteam.hypatia_318.apk (IzzyOnDroid) | `d0e31c8dbb338e61220373be61ee524c8476f39aa2d59517984fe45c5e7576c3` | `1b008d64bb95ab4774d68b87f22b8be9a272f4924df52029d7e6183835d918cc` |

Совпадение VLC, TorrServe и TV Launcher с F-Droid проверено по sha256 против **подписанного**
индекса https://f-droid.org/repo/index-v1.jar (подпись JAR проверена, ключ репозитория `43238d51…9c9ccab`).

### Параметры лаунчера и плиток

- Домашнее приложение: `com.tvlauncher/.MainActivity` (TV Launcher 1.6.2 из F-Droid).
- Порядок: `/data/data/com.tvlauncher/shared_prefs/selected_apps.xml`, ключ `apps_order`; разделитель пакетов — U+001E, сериализуется как `&#30;`; лимит приложения — 14 плиток.
- После чистой установки `overlay/vendor/bin/tvbox-firstboot.sh` один раз останавливает HOME, убирает устаревшие записи и добавляет штатные плитки в исходном порядке. Маркер `/data/vendor/tvbox/launcher-tiles.initialized` блокирует повторную правку: пользователь может менять порядок и набор, обычная перезагрузка их не сбрасывает. Очистка данных возвращает исходную раскладку.
- Порядок по умолчанию: IPTV, TorrServe, FileExploresky, Ссылки, Обновление, Настройки Android, Производительность и диагностика, Антивирус, Питание. Журнал загрузки открывается из диагностики, Obtainium — из Обновления. VLC используется IPTV и TorrServe без отдельной плитки.

## 3. Системные компоненты из сторонних источников
| Что | Версия | Источник | SHA-256 |
|---|---|---|---|
| WebView `com.android.webview` (в /product/app/webview) | 155.0.8059.39 | LineageOS prebuilt: https://github.com/LineageOS/android_external_chromium-webview_prebuilt_arm64 (файл webview.apk) | `e91bced008fa9a2976997072cd9168c653905b538217530994f20d73276e72d9`, сертификат `32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0` |
| TorrServer (служба :8090, /system/bin/torrserver) | MatriX.147.1 | https://github.com/YouROK/TorrServer/releases/download/MatriX.147.1/TorrServer-android-arm64 | `93250bb1850e69663c6758398be515c2c429595cb4692c6eccb367f2236868d5` |
| Прошивка Wi‑Fi/BT (dhd config, BT hcd) | из стоковой A9 приставки | снято с eMMC стока (backup/), файлы в `a9fw/` | — |
| Раскладка ИК-пульта `ff1b0030_pwm.kl` | из стоковой A9 | `overlay/vendor/usr/keylayout/` | — |

## Наши подписи (ключи в `secrets/`, в репозиторий не попадают; ниже — только отпечатки сертификатов)
| Ключ | Чем подписано | Сертификат | SHA-256 сертификата |
|---|---|---|---|
| `secrets/tvbox-key.pem` | все наши APK и overlay (v1, `tools/v1sign.py`) | CN=tvbox-rk3318, до 2054-03-12 | `f52a109a77d33ff95bc97760c4b85358b678384f805035ebe0ef9d692e26f388` |
| `secrets/ota-key.pem` | OTA-пакеты `tvbox-update-*.zip` (signapk -w, `tools/mkota.py`); recovery и система доверяют **только** ему (`otacerts.zip`) | CN=panadonik tvbox OTA, O=tvbox, до 2056-10-19 | `906ea1f4a463757434aade51e7f469e7fd49030812e1db40cf74b55c2a7843c0` |

## 4. Наши приложения (собираются из исходников этого репозитория)
Все: `apps/<имя>/build.sh` (javac `--release 8` из `sdk/jdk` → dx → aapt → подпись v1 `tools/v1sign.py` ключом `secrets/tvbox-key.pem`);
все сразу — шаг 2 `tools/build-release.sh`. Тулчейн: `tools/setup-sdk.sh`.

| Плитка / компонент | Пакет / файл | Исходник | Что делает |
|---|---|---|---|
| ТВ-каналы | `org.tvbox.iptv` | `apps/iptv` | категории с пиктограммами → каналы с логотипами, качеством и EPG ID → только выбранный поток в VLC; однократная индексация M3U при старте с прогрессом/ETA и локальным кэшем |
| Установка и обновление | `org.tvbox.installer` | `apps/installer` | установка на eMMC, обновления (флешка/SD/сеть), резервная копия; активный этап и журнал операции с временными метками |
| Антивирус | `org.tvbox.antivirus` | `apps/antivirus` | проверка по требованию через Hypatia, базы, защита в реальном времени |
| Питание | `org.tvbox.power` | `apps/power` | выключение, сон, перезагрузка и переход в recovery; значок выключения рисуется приложением, не зависит от шрифта |
| Журнал загрузки | `org.tvbox.bootlog` | `apps/bootlog` | журнал ядра и ошибок; краткая сводка запуска также показана при старте и хранится для просмотра |
| Производительность | `org.tvbox.clean` | `apps/clean` | закрытие фоновых приложений и метрики; подкачка отключена по политике проекта |
| Ссылки | `org.tvbox.links` | `apps/links` (+ qrcodegen Nayuki, MIT) | QR для доступа к TorrServer с телефона |
| Файлы (root) | `org.tvbox.files` | `apps/files` | запасной файловый менеджер без плитки и без роли по умолчанию: просмотр и операции с файлами с правами root через `tvboxd` (`FS_*`), открытие файлов в VLC и других приложениях |
| tvboxd (root-служба) | `/system/framework/tvboxd.jar` | `apps/tvboxd` | файловые операции «Файлов (root)» (только их uid); установка/сверка eMMC, OTA (uncrypt), misc, антивирус; `PERF_ZRAM_SET` отклоняется |
| Заставка и сводка загрузки | `media/bootanimation.zip`, `org.tvbox.bootlog` | `tools/mkbootanim.py`, `apps/bootlog`, `overlay/vendor/bin/tvbox-startup-summary.sh` | статичная карточка PANADONIK/RK3318/1080p без 4K; после запуска Android краткий снимок CPU, GPU, RAM, температуры, хранилища и этапов с временными метками; Matrix и кадровая анимация удалены |

## 5. Генерируемые данные (инструкция по сборке)
| Что | Как получить |
|---|---|
| Заставка `media/bootanimation.zip` | `python3 -I tools/mkbootanim.py media/bootanimation.zip`; статичная карточка устройства, один кадр, без маркировки 4K |
| Список каналов `overlay/system/etc/tvbox/iptv.m3u` | `python3 -I tools/iptv-merge.py` (источники: `tools/iptv-sources.txt`; проверка логотипов и потоков); `python3 -I tools/iptv-verify.py overlay/system/etc/tvbox/iptv.m3u --report diagnostics/iptv-verify.csv` — отчёт о доступности и дублях |
| eMMC OTA-пакет | `tools/release-ota.sh ВЕР release/notes/ВЕР.txt` (подпись `secrets/ota-key.pem`, `tools/mkota.py`); образ для SD не входит в релиз |
| Помощник записи в recovery `ota/tvbox-flash` | `aarch64-linux-gnu-gcc -static -O2 -o ota/tvbox-flash ota/tvbox-flash.c` (шаг 3 build-release) |
| Прошивка eMMC по USB (чистая установка) | `tools/flash-emmc-usb.sh release/ВЕР/emmc` — rkdeveloptool 1.32+pine64git20240226 (пакет Ubuntu `rkdeveloptool`, исходник https://github.com/rockchip-linux/rkdeveloptool), режим Loader, кабель A–A в OTG |
| Ключи `secrets/*` | генерируются локально (openssl), в репозиторий не попадают |
