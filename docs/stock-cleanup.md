# Очистка стоковых разделов A12 для Git LFS

Дата подготовки: 2026-11-23 (Europe/Moscow). Исходник: `RK3318_userdebug_H96_Max_V12_12_20221117.1447`.
Очистка воспроизводится через `tools/prepare-ci-partitions.py` и список `tools/unused-stock-apps.txt`.
`super.img` в репозиторий не входит.

## Аппаратно отсутствующее на H96 Max

| Функция | Доказательство | Действие |
|---|---|---|
| Камера | нет модуля; camera HAL в vendor | удалены provider HAL, `.rc`, permissions XML, запись VINTF |
| Печать | нет принтера | удалены PrintSpooler и GooglePrintRecommendationService |
| Secure Element / SIM | TV-приставка | удалён SecureElement |
| Телефония / SMS / NFC / GPS / отпечаток / батарея / вибро | нет соответствующей периферии | APK-обвязки в кандидатах; framework jars не трогались |
| Bluetooth | DTS `wireless-bluetooth`, permissions `android.hardware.bluetooth*.xml` | **оставлен** |

## Удалённое (кратко)

Полный список путей и хеши — `dl/a12/Image/lp/cleanup-report.json`.

| Раздел | Было | Стало | Экономия | Примеры удаления |
|---|---:|---:|---:|---|
| system | 1483.1 MB | 921.7 MB | 535.4 MiB | chrome_hcy, katniss, preinstall (Netflix/TikTok/…), MediaCenter, ShowDebug, тесты OEM, PrintSpooler, SecureElement, telemetry bin |
| product | 660.1 MB | 43.9 MB | 587.7 MiB | GmsCore, WebViewGoogle, YouTube, PhoneskyTv, LatinImeGoogle, Wellbeing, GMS overlays |
| system_ext | 158.3 MB | 158.0 MB | 0.3 MiB | GoogleServicesFramework, GoogleFeedback |
| vendor | 182.9 MB | 181.9 MB | 1.0 MiB | camera HAL/permissions, xbmc_copy_folder, vendor_flash_recovery |
| vendor_dlkm / odm / odm_dlkm | без изменений по сути | — | 0 | пустая политика |

Итого по семи LP-образам: ~2503 MB → ~1324 MB (−1179 MB).

## Кандидаты (не удалялись)

- `FusedLocation`, `ContactsProvider`, `CalendarProvider`
- `CompanionDeviceManager`, `BasicDreams`, `HTMLViewer`, `WallpaperBackup`
- `Bluetooth`, `BoxLatinIME`, `VfdService`, `ChangeLed`, `FileExploresky`
- `Settings` / `SystemUI` / `TvSettings`, `GooglePackageInstaller`, NetworkStack*
- framework `telephony-common.jar` / `ims-common.jar` и location provider jar
- permissions NFC/GPS, если объявлены без отдельных APK

## Сторонние приложения (не в LFS)

См. `tools/third-party-apps.json` и `tools/fetch-third-party.sh`.
