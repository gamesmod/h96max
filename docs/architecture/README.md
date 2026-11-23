# Архитектура Panadonik

Редактируемая схема: [`panadonik-architecture.drawio`](panadonik-architecture.drawio) (draw.io / diagrams.net).

Три страницы:

1. **Архитектура решения** — аппаратная платформа H96 Max, разделы Android 12, службы (`tvboxd`, TorrServer, firstboot), плитки лаунчера и внешние источники.
2. **Подготовка образа** — входы → `tools/build-release.sh` → eMMC/OTA/clean-install → кандидат CI → аппаратная приёмка → теги `dev-v*` / `v*`.
3. **Загрузка и обновление** — чистая установка через Rockchip Loader (`tools/rkusb.py`) и OTA через «Обновление»/recovery; первая загрузка и два сценария приёмки (ТВ/USB).

## Процесс подготовки образа (кратко)

1. Подготовить входы: очищенная база A12 (`dl/a12` / LFS или `update.img`), firmware Wi‑Fi/BT из A9, ключи `secrets/`, сторонние APK, changelog `release/notes/<версия>.txt`.
2. Запустить `tools/build-release.sh`: SDK → сборка своих APK/JAR → `MEDIA=emmc ./build-sd.sh` → подписанный OTA и `Panadonik-<версия>-emmc-clean-install.zip`.
3. Проверить кандидата: SHA-256, подпись OTA, `check-image-wifi-secrets.py`, автотесты/IPTV.
4. Пройти аппаратную приёмку и заполнить `docs/release-qa/<версия>.*`.
5. Только после явного подтверждения пользователя: тег `dev-v<версия>` (prerelease) или `v<версия>` (stable).

Wi‑Fi credentials в публичный образ не вшиваются. Публикационный тег не отправлять до подтверждения.

## Процесс загрузки приставки (кратко)

**Чистая установка** (со стока / с нуля): скачать clean-install архив → проверить SHA-256 и подпись → USB-A↔A в OTG, режим Loader → `sudo python3 -I tools/rkusb.py flash release/<версия>/emmc` → первая загрузка. Подробности: [`docs/install-from-stock.md`](../install-from-stock.md).

**OTA**: скачать/получить `tvbox-update-<версия>-emmc.zip` из GitHub Releases → плитка «Обновление» или recovery → проверка подписи ota-ключом → по умолчанию очистка userdata с двумя подтверждениями в UI.

Критерий готовности после загрузки: рабочий стол, управляемый с USB-пульта. Приёмка включает сценарий у телевизора (Wi‑Fi) и у ПК (USB ADB без экрана).
