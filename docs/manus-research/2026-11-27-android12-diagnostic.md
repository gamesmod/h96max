# Android 12 на H96 Max RK3318/RK3328: совместимость, загрузка и диагностическая матрица

**Дата:** 27 ноября 2026 г.  
**Автор:** Manus AI  
**Предмет:** H96 Max V12 на RK3318; платформа RK3328/RK3318; вендорская сборка Android 12.

**Статус в Panadonik:** внешний исследовательский отчёт Manus, а не аппаратная приёмка и не доказательство для release gate. Выводы о конкретной приставке требуют проверки в репозитории и на устройстве; локальные факты проекта имеют приоритет над публичными предположениями отчёта.

H96 Max V12 официально поставляется с Android 12 на RK3318, и официальный центр прошивок содержит образ `RK3318_userdebug_H96_Max_V12_12_20221117.1447` [1] [2]. При этом публичной ветки Android 12 именно для RK3318 в открытых репозиториях Rockchip не найдено: у `device-rockchip-rk3328` на дату проверки видна ветка `rk3328-box-7.1`, а не Android 12 [6]. Поэтому ниже Android 12 рассматривается как вендорская сборка поверх платформы RK3328/RK3318, а не как документированный Rockchip BSP. Это главное ограничение: поведение AOSP, RK3328 SoC DTS и конкретной платы H96 Max может различаться.

Ниже нет предложения прошивать устройство. Диагностическая матрица построена вокруг чтения, измерений и сравнения наблюдаемых признаков. Операции, меняющие загрузчик, DDR-конфигурацию, ATF, ядро или разделы, вынесены за пределы матрицы и обозначены как высокорисковые.

## 1. Платформа, прошивка и статус публичных данных

### 1.1. Паспорт H96 Max V12

Официальная страница H96 Max описывает V12 как ТВ-бокс с RK3318, четырьмя Cortex-A53, GPU «Penta-Core Mali-450 up to 750 MHz+», 2 или 4 ГБ DDR3, eMMC 16/32/64 ГБ, Android 12, USB 3.0 ×1 и USB 2.0 ×1, HDMI 2.0a до 4K@60, LAN 100 Мбит/с и питанием 5 В/2 А [1]. Эти параметры — исходная точка, но они не заменяют проверку конкретного экземпляра: вендорские ревизии плат и прошивок могут отличаться.

| Параметр | H96 Max V12 / RK3318 | RK3328 | Источник |
|---|---|---|---|
| CPU | 4 × Cortex-A53 | 4 × Cortex-A53 | [1] [3] [4] |
| GPU | H96 заявляет Mali-450 «Penta-Core», до 750 МГц | Официально Mali-450MP2, до 500 МГц | [1] [5] |
| RAM | 2 или 4 ГБ DDR3 | DDR3/DDR3L/LPDDR3/DDR4, 32-бит | [1] [5] |
| Накопитель | eMMC 16/32/64 ГБ, microSD | eMMC 4.51, SD, SPI | [1] [5] |
| H.264 | до 4K@30 | до 4K@60 | [3] [4] |
| HEVC/H.265 | Main profile, до 4K@60 | Main/Main10, до 4K@60 | [3] [4] |
| VP9 | до 4K@60 | до 4K@60 | [3] [4] |
| HDMI | 2.0a, 4K@60, HDCP | 2.0a, 4K@60, HDCP 1.4/2.2 | [1] [5] |
| USB | USB 3.0 ×1, USB 2.0 ×1 | USB 3.0 OTG + USB 2.0 OTG + EHCI/OHCI | [1] [4] [26] |
| BootROM | SPI, eMMC, SDMMC0; USB OTG device mode | То же | [3] [4] |
| Питание | 5 В/2 А | — | [1] |

Различие по GPU важно для диагностики. Маркетинговое «Penta-Core» у H96 Max не следует автоматически превращать в «пять ядер Mali-450»: в тексте даташита RK3318 GPU описан как quad-core, а у RK3328 официально указан Mali-450MP2 [1] [3] [5]. Фактическое число PP-ядер и частоту нужно проверять по строке renderer в работающей системе или по логу драйвера, а не по наклейке на коробке.

Различие по видеодекодеру ещё важнее. RK3318 по даташиту декодирует H.264 до 4K@30 и HEVC только Main profile до 4K@60; RK3328 — H.264 до 4K@60 и HEVC Main/Main10 до 4K@60 [3] [4]. Поэтому «4K H.264 тормозит» на RK3318 может быть не ошибкой Android 12, а пределом аппаратного профиля. Для HEVC Main10 на RK3318 аппаратного Main10-профиля нет; если файл Main10, система может уйти в программное декодирование или отказать.

### 1.2. Прошивка Android 12

Официальный центр прошивок H96 перечисляет модель `3318V12`, версию `20221117.1447` и файл `RK3318_userdebug_H96_Max_V12_12_20221117.1447` [2]. Это `userdebug`-сборка, то есть она может допускать `adb root`, но это не гарантируется: проверяйте `ro.debuggable` и фактическое поведение `adb root`.

Сторонняя страница OTA приводит build number `google/redfin/redfin:12/SP1A.210812.015/7679548:user/release-keys__2021-10-05` [43]. Это **неподтверждённое наблюдение**: по нему нельзя делать вывод о составе прошивки. Если строка действительно такая, она указывает на AOSP-базу уровня Android 12 с fingerprint Pixel 5 (`redfin`), а не на Android TV; тогда Android TV-сервис `HdmiControlService` может отсутствовать. Проверять нужно на устройстве: `getprop ro.build.fingerprint`, `ro.product.device`, `ro.build.version.sdk`, `ro.build.type`, `ro.build.date.utc`.

### 1.3. Публичный BSP и границы достоверности

В открытом репозитории `rockchip-android/device-rockchip-rk3328` на дату проверки видна ветка `rk3328-box-7.1` [6]. Это не доказывает отсутствие закрытого Android 12 BSP у Rockchip или H96, но показывает, что публично документированной Android 12-конфигурации для RK3318 нет. Отсюда следуют ограничения:

- в публичных источниках точная версия ядра H96 не установлена; в проекте Panadonik для проверенной синей платы зафиксировано Linux 4.19.232 #50 — это локальное доказательство, а не публичный источник;
- состав DTB и board-specific DTS H96 не опубликован; SoC DTS RK3328 — только ориентир [26];
- неизвестно, включены ли DMC, thermal, CEC, rkvdec и какие пороги/частоты переопределены платой;
- build fingerprint из сторонней страницы не проверен на устройстве [43];
- форумные сообщения 4PDA — наблюдения, а не контролируемые доказательства [41] [42];
- в отчёте нет доступа к живому устройству, дампу прошивки и UART-логу, поэтому все выводы — проверяемые гипотезы, а не заключение о конкретном экземпляре.

Дата среза публичных источников — 27 ноября 2026 г. Версии, использованные для сверки: RK3318 datasheet V1.0, RK3328 datasheet V1.2, Rockchip DDR Developer Guide V1.3.0 (2021-01-21), U-Boot v2026.04, TF-A LTS v2.14.8, H96 build `20221117.1447`; сторонняя страница OTA с build fingerprint опубликована 22 апреля 2025 г. [3] [4] [7] [8] [25] [43].

Для сверки с проектом: в `COMPONENTS.md` и `research-base.md` проверенная плата описана как Android 12 userdebug с ядром Linux 4.19.232; это локальные данные Panadonik, они не входят в публичный набор источников и не отменяют необходимость измерений. В отчёте они используются только как указание, какой kernel проверять через `uname -a`.

## 2. Карта загрузки Android 12 на RK3318/RK3328

### 2.1. Цепочка стадий

На RK3328/RK3318 загрузка идёт от внутреннего BootROM к раннему загрузчику, затем к BL31 и U-Boot, после чего ядро передаёт управление Android `init`. TF-A для AArch64 описывает последовательность `BootROM → BL1/BL2 → BL31 → BL33 → Linux kernel`; BL1/BL2 и BL33 могут быть предоставлены U-Boot, в том числе как TPL+SPL или только SPL [8]. U-Boot для RK3328 собирается с `BL31` из TF-A, а для инициализации DRAM может использовать TPL из `rkbin` или упаковку с Rockchip miniloader [7] [9].

У U-Boot Rockchip есть два взаимозаменяемых способа упаковки: `u-boot-rockchip.bin` с TPL/SPL либо Rockchip miniloader с `idbloader.img`, `trust.img` и `uboot.img` [7]. По UART-логу можно отличить не только стадии, но и тип упаковки: в одном случае видны сообщения TPL/SPL, в другом — сообщения miniloader. Вендорская прошивка H96 может использовать любой из вариантов; заранее это не предполагается.

BootROM RK3318/RK3328 поддерживает загрузку с SPI, eMMC и SDMMC0, а также режим USB OTG device для загрузки кода [3] [4]. Если все носители не дали валидный загрузчик, SoC может перейти в Mask ROM USB-режим. В документации U-Boot приведён пример `lsusb` с Rockchip VID `2207` и PID `330c` для Mask ROM, но PID зависит от SoC; ориентироваться нужно на фактический `lsusb`, а не на конкретный PID [7].

### 2.2. Стадии и наблюдаемые маркеры

| Стадия | Что происходит | Наблюдаемый маркер | Источник |
|---|---|---|---|
| Питание и сброс | Подача 5 В, сброс SoC, BootROM | Первый байт UART; ток потребления; `lsusb` в Mask ROM | [3] [7] |
| DDR init | TPL или DDR-бинарь инициализирует DRAM | `DDR Version ...`, `Channel a: DDR3 ...`, `Memory OK`, `OUT` | [25] |
| SPL/miniloader | Загружает BL31 и U-Boot | `U-Boot SPL`, сообщения miniloader, `Trying to boot from MMC` | [7] [9] |
| BL31 / TF-A | Secure monitor, PSCI, SIP-вызовы | Сообщения TF-A/BL31; далее U-Boot proper | [8] |
| U-Boot | Проверяет boot/vendor_boot, выбирает слот, грузит kernel/ramdisk | `Starting kernel` | [7] [10] [11] |
| Ядро | Инициализирует драйверы, монтирует rootfs | dmesg `[0.000000]`, `Run /init` | [10] [13] |
| init, first stage | Монтирует `/dev`, `/proc`, early mounts | `ro.boottime.init`; dmesg | [13] |
| init, SELinux | Загружает политику | `ro.boottime.init.selinux`; `avc: denied` | [13] |
| init, second stage | Запускает сервисы по `init.rc` | `boot_progress_start` | [13] [16] [17] |
| Zygote | Стартует system_server, preload | `boot_progress_start` 3000; `preload_start/end` 3020/3030 | [16] [17] |
| SystemServer | PMS, AMS, WM, сервисы | 3010, 3060–3100, 3040 | [16] [18] [19] |
| Boot animation / launcher | Экран, анимация, launcher | 31007, 3050, `sys.boot_completed=1` | [16] [20] |

Android 12 может использовать boot image header v3 или v4; v3 добавляет bootconfig, v4 добавляет `boot_signature`, а `vendor_boot` используется для GKI-конфигураций [11]. Это не означает, что конкретная H96-прошивка обязательно использует v3/v4: вендорский порт может остаться на более старом заголовке. Проверять нужно распаковкой образа (`unpack_bootimg`) или чтением свойств загрузчика, а не предположением.

AVB/dm-verity проверяют целостность загрузочных и системных разделов; состояние можно читать через `ro.boot.verifiedbootstate`, `ro.boot.veritymode`, `ro.boot.flash.locked`, а ошибки — через `dmesg` и `logcat` [39] [40]. На `userdebug`-сборке AVB может быть отключён или переведён в permissive; это не считается дефектом само по себе, но должно быть явно зафиксировано в паспорте устройства.

## 3. Время загрузки: bootstat, boot_progress, dmesg и UART

### 3.1. Почему нельзя сравнивать «время» из разных источников напрямую

В одной загрузке одновременно существуют несколько шкал: хост-время UART от подачи питания, время ядра от `0.000000`, `CLOCK_BOOTTIME` от старта загрузки, монотонное время Android от старта системы и wall-clock время logcat. Числа в `bootstat`, `boot_progress`, `ro.boottime.*` и `dmesg` имеют разные единицы и разные начала отсчёта. Без приведения они дают ложные «сдвиги» на секунды и минуты. Для автоматического разбора тех же источников полезен `bootanalyze.py`, который использует `logcat -b all -v epoch`, dmesg, `ro.boottime.*` и `ro.boot.boottime` [22].

| Источник | Часы | Единицы | Начало отсчёта | Что даёт | Ловушка |
|---|---|---|---|---|---|
| UART-логгер на хосте | Монотонное время хоста | с или мс | Подача питания | Абсолютная шкала T0; DDR/U-Boot/kernel | Задержка USB-UART и реле |
| `dmesg` | Время ядра | с | Старт ядра | Драйверы, `Run /init` | Не включает bootloader |
| `ro.boottime.init` | `CLOCK_BOOTTIME` | нс | Старт загрузки | Старт first stage init | Не путать с `ro.boottime.<service>` |
| `ro.boottime.init.first_stage` | `CLOCK_BOOTTIME` | нс | Старт загрузки | Длительность first stage | Не абсолютное время |
| `ro.boottime.init.selinux` | `CLOCK_BOOTTIME` | нс | Старт загрузки | Длительность SELinux | Не абсолютное время |
| `ro.boottime.init.modules` | — | мс | — | Загрузка модулей | Единицы отличаются от соседних свойств |
| `ro.boottime.init.cold_boot_wait` | — | мс | — | Ожидание coldboot ueventd | Не путать с `modules` |
| `boot_progress_*` | `CLOCK_MONOTONIC`/`elapsedRealtime` | мс | Старт загрузки | Стадии Zygote/SystemServer | Payload — мс, а не epoch |
| `bootstat` | `CLOCK_BOOTTIME` минус offset | с или мс | Старт загрузки | Итоговые метрики и bootloader-этапы | Разные события — разные единицы |
| `logcat -v epoch` | Wall clock | мс/мкс | Unix epoch | Корреляция с внешними логами | Не использовать для дельт стадий |
| Bootchart | Время init | с | Условный 0 init | CPU/I/O профиль | init показан с 0; нужен dmesg |

### 3.2. bootstat: что именно он записывает

`bootstat` хранит события в файлах каталога `/data/misc/bootstat/`; значение события хранится в mtime файла, а `bootstat -p` печатает заголовок `Boot events:` и строки `event<TAB>value` [14]. На `sys.boot_completed=1` срабатывает правило `bootstat.rc`, которое запускает `bootstat --record_boot_complete --record_boot_reason --record_time_since_factory_reset -l`; повторный запуск предотвращается свойством `sys.bootstat.first_boot_completed` [15].

Ключевые события и единицы:

- `boot_complete` — секунды от старта загрузки до `sys.boot_completed=1`; комментарий в исходнике прямо говорит, что значение записывается в секундах, хотя поле statsd-атома описано в миллисекундах [14].
- `boot_complete_no_encryption`, `boot_complete_post_decrypt`, `boot_decryption_complete` — варианты для шифрования/дешифрования [14].
- `boottime.bootloader.1BLE`, `1BLL`, `KL`, `2BLE`, `2BLL`, `SW`, `total` — миллисекунды; берутся из `ro.boot.boottime` формата `stage:time,...` [14].
- `ro.boottime.init`, `ro.boottime.init.first_stage`, `ro.boottime.init.selinux`, `ro.boottime.init.cold_boot_wait` — в bootstat записываются в миллисекундах, хотя одноимённые свойства init — в наносекундах [13] [14].
- `absolute_boot_time` — секунды, оценка «от включения до boot_complete» с добавлением bootloader-этапов; этап `SW` при этом исключается [14].
- `boot_reason`, `system_boot_reason`, `time_since_last_boot`, `last_boot_time_utc` — причина и история загрузок [14] [12].

Практическое следствие: если сравнивать `boot_complete` (секунды) с `boot_progress_start` (миллисекунды) без пересчёта, получится ошибка в 1000 раз. Если сравнивать `ro.boottime.init` из `getprop` (наносекунды) с одноимённым событием `bootstat` (миллисекунды), получится ошибка в 1 000 000 раз.

На `userdebug`-сборке `bootstat -p` обычно доступен после `adb root`; на `user`-сборке каталог `/data/misc/bootstat` имеет режим `0700 system:log` и может быть недоступен оболочке [15]. Если `bootstat` недоступен, это ограничение сбора, а не доказательство отсутствия загрузки: используйте `logcat -b events`, `dmesg` и UART.

### 3.3. boot_progress: стадии Android в миллисекундах

`boot_progress_start` (3000) логируется в `AndroidRuntime.cpp` при старте первичного zygote с опцией `startSystemServer`; значение — `ns2ms(systemTime(SYSTEM_TIME_MONOTONIC))`, то есть миллисекунды от старта загрузки [17]. `boot_progress_system_run` (3010) логируется в `SystemServer` как `SystemClock.elapsedRealtime()` в миллисекундах [18]. Оба значения — payload события, а не wall-clock метка logcat.

| ID | Событие | Смысл | Источник |
|---|---|---|---|
| 3000 | `boot_progress_start` | Первичный zygote стартует system_server | [16] [17] |
| 3010 | `boot_progress_system_run` | Вход в SystemServer | [16] [18] |
| 3020 | `boot_progress_preload_start` | Начало preload Zygote | [16] |
| 3030 | `boot_progress_preload_end` | Конец preload Zygote | [16] |
| 3040 | `boot_progress_ams_ready` | ActivityManagerService готов | [19] |
| 3050 | `boot_progress_enable_screen` | Экран включён | [19] |
| 3060 | `boot_progress_pms_start` | PackageManagerService стартует | [18] |
| 3070 | `boot_progress_pms_system_scan_start` | Сканирование system-разделов | [18] |
| 3080 | `boot_progress_pms_data_scan_start` | Сканирование data | [18] |
| 3090 | `boot_progress_pms_scan_end` | Сканирование завершено | [18] |
| 3100 | `boot_progress_pms_ready` | PMS готов | [18] |
| 31007 | `wm_boot_animation_done` | Boot animation завершена | [20] |

`event.logtags` описывает поля `(time|2|3)`, где единица поля 3 — миллисекунды; при этом собственные метки событий в event log — wall-clock [16]. Поэтому `logcat -v epoch` даёт календарное время строки, а число внутри события остаётся миллисекундами от старта. Для дельт стадий нужно брать именно payload.

### 3.4. Как свести шкалы вместе

1. Запустите UART-логгер до подачи питания и зафиксируйте T0 на хосте. Считайте `DDR Version`, `Memory OK`, `OUT`, `U-Boot`, `Starting kernel` с хостовыми метками.
2. Привяжите `dmesg` к UART по сообщению `Starting kernel`: `dmesg` начинается с `0.000000` примерно в точке входа ядра. Запишите смещение между хост-временем `Starting kernel` и `dmesg` в нулевой момент.
3. Привяжите `ro.boottime.init` (нс) к T0: абсолютное время старта first stage init примерно равно `T0 + ro.boottime.init/1e9`. Сравните с `dmesg` `Run /init`; расхождение больше ~0,5 с — повод проверить offset/часы.
4. Привяжите `boot_progress_start` (мс) к T0: `T0 + value/1000`. Проверьте, что `boot_progress_start` больше `ro.boottime.init` и меньше `boot_progress_system_run`.
5. Привяжите `boot_complete` (с) к T0: `T0 + value`. Сравните с `absolute_boot_time` и `boottime.bootloader.total`: `absolute_boot_time` должен быть близок к `boot_complete + bootloader.total` (за вычетом `SW`).
6. `logcat -v epoch` используйте только для сопоставления с внешними событиями (питание, HDMI, USB), не для вычисления стадий.

## 4. Диагностическая матрица

### 4.1. Как читать матрицу

Матрица описывает гипотезы и read-only доказательства. Она не предписывает прошивать, стирать или менять загрузчик. Шкала риска:

- **R1 — чтение:** `getprop`, `dmesg`, `logcat`, `dumpsys`, чтение sysfs/procfs, `bootstat -p`, UART, USB-метр. Состояние устройства не меняется.
- **R2 — низкий:** `adb root` (перезапускает adbd), `adb reboot`, вскрытие корпуса для UART, `rkdeveloptool ld`, `mmc extcsd read`. Обратимо, но меняет режим или доступ.
- **R3 — средний:** запись в sysfs (`governor`, `min_freq`, `UDC`), configfs, отключение сервисов/настроек. Требует фиксации исходного значения и обратного отката.
- **R4 — высокий:** перепрошивка, стирание, разблокировка, замена ATF/DDR-бинаря/ядра. В диагностическую матрицу не входит.

Сокращения оборудования: **UART** — USB-TTL 3,3 В, 1 500 000 8N1, TX/RX/GND, без VCC [44]; **PSU** — лабораторный 5 В/3 А с ограничением тока; **TC** — термопара или тепловизор; **CEC** — USB-CEC-адаптер с Linux-хостом (`cec-ctl`) или логический анализатор линии CEC; **IR** — ИК-приёмник/логический анализатор; **USB analyzer** — анализатор USB 2.0/3.0.

### 4.2. Матрица 1: загрузка и время

| Симптом | Вероятная подсистема | Первичный источник | Read-only доказательства | Доп. оборудование | Риск | Критерий закрытия |
|---|---|---|---|---|---|---|
| Нет реакции, UART молчит, LED не меняется | BootROM, питание, DDR, reset | RK3318 datasheet BootROM [3]; U-Boot/TF-A [7] [8] | UART на 1,5 Мбод; `lsusb` с Rockchip VID `0x2207`; `rkdeveloptool ld`, если доступен; напряжение и ток на разъёме | UART, PSU, мультиметр | R1; вскрытие корпуса — R2 | 20/20 включений дают UART или Mask ROM; ток и 5 В в норме; если есть Mask ROM, но нет UART — искать DDR/loader; если нет обоих — питание/SoC |
| UART обрывается на `DDR Version`, нет `Memory OK` | DDR init, TPL/miniloader, питание | DDR guide [25]; rkbin [9]; U-Boot [7] | Строки `DDR Version`, `Channel a: DDR3 ...`, `Memory OK`, `OUT`; отсутствие `U-Boot SPL`; `dmesg` не появляется | UART, PSU, TC | R1 | 20/20 холодных стартов доходят до `OUT` и U-Boot; нет DDR-ошибок; 5 В стабильны; иначе — DDR-конфигурация/питание/железо |
| U-Boot есть, `Starting kernel` нет или циклический reboot | boot image, AVB, TF-A/BL31, kernel | U-Boot [7]; TF-A [8]; AOSP bootloader [10]; boot image [11]; AVB [39] | UART до `Starting kernel`; `ro.boot.verifiedbootstate`, `ro.boot.veritymode`, `ro.boot.flash.locked`; offline `unpack_bootimg`/`avbtool verify_image`; отсутствие dmesg | UART, USB | R1; перепрошивка — R4 и не входит | Состояние AVB объяснено, заголовок boot/vendor_boot согласован, `Starting kernel` появляется; если нет — bootloader/AVB/kernel |
| `Starting kernel` есть, Android не доходит | init, ramdisk, SELinux, system | AOSP init [13]; bootstat [14] [15] | `Run /init`; `ro.boottime.init`; `init: cannot execv`; `avc: denied`; отсутствие `sys.boot_completed` | UART | R1 | Стадии first stage, SELinux и second stage завершены, появился `boot_progress_start`; иначе — init/ramdisk/SELinux |
| Android грузится, но нет launcher или bootanim loop | Zygote, SystemServer, PMS/AMS, SurfaceFlinger, launcher | AOSP event tags [16] [17] [18] [19] [20]; boot times [21] | Последовательность 3000, 3020/3030, 3010, 3060–3100, 3040, 31007, 3050; `dumpsys activity`, `dumpsys window`; logcat crashes | Нет или UART | R1 | События монотонны, launcher виден; отсутствующее событие указывает стадию |
| `bootstat` пуст, значения отсутствуют или «сдвинуты» | bootstat, init, timebase | bootstat.cpp [14]; bootstat.rc [15]; init README [13]; event.logtags [16] | `bootstat -p`; `getprop ro.boottime.init`; `logcat -b events`; `ls -ld /data/misc/bootstat`; `getprop sys.boot_completed` | Нет | R1; `adb root` — R2 | Есть `boot_complete`, `ro.boottime.init`, 3000/3010; единицы согласованы; иначе серия помечается невалидной и заменяется dmesg/UART |

### 4.3. Матрица 2: память, GPU, видео, thermal

В референсном RK3328 DTS узел DMC имеет `compatible = "rockchip,rk3328-dmc"`, governor `dmc_ondemand`, `upthreshold = 40`, `downdifferential = 20`, `auto-freq-en = 0` и таблицу `system-status-freq`; по умолчанию узел может быть `status = "disabled"` [26]. Thermal-зоны имеют trips 70 °C и 85 °C (passive) и 115 °C (critical), а `tsadc` — `rockchip,hw-tshut-temp = <100000>`, то есть 100 °C [26] [27]. GPU-узел — `arm,mali-450` с OPP 200/300/400/500 МГц [26]. Для видео в BSP DTS соседствуют legacy VPU и `rkvdec`; вендорский Android обычно использует MPP, а mainline rkvdec — другой, stateless V4L2-путь [26] [37]. Эти значения — референс, а не гарантия для H96.

| Симптом | Вероятная подсистема | Первичный источник | Read-only доказательства | Доп. оборудование | Риск | Критерий закрытия |
|---|---|---|---|---|---|---|
| Нет `/sys/class/devfreq/dmc`, частота не меняется, ошибки OPP/ATF | DMC, DFI, ATF/BL31, DTB | DMC driver [23]; binding [24]; DDR guide [25]; RK3328 DTS [26] | `ls /sys/class/devfreq`; `cat dmc/available_frequencies`, `cur_freq`, `governor`, `trans_stat`, `upthreshold`, `downdifferential`, `system_status`; `dmesg` с фильтром `dmc`; `/proc/device-tree/dmc/status` | Нет или UART | R1; запись governor — R3 | DMC присутствует, OPP-список непуст, `trans_stat` меняется под нагрузкой, нет ATF/OPP-ошибок; если узла нет — DT disabled или драйвер не поддерживает плату, это не автоматически дефект |
| Перегрев, троттлинг, выключение около 100 °C | thermal, tsadc, cooling devices | Thermal driver [27]; thermal sysfs [28]; Android thermal [29]; RK3328 DTS [26] | `thermal_zone*/temp`, `trip_point_*_temp/type`, `cooling_device*/cur_state/max_state`; `dumpsys thermalservice`; `logcat -b events` с фильтром `thermal_changed`; `dmesg` с фильтром `thermal` | TC | R1 | Под нагрузкой максимум ниже 85 °C, нет critical/TSHUT, cooling states меняются; если выключение около 100 °C — это аппаратный TSHUT (в RK3328 DTS 100 °C), а не Linux critical 115 °C; подтвердить внешним TC |
| GPU-приложение падает, нет GLES, чёрный UI | Mali, kbase/libmali, Lima, EGL, SurfaceFlinger | Mesa Lima [30]; Arm Mali-450 [31]; RK3328 GPU node [26] | `ls /dev/mali0`, `/dev/dri`; `ls /vendor/lib*/egl`; `dumpsys SurfaceFlinger` с фильтром `GLES`; `logcat` с фильтром `libGLES_mali`, `eglInitialize`, `mali`; `dmesg` с фильтром `mali` | Нет | R1 | Renderer соответствует ожидаемому, EGL инициализируется, приложение не требует GLES 3/Vulkan; Mali-450 поддерживает GLES 2.0, а Lima — GLES 2.0/частично GL 2.1 [30]; проблемы дисплея — не GPU |
| 4K H.264/HEVC/VP9 тормозит или даёт артефакты | VPU/MPP, Codec2/OMX, rkvdec, DMC, HDMI | RK3318 datasheet [3]; RK3328 datasheet [4]; rkvdec [37]; 4PDA-наблюдение [42] | `dumpsys media.codec`, `dumpsys media.player`; `ls /dev/mpp_service /dev/video*`; `dmesg` с фильтром `rkvdec`, `vpu`, `mpp`; `logcat` с фильтром `Codec2`, `OMX`, `CCodec`, `rkvdec`; профиль потока; `dumpsys display` | Нет; HDMI 2.0 cable/TV для 4K60 | R1 | Выбран аппаратный кодек, профиль в пределах SoC (RK3318: H.264 4K30, HEVC Main; RK3328: H.264 4K60, HEVC Main10), нет `rkvdec` timeout, dropped frames <1%; иначе — программный fallback/предел SoC |
| Нет 4K60, падение в 1080p, мигание HDMI | dw-hdmi, VOP, EDID/HPD, кабель | RK3328 HDMI node [26]; AOSP display stack [21] | `dumpsys display`; `dumpsys SurfaceFlinger`; `/sys/class/drm/card0-HDMI-A-1/status`, `edid`, `modes`; `dmesg` с фильтром `dw-hdmi`, `edid`, `hpd` | Заведомо исправный HDMI 2.0-кабель, 4K60 TV, EDID-ридер | R1 | EDID читается, выбран режим 4K60, нет HPD-флапа; иначе — кабель/TV/порт, не GPU |
| После логотипа чёрный экран, UART/U-Boot живы | VOP, dw-hdmi, EDID/HPD, SurfaceFlinger | RK3328 HDMI node [26]; AOSP boot times [21] | UART `Starting kernel`; `dumpsys display`; `/sys/class/drm/card0-HDMI-A-1/status`, `edid`, `modes`; `dmesg` с фильтром `vop`, `dw-hdmi`, `edid`, `hpd` | Заведомо исправный HDMI-кабель, TV, EDID-ридер | R1 | EDID читается, HPD стабилен, SurfaceFlinger имеет дисплей; иначе — VOP/HDMI/кабель/TV, не загрузчик |

### 4.4. Матрица 3: USB, CEC, накопитель, питание, standby, сеть

| Симптом | Вероятная подсистема | Первичный источник | Read-only доказательства | Доп. оборудование | Риск | Критерий закрытия |
|---|---|---|---|---|---|---|
| ADB/USB OTG не работает, роль не переключается | DWC2/DWC3, PHY, extcon, USB HAL, configfs | RK3328 USB node [26]; Rockchip USB guide [33]; Android USB HAL [32]; configfs [34] | `ls /sys/class/udc`; `cat /sys/class/udc/*/state`; `getprop sys.usb.config`, `sys.usb.state`; `ls /config/usb_gadget`; `dumpsys usb`; `dmesg` с фильтром `dwc2`, `dwc3`, `phy`, `otg`, `vbus`, `id`; debugfs `ff580000.usb/state` | USB-метр, OTG-адаптер, USB analyzer, заведомо исправный кабель | R1; запись `UDC`/configfs — R3 | UDC присутствует, gadget bind проходит, host видит устройство, ADB стабилен, роль переключается; если `dr_mode=host`, gadget невозможен |
| HDMI-CEC не работает | dw-hdmi-cec, CEC framework, Android HdmiControlService/HAL | Kernel CEC [35]; Android HDMI-CEC [36]; RK3328 HDMI node [26]; Rockchip issue [38] | `ls /dev/cec*`; `dmesg` с фильтром `cec`; `getprop` с фильтром `hdmi`; `pm list features` с фильтром `hdmi`; `dumpsys hdmi_control`, если сервис есть; kernel config `CONFIG_DRM_DW_HDMI_CEC`; `/proc/device-tree/hdmi/status` | USB-CEC + Linux `cec-ctl`, логический анализатор CEC (pin 13), HDMI-кабель | R1 | `/dev/cec0` есть, `cec-ctl --info` и `cec-ctl -S` видят TV, Android HAL/сервис присутствует, One Touch Play работает; если сервиса нет — вендорская сборка не включает Android TV CEC, это не обязательно ошибка ядра |
| eMMC/SD ошибки, долгий boot, fsck | eMMC, SDHCI, filesystem | RK3318 datasheet [3]; AOSP boot times (I/O) [21] | `cat /proc/partitions`; `lsblk`; `dmesg` с фильтром `mmc`, `sdhci`, `emmc`; `/sys/block/mmcblk0/device/*`; `df -h /data`; `dumpsys diskstats` | Нет или UART | R1 | Нет `mmc`/I/O errors, размер eMMC соответствует 16/32/64 ГБ, fsck чистый, boot time стабилен; иначе — накопитель/питание |
| Ребут под нагрузкой, нестабильные USB/Wi-Fi | PMIC, regulators, USB VBUS, 5 В | H96 5 В/2 А [1]; RK3318 datasheet [3]; Rockchip USB guide [33] | Внешний PSU/USB-метр; `dumpsys battery`; `/sys/class/power_supply/*/uevent`; `dmesg` с фильтром `regulator`, `voltage`, `brownout`; повторить без USB HDD | PSU 5 В/3 А, USB-метр, мультиметр | R1 | 5,0–5,2 В стабильны, просадки не ниже 4,8 В при boot/нагрузке; 20/20 стартов; если только с нагрузкой — питание/кабель |
| Сам включается после выключения пультом | standby/wake, CEC, IR, PMIC, RTC | Android CEC power control [36]; kernel CEC [35]; 4PDA-наблюдение [41] | `dumpsys power`; `dumpsys deviceidle`; `dmesg` с фильтром `wake`, `rtc`, `pmu`, `cec`, `ir`; `/proc/interrupts`; `/sys/power/wakeup_count` | IR-приёмник/анализатор, CEC-анализатор, мультиметр | R1; отключение CEC/IR — R3 | Источник wake идентифицирован и воспроизводится только при условии; если CEC/IR — проверить настройку; иначе PMIC/RTC. Это наблюдение, не подтверждённый вендорский баг |
| Сеть ≤10 Мбит/с после Android 12 | Ethernet PHY, Wi-Fi, USB-сеть | H96 100 Мбит/с LAN [1]; 4PDA-наблюдение [42] | `/sys/class/net/eth0/speed`, `duplex`, `operstate`; `ethtool`, если есть; `dmesg` с фильтром `phy`; `dumpsys wifi`; `ip addr`; iperf3 | Заведомо исправный Cat5e/6, 100M/1G switch, USB Wi-Fi | R1 | Линк 100 Мбит full duplex, iperf3 стабилен; если 10 Мбит — кабель/PHY/негоциация. Это наблюдение, не доказанный баг прошивки |

## 5. Типичные ошибки и строки логов

Строки ниже — ориентиры, а не гарантированный словарь конкретной H96-прошивки. Точные сообщения зависят от версии ядра, драйвера и вендорских патчей. Там, где строка взята из открытого драйвера или гайда, это указано ссылкой; остальные формулировки — типовые шаблоны для поиска.

| Строка или шаблон | Где | Что означает | Что проверить |
|---|---|---|---|
| `DDR Version ...`, `Channel a: DDR3 ...`, `Memory OK`, `OUT` | UART, ранний loader | DDR-инициализация и самотест | Нет `Memory OK`/`OUT` — DDR/питание; нет `U-Boot SPL` — loader [25] |
| `DDR DEBUG:` | dmesg | Ядро печатает DDR-информацию | Отсутствие не обязательно ошибка: драйвер может быть отключён [25] |
| `trusted firmware need update to V1.00 and above.` | dmesg, DMC | ATF/BL31 слишком старый для DMC-вызовов | Версия BL31/ATF; не обновлять вслепую [23] |
| `Failed to find opp for ... Hz` | dmesg, DMC | Нет OPP для запрошенной частоты/вольтажа | OPP-таблица, DTB, регулятор [23] |
| `failed to add devfreq` | dmesg, DMC | DMC не зарегистрировал devfreq | DTB, DFI, OPP, probe order [23] |
| `Current ATF unsupported get_stall_time` | dmesg, DMC | ATF не поддерживает SIP-запрос | Версия ATF/BL31 [23] |
| `thermal thermal_zone0: critical temperature reached` | dmesg, thermal core | Достигнут critical trip | Температура, cooling states; при ~100 °C возможен аппаратный TSHUT раньше 115 °C [26] [27] |
| `Frame processing timed out!` | dmesg, rkvdec | Аппаратный декодер завис | Поток, формат, DMC/thermal, зависание rkvdec [37] |
| `dw-hdmi ff3c0000.hdmi: ...`, `cec: ...` | dmesg, HDMI/CEC | Ошибки HDMI/CEC | `/dev/cec*`, EDID/HPD, pinctrl/clock CEC [26] [35] |
| `dwc2 ff580000.usb: ...`, `dwc3 ...` | dmesg, USB | Ошибки USB-контроллера/PHY | UDC, роль, VBUS/ID, debugfs `state` [33] |
| `mmc0: error -110`, `mmcblk0: error` | dmesg, MMC | Таймаут/ошибка I/O накопителя | Кабель/питание/SD/eMMC, `mmc extcsd read` [3] |
| `avc: denied`, `init: cannot execv` | dmesg/logcat, init/SELinux | Политика или отсутствующий бинарь | Контекст, файл, policy; не менять policy без причины [13] |
| `dm-verity: ...`, `avb_slot_verify` | dmesg/bootloader | Ошибка целостности/AVB | `ro.boot.verifiedbootstate`, `ro.boot.veritymode`, хеши [39] [40] |
| `libGLES_mali.so` отсутствует, `eglInitialize` fail | logcat, GPU/EGL | Нет vendor-драйвера или несовместимый стек | `/dev/mali0`, `/vendor/lib*/egl`, renderer; Lima vs libmali [30] |
| `OMX.rockchip`/`c2.rockchip` отсутствует | logcat, media | Нет вендорского codec-плагина | `dumpsys media.codec`, `/dev/mpp_service`, media_codecs.xml [37] |
| `vop`/`rockchip-drm` ошибки | dmesg, display | Проблема VOP/DRM, не Mali | `dumpsys display`, DRM status/EDID [26] |

## 6. Схема многократного benchmark загрузки с `boot_id`

### 6.1. Цель и дизайн

Цель — измерить не «одно время загрузки», а распределение этапов на серии одинаковых стартов и связать каждый запуск с уникальным `boot_id`. Базовая схема: 30 холодных стартов от реле питания и 10 тёплых `adb reboot` как контроль. Холодные и тёплые серии анализируются отдельно; смешивать их нельзя.

Стенд должен быть заморожен на время серии:

- одна и та же прошивка, один и тот же экземпляр платы, один и тот же eMMC/SD;
- один и тот же HDMI-телевизор, порт и кабель; желательно один и тот же режим 4K60;
- одна и та же сеть (Ethernet или Wi-Fi), один и тот же USB-набор; ADB-кабель не меняется;
- корпус вертикально, температура старта 23 ± 2 °C, без прямого солнца;
- автообновления, синхронизация и фоновые загрузки отключены или зафиксированы;
- питание через реле/умную розетку с фиксацией T0; после выключения пауза не меньше 10 с для разряда.
- UART-логгер запущен до подачи питания; ADB-сборщик запускается после `sys.boot_completed=1`.

### 6.2. Этапы и маркеры

| ID | Этап | Источник | Единицы | Как получить |
|---|---|---|---|---|
| T0 | Подача питания | Хост/реле | с | Метка хоста в момент включения |
| T1 | Первый байт UART | UART | с | Хост-время первого байта |
| T2 | Начало DDR | UART | с | `DDR Version` |
| T3 | Конец DDR | UART | с | `Memory OK`/`OUT` |
| T4 | U-Boot proper | UART | с | `U-Boot ...`/`Trying to boot from MMC` |
| T5 | Вход в ядро | UART | с | `Starting kernel` |
| T6 | Старт init | dmesg | с от старта ядра | `Run /init` |
| T7 | Старт Zygote (startSystemServer) | `boot_progress_start` 3000 | мс от старта | payload события |
| T8 | Конец preload | `boot_progress_preload_end` 3030 | мс | payload |
| T9 | SystemServer | `boot_progress_system_run` 3010 | мс | payload |
| T10 | PMS готов | `boot_progress_pms_ready` 3100 | мс | payload |
| T11 | AMS готов | `boot_progress_ams_ready` 3040 | мс | payload |
| T12 | Boot complete | `sys.boot_completed=1`/`boot_complete` | с | property/bootstat |
| T13 | Boot animation done | 31007 | мс | payload |
| T14 | Экран включён | 3050 | мс | payload |
| T15 | Launcher виден | `dumpsys activity`/`dumpsys window` | мс/с | Первый кадр launcher |

`boot_progress_start` — это старт первичного zygote с `startSystemServer`, а не «начало загрузки» [17]. `boot_progress_system_run` — вход в SystemServer [18]. `sys.boot_completed=1` и `boot_complete` обычно совпадают по моменту, но `boot_complete` пишется в секундах [14]. `wm_boot_animation_done` и `enable_screen` могут идти в разном порядке; фиксируйте фактическую последовательность, а не ожидаемую.

### 6.3. Сбор и `boot_id`

`boot_id` — UUID из `/proc/sys/kernel/random/boot_id`, уникальный для каждой загрузки. Он должен быть первичным ключом каталога и CSV-строки. Дополнительно фиксируйте `ro.boot.bootreason`, `ro.boot.boottime_offset`, `ro.boot.boottime`, `ro.boottime.init`, `ro.build.fingerprint`, `ro.build.date.utc`, `uname -a`, `cat /proc/cmdline`.

Пример хостового цикла:

```bash
for i in $(seq 1 30); do
  relay off
  sleep 10
  t0=$(date +%s.%N)
  relay on
  # UART logger already writes host-timestamped lines
  adb wait-for-device
  adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 0.2; done'
  adb shell 'sh /data/local/tmp/collect_boot.sh'
  adb pull /data/local/tmp/boot_bench "./runs/run_$i"
done
```

Пример device-side сборщика:

```sh
#!/system/bin/sh
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)
DIR="/data/local/tmp/boot_bench/$BOOT_ID"
mkdir -p "$DIR"
getprop > "$DIR/props.txt" 2>&1
dmesg > "$DIR/dmesg.txt" 2>&1
logcat -b all -d -v epoch > "$DIR/logcat.txt" 2>&1
bootstat -p > "$DIR/bootstat.txt" 2>&1
cat /proc/uptime > "$DIR/uptime.txt" 2>&1
cat /proc/sys/kernel/random/boot_id > "$DIR/boot_id.txt" 2>&1
for z in /sys/class/thermal/thermal_zone*; do
  echo "== $z ==" >> "$DIR/thermal.txt"
  cat "$z/type" "$z/temp" >> "$DIR/thermal.txt" 2>&1
done
ls -l /sys/class/devfreq > "$DIR/devfreq.txt" 2>&1
```

`adb root` может потребоваться для `dmesg` и `bootstat -p`; он перезапускает adbd, поэтому выполняется после boot complete, а не во время замера. Если `adb root` недоступен, используйте `su 0` или ограничьтесь `logcat`/`dumpsys`; отсутствие `bootstat` фиксируйте как ограничение серии, а не как ошибку загрузки.

### 6.4. Схема CSV и производные метрики

| Поле | Источник | Единица | Назначение |
|---|---|---|---|
| `run` | Номер запуска | — | Порядок |
| `boot_id` | `/proc/sys/kernel/random/boot_id` | UUID | Первичный ключ |
| `bootreason` | `ro.boot.bootreason` | строка | Холодный/тёплый/сбой |
| `firmware` | `ro.build.fingerprint`, `ro.build.date.utc` | строка | Паспорт |
| `kernel` | `uname -a` | строка | Версия ядра |
| `t0` | Хост | с | Абсолютная точка |
| `t_uart_first` | UART | с | BootROM/loader |
| `t_ddr_end` | UART | с | Конец DDR |
| `t_uboot` | UART | с | U-Boot |
| `t_kernel` | UART | с | `Starting kernel` |
| `t_init` | dmesg/`ro.boottime.init` | с | init |
| `t_bp_start` | 3000 | мс | Zygote (startSystemServer) |
| `t_preload_end` | 3030 | мс | Preload |
| `t_system_run` | 3010 | мс | SystemServer |
| `t_pms_ready` | 3100 | мс | PMS |
| `t_ams_ready` | 3040 | мс | AMS |
| `t_boot_complete` | property/bootstat | с | Boot complete |
| `t_bootanim` | 31007 | мс | Анимация |
| `t_screen` | 3050 | мс | Экран |
| `t_launcher` | `dumpsys` | с | UI ready |
| `temp_start`, `temp_max` | thermal | °C | Тепловой режим |
| `dmc_cur`, `dmc_trans` | devfreq | Гц/счётчик | DMC |
| `errors` | dmesg/logcat | строки | Качество серии |

Производные метрики: длительности этапов `T(i+1) − T(i)`, полное время `T15 − T0`, вклад каждого этапа, отношение к медиане, коэффициент вариации. Для каждого этапа считайте медиану, IQR, p10/p90 и bootstrap 95% CI; среднее арифметическое менее устойчиво к выбросам. Выбросы определяйте по 1,5 × IQR, но не удаляйте автоматически: помечайте и объясняйте.

### 6.5. Валидность серии

Запуск включается в анализ, только если:

- `boot_id` совпадает во всех файлах и уникален;
- `ro.boot.bootreason` соответствует ожидаемому холодному/тёплому старту, без `kernel_panic`/`watchdog`;
- `sys.boot_completed=1` достигнут, все обязательные маркеры присутствуют;
- температура старта в пределах 23 ± 2 °C, нет признаков перегрева;
- прошивка, ядро и `ro.build.fingerprint` совпадают с паспортом;
- нет `dm-verity`/AVB-ошибок, `mmc`-ошибок и критических thermal-событий;
- HDMI-режим и сеть совпадают с зафиксированными.

Если маркер отсутствует, не подставляйте ноль: помечайте `NA` и исключайте только соответствующий этап, сохраняя запуск для общей статистики. Если `bootstat` недоступен, серия может быть проанализирована по UART/dmesg/`boot_progress`, но не должна смешиваться с серией, где `bootstat` есть.

### 6.6. Что сравнивать

Сравнивайте не только полное время, но и вклад этапов. Типичные вопросы: DDR/loader стабилен или даёт разброс; U-Boot тратит время на USB/HDMI; kernel probe delay; init/PMS/AMS вносят основной вклад; bootanim/launcher добавляют секунды; DMC/thermal меняют картину на 2–3-м старте. Для A/B-сравнения прошивок или настроек используйте Mann–Whitney U или bootstrap разницы медиан при одинаковых условиях; не сравнивайте холодную и тёплую серии между собой.

## 7. Ограничения и неподтверждённые наблюдения

### 7.1. Ограничения

- Нет живого устройства, UART-лога, дампа прошивки и точного board DTS H96; выводы основаны на публичных источниках и референсном RK3328 DTS.
- Публичный `device-rockchip-rk3328` содержит ветку `rk3328-box-7.1`; Android 12 BSP для RK3318 публично не подтверждён [6].
- Даташиты RK3318 V1.0 и RK3328 V1.2 описывают SoC, а не конкретную плату; пороги thermal, частоты DMC и включение узлов могут быть переопределены вендором [3] [4] [26].
- `bootstat` может требовать root; на `user`-сборке его данные могут быть недоступны [15].
- CEC в Android — опциональная функция; её отсутствие на медиабоксе не обязательно является ошибкой ядра [36].
- Точные строки логов зависят от версии ядра и вендорских патчей; таблица ошибок — ориентир, а не исчерпывающий словарь.
- Build fingerprint `google/redfin/redfin:12/...` взят со сторонней страницы и не проверен на устройстве [43].

### 7.2. Наблюдения 4PDA, которые нельзя считать доказательствами

В теме H96 Max RK3318 есть сообщения о 4K-тормозах после перехода на Android 12 при нормальном Full HD, о перегреве и низком FPS в играх, об артефактах 4K HEVC и гипотезе QoS rkvdec, о сети ≤10 Мбит/с, о чёрном экране после прошивки и о самопроизвольном включении примерно через 20 минут после выключения пультом [41] [42]. Например, сообщение 23704 датировано 04.01.2026, сообщение 23718 — 19.01.2026 [42]. Это **наблюдения**, а не контролируемые эксперименты: неизвестны точная прошивка, температура, питание, кодек, профиль потока и настройки. Их полезно использовать как список гипотез для матрицы, но не как доказательство причины.

Отдельно стоит 4K HEVC и QoS. В mainline-драйвере rkvdec для RK3328 действительно есть флаг `RKVDEC_QUIRK_DISABLE_QOS`, то есть отключение QoS заложено для этого варианта декодера [37]. Однако вендорская Android 12 использует не mainline rkvdec, а Rockchip MPP/Codec2/OMX; патч mainline-драйвера нельзя автоматически переносить на вендорский стек. Это гипотеза для проверки через `dumpsys media.codec` и `logcat`, а не готовое решение.

### 7.3. Что явно не подтверждено

- В публичных источниках версия ядра H96 Android 12 (4.19 или 5.10) не подтверждена; локально проект фиксирует 4.19.232, но итоговую версию всё равно проверяйте через `uname -a`.
- Точный build fingerprint и security patch — не подтверждены; читайте `ro.build.fingerprint`.
- Наличие Android TV `HdmiControlService` и HAL CEC — не подтверждено; проверяйте `pm list features`, `dumpsys hdmi_control`, `/dev/cec*`.
- Включение DMC, thermal, rkvdec и HDMI CEC в board DTB — не подтверждено; проверяйте runtime `/proc/device-tree` и `dmesg`.
- Аппаратный TSHUT H96 может отличаться от референсных 100 °C; проверяйте `rockchip,hw-tshut-temp` в runtime DT и внешним термометром.
- 4PDA-сообщения о 4K, сети и самовключении — не подтверждены контролируемыми измерениями.

## 8. Итог

Для H96 Max V12 на RK3318 диагностику стоит вести в порядке: идентификация платы и прошивки → UART-цепочка BootROM/DDR/U-Boot/kernel → `boot_id` и сведение `bootstat`/`boot_progress`/dmesg → DMC и thermal → видео/GPU → USB/CEC → питание и накопитель. Такой порядок отделяет «не стартует вообще» от «стартует, но деградирует», а внутри Android — этапы загрузки от подсистем.

Ключевые развилки для этой платформы: 4K H.264 на RK3318 ограничен 4K@30, HEVC — Main, а не Main10; DMC зависит от ATF/OPP и может отсутствовать в DTB; аппаратный TSHUT в референсном RK3328 DTS стоит на 100 °C раньше Linux critical 115 °C; Mali-450 — только GLES 2.0, а Lima не заменяет vendor libmali; CEC часто отсутствует на медиабоксах, даже если SoC поддерживает его; USB OTG зависит от `dr_mode`, PHY и extcon.

Для benchmark используйте 30 холодных и 10 тёплых стартов, `boot_id` как первичный ключ, UART от T0, `boot_progress` в миллисекундах и `bootstat` в секундах с явным пересчётом. Медиана/IQR и этапные дельты дадут больше, чем одно «время загрузки»; серия с разными `boot_id`, температурой или прошивкой не должна смешиваться.

## References

[1]: https://www.h96max.com/products-details/h96-max-v12-android-tv-box "H96 Max V12 Android TV Box — официальные характеристики"
[2]: https://www.h96tvbox.com/firmware-download/ "H96 Max Firmware Centre — официальные прошивки"
[3]: https://rockchip.fr/RK3318%20datasheet%20V1.0.pdf "Rockchip RK3318 Datasheet V1.0"
[4]: https://rockchip.fr/RK3328%20datasheet%20V1.2.pdf "Rockchip RK3328 Datasheet V1.2"
[5]: https://www.rock-chips.com/a/en/products/RK33_Series/2017/0118/829.html "Rockchip RK3328 — Key features"
[6]: https://github.com/rockchip-android/device-rockchip-rk3328 "Rockchip Android device tree for RK3328"
[7]: https://docs.u-boot.org/en/v2026.04/board/rockchip/rockchip.html "U-Boot Rockchip documentation"
[8]: https://trustedfirmware-a.readthedocs.io/en/lts-v2.14.8/plat/rockchip.html "Trusted Firmware-A Rockchip SoCs"
[9]: https://github.com/rockchip-linux/rkbin "Rockchip rkbin repository"
[10]: https://source.android.com/docs/core/architecture/bootloader "Android bootloader overview"
[11]: https://source.android.com/docs/core/architecture/bootloader/boot-image-header "Android boot image header"
[12]: https://source.android.com/docs/core/architecture/bootloader/boot-reason "Android boot reason"
[13]: https://android.googlesource.com/platform/system/core/+/refs/heads/main/init/README.md "AOSP init README"
[14]: https://android.googlesource.com/platform/system/core/+/refs/heads/main/bootstat/bootstat.cpp "AOSP bootstat.cpp"
[15]: https://android.googlesource.com/platform/system/core/+/refs/heads/main/bootstat/bootstat.rc "AOSP bootstat.rc"
[16]: https://android.googlesource.com/platform/system/logging/+/refs/heads/main/logcat/event.logtags "AOSP event.logtags"
[17]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/jni/AndroidRuntime.cpp "AOSP AndroidRuntime.cpp Android 12"
[18]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/EventLogTags.logtags "AOSP SystemServer EventLogTags"
[19]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/EventLogTags.logtags "AOSP ActivityManager EventLogTags"
[20]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/EventLogTags.logtags "AOSP WindowManager EventLogTags"
[21]: https://source.android.com/docs/core/perf/boot-times "Android boot times"
[22]: https://raw.githubusercontent.com/jbeich/platform_system_extras/master/boottime_tools/bootanalyze/bootanalyze.py "AOSP bootanalyze.py"
[23]: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-5.10/drivers/devfreq/rockchip_dmc.c "Rockchip DMC driver develop-5.10"
[24]: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-5.10/Documentation/devicetree/bindings/devfreq/rockchip_dmc.txt "Rockchip DMC DT binding"
[25]: https://pro934ad4-pic3.ysjianzhan.cn/upload/Rockchip-Developer-Guide-DDR-EN.pdf "Rockchip DDR Developer Guide V1.3.0"
[26]: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-5.10/arch/arm64/boot/dts/rockchip/rk3328.dtsi "RK3328 SoC device tree develop-5.10"
[27]: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-5.10/drivers/thermal/rockchip_thermal.c "Rockchip thermal driver"
[28]: https://docs.kernel.org/driver-api/thermal/sysfs-api.html "Linux thermal sysfs API"
[29]: https://source.android.com/docs/core/power/thermal-mitigation "Android thermal mitigation"
[30]: https://docs.mesa3d.org/drivers/lima.html "Mesa Lima driver"
[31]: https://developer.arm.com/Processors/Mali-450 "Arm Mali-450"
[32]: https://source.android.com/docs/core/permissions/usb-hal "Android USB HAL"
[33]: https://101.132.36.237/rockchip/common/usb/rockchip-developer-guide-usb-en "Rockchip Linux USB Developer Guide"
[34]: https://docs.kernel.org/usb/gadget_configfs.html "Linux USB gadget configfs"
[35]: https://docs.kernel.org/admin-guide/media/cec.html "Linux HDMI CEC framework"
[36]: https://source.android.com/docs/devices/tv/hdmi-cec "Android HDMI-CEC control service"
[37]: https://raw.githubusercontent.com/torvalds/linux/master/drivers/media/platform/rockchip/rkvdec/rkvdec.c "Linux rkvdec driver"
[38]: https://github.com/rockchip-linux/kernel/issues/12 "Rockchip kernel issue 12 HDMI CEC"
[39]: https://source.android.com/docs/security/features/verifiedboot "Android Verified Boot"
[40]: https://source.android.com/docs/security/features/verifiedboot/dm-verity "Android dm-verity"
[41]: https://4pda.to/forum/index.php?showtopic=952710 "4PDA H96 MAX RK3318 — наблюдения"
[42]: https://4pda.to/forum/index.php?showtopic=952710&st=23700 "4PDA H96 MAX — сообщения 23700 и далее"
[43]: https://chinagadgetsreviews.com/download-android-12-ota-stock-firmware-for-h96-max-v12-tv-box.html "China Gadgets Reviews H96 Max V12 Android 12 OTA"
[44]: https://community.t-firefly.com/en/docs/products/system-on-modules/Core-3328-JD4/debug "Firefly RK3328 serial debug 1500000 baud"
