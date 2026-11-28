# План безопасного удаления OEM-тестов H96 Max V12

Дата: 28.11.2026. Область: четыре OEM-приложения в `system`-разделе H96 Max V12 (`RK3328`, Android 12, `userdebug`): `ShowDebug`, `ProductTest`, `RKHcyDeviceTest`, `StressTest`. Суммарный измеренный размер — **57 663 008 байт (55,0 МиБ)**. План не является разрешением на удаление конкретных компонентов: это порядок проверки, удаления и отката. Runtime-проверка на приставке пока не выполнена, поскольку ADB `10.12.54.42:5555` из песочницы недоступен.

Связанные материалы: [manifest-аудит](2026-11-27-size-reduction-rk3318.md#статический-manifest-аудит-oem-компонентов-28112026) и [CSV аудита](2026-11-28-h96-oem-static-audit.csv).

## 1. Главный вывод

Удалять все четыре приложения одним пакетом нельзя. Это не четыре одинаковых «теста»: `DeviceTest` — основной заводской функциональный и aging-инструмент с регистрацией устройства; `ProductTest` — заводской QA-сценарий, запускаемый файлом с SD/USB; `StressTest` — прожиг, reboot/recovery и опасные операции `wipeall`/`eraseflash`; `ShowDebug` — persistent-приложение лицензионной проверки и телеметрии, а не только отладочное меню.

Статический анализ **не нашёл** записи сенсорных/RF/аудио-калибровок в `/persist`, `/misc`, `/vendor` или иные калибровочные блобы; отдельного раздела `persist`/`factory` в разметке этой прошивки нет, поэтому проверялись только реально существующие каталоги. Найдены только чтение sysfs, тестовые файлы на SD/USB, SharedPreferences-счётчики, один persistent property (`persist.vendor.ethstate`), логи, регистрация устройства и лицензионная телеметрия. Это снижает риск потери уже записанной калибровки, но **не доказывает**, что заводская калибровка не выполняется отдельными native-инструментами или что эти APK не являются единственным операторским интерфейсом к ней.

Рекомендуемая стратегия — двухвариантная сборка:

| Вариант | Состав | Назначение |
|---|---|---|
| Production | Без четырёх OEM-тестов | Пользовательские приставки, максимальная экономия 55,0 МиБ и снижение attack surface |
| Factory/service | С четырьмя OEM-тестами или с минимальным диагностическим набором | Заводская линия, сервис, повторная калибровка, регистрация и прожиг |

Если двух вариантов быть не может, удаление выполняется поэтапно, с остановкой на любом заводском сценарии. Последним удаляется `DeviceTest`.

## 2. Что именно удаляется

| Приложение | Размер | Фактическая роль по коду | Заводской триггер | Риск для обычного пользователя | Риск для завода/калибровки |
|---|---:|---|---|---|---|
| `ShowDebug` (`com.android.show`) | 27,6 МиБ | Persistent boot/job-приложение; лицензионная проверка и отправка сведений об устройстве/установленных приложениях | Boot receiver и JobScheduler; на `userdebug` проверка лицензии отключается | Низкий: нет launcher/IME/home | Средний: на `user`-сборке возможна лицензионная проверка; телеметрия может быть частью бизнес-процесса |
| `ProductTest` (`com.example.a`) | 13,0 МиБ | Заводской QA: HDMI, LED, Wi-Fi, Ethernet, Bluetooth, MAC, aging; в исследованном коде результаты — SharedPreferences-счётчики, калибровочной записи не найдено | Файл `YXTPRODUCTTEST.txt` на SD/USB при `MEDIA_MOUNTED` | Низкий: скрытый launcher с именем «A» | Средний–высокий: может быть частью входного/выходного контроля производственной линии |
| `RKHcyDeviceTest` (`com.rockchip.devicetest`) | 9,3 МиБ | Основной заводской functional/aging/registration-инструмент: HDMI, CVBS, audio, LED, key, reset, SD, USB, MIC, BT, Wi-Fi, LAN, регистрация и `License.bin` | Файлы `Factory_Test.bin`, `Aging_Test.bin`, `SN_Test.bin` на SD/USB; boot receiver и TestService | Низкий: нет launcher/IME/home, но есть экспортированные activity/services | **Высокий:** удаление ломает заводской функциональный тест, aging и регистрацию; удалять последним |
| `StressTest` (`com.cghs.stresstest`) | 5,1 МиБ | Прожиг и стресс: reboot, recovery, camera, video, Wi-Fi, BT, sleep, ARM freq; флаги `wipeall`/`eraseflash` в `cust.xml`/`Recovery_state` | `RK_StressTest.txt`/`cust.xml` на SD/USB; boot receiver при выставленном `reboot_flag`; скрытый код калькулятора | Низкий, если `reboot_flag` не выставлен | Средний: заводской прожиг, recovery-проверки и опасные операции очистки; удалять только при отказе от них |

Все четыре APK подписаны одним AOSP testkey (SHA-256 `2D:37:0C:21:F5:DF:D5:53:D2:A7:96:31:4B:70:92:5F:B3:8A:DE:EF:90:86:4C:92:0B:BB:12:88:7D:35:22`) и используют `android.uid.system`. Это не делает их обязательными для загрузки, но означает, что каждый из них обладает широкими системными правами.

## 3. Оценка риска заводской калибровки

### 3.1. Что понимается под калибровкой

Калибровка — это запись индивидуальных параметров конкретного экземпляра: RF-мощности Wi-Fi/Bluetooth, чувствительности приёмников, аудиоуровней, HDMI/EDID, сенсоров, MAC/серийных номеров или их заводских дублей. Функциональный тест проверяет, что узел работает; aging/прожиг проверяет надёжность во времени; регистрация заносит устройство в базу и проверяет лицензию. Эти четыре категории нельзя смешивать.

### 3.2. Что подтверждено статически

| Приложение | Что делает с данными | Признаки калибровочной записи |
|---|---|---|
| `ShowDebug` | На `userdebug` license check не выполняется; `getShow()` отправляет сведения об устройстве и список приложений на OEM-сервер | Не найдено записи калибровочных параметров; есть сетевая телеметрия |
| `ProductTest` | Читает MAC, HDMI-статус, температуру, частоты; тестирует Wi-Fi/BT/HDMI/LED; хранит счётчики в SharedPreferences | Не найдено записи калибровочных параметров; только тестовые счётчики |
| `RKHcyDeviceTest` | Запускает functional/aging-тесты; `LanTest` пишет `persist.vendor.ethstate`; `RegistTest` читает `License.bin` и отправляет serial/MAC на сервер регистрации | Не найдено записи сенсорных/RF-калибровок; есть state-флаг и регистрация |
| `StressTest` | Прожиг, reboot/recovery, CPU freq, логи, `wipeall`/`eraseflash` | Не найдено калибровочных записей; есть опасные операции очистки и recovery |

Из четырёх приложений только `DeviceTest` пишет persistent property: `persist.vendor.ethstate` в `LanTest`. Это флаг состояния Ethernet-теста, а не калибровочная таблица. После удаления приложения значение может остаться в property-разделе; его нужно проверить и при необходимости сбросить отдельно, не трогая калибровочные данные.

### 3.3. Чего статический анализ не доказывает

- Неизвестно, какие native-бинарники и скрипты использует заводская линия вне этих APK.
- Неизвестно, пишут ли эти APK через `Runtime.exec`, `SystemProperties` или внешние сервисы в `/misc`, `/persist`, `/vendor` или `/data/vendor`; в коде явных калибровочных путей не найдено, но полный трассировщик зависимостей не строился.
- Неизвестно, является ли `RegistTest` обязательным для активации/лицензии на конкретной партии.
- `ShowDebug` на H96 `userdebug` не выполняет license check, но на будущей `user`-сборке поведение изменится; нельзя переносить вывод с `userdebug` на `user`.

### 3.4. Практическое следствие

Перед удалением нужно снять snapshot калибровочных данных и прогнать эталонную приставку через заводские сценарии. Если после удаления `DeviceTest` заводская линия не может выполнить functional/aging/registration, удаление не принимается независимо от экономии 9,3 МиБ. Если калибровка выполняется отдельным native-инструментом и подтверждена сравнением snapshot, APK можно удалить из production-варианта, сохранив factory-вариант.

## 4. Зависимости и побочные эффекты

| Зависимость | Что найдено | Действие в плане |
|---|---|---|
| Скрытые коды калькулятора | `ExactCalculator` по формуле `83,991,906` запускает `android.rk.intent.action.startStressTest`, по формуле `000.` — `rk.intent.action.startDevicetest` | После удаления тестов intents станут неразрешимыми; в production-сборке удалить/обезвредить эти ветки калькулятора или оставить без обработчика |
| Privapp allowlist | `com.android.show`: `MOUNT_UNMOUNT_FILESYSTEMS`, `SHUTDOWN`; `com.cghs.stresstest`: `MOUNT_UNMOUNT_FILESYSTEMS`, `WRITE_MEDIA_STORAGE`, `WRITE_SECURE_SETTINGS` | Удалить записи вместе с пакетами; проверить boot с `ro.control_privapp_permissions=enforce` |
| Boot/media receivers | `ShowDebug`, `DeviceTest`, `StressTest` имеют boot receivers; `ProductTest`, `DeviceTest`, `StressTest` реагируют на внешние носители | Проверить logcat и `dumpsys jobscheduler`; убедиться, что orphan jobs/receivers не остались |
| Файлы-триггеры | `Factory_Test.bin`, `Aging_Test.bin`, `SN_Test.bin`, `YXTPRODUCTTEST.txt`, `RK_StressTest.txt`, `cust.xml`, `License.bin` | Использовать только на заводском стенде; после удаления приложений файлы не должны запускать тесты |
| Recovery/reboot | `StressTest` вызывает `PowerManager.reboot`, `sys.powerctl`, recovery-проверки; `DeviceTest` имеет `ResetTest` | Не запускать на пользовательской приставке; factory-вариант сохраняет возможность проверки recovery |
| Лицензия/телеметрия | `ShowDebug` содержит URL `https://tvbox.hcybox.net:31443/api/v1/device/license/check` и `http://box.link.hcybox.net:31001/qbox/device/uploadinfo` | До удаления получить письменное подтверждение, что license check и uploadinfo не нужны в production |
| Общие UID/подписи | Все четыре — `android.uid.system`, один testkey | Удаление одного пакета не меняет подпись/UID остальных; проверить, что shared UID не используется для общих данных |

### 4.1. Доказательная база

Пути даны от корня образа (`mnt_system`/`mnt_vendor`), то есть так, как они выглядят внутри прошивки; smali-классы — из декодированных APK, а не из исходников H96.

| Компонент | Где смотреть | Что подтверждает |
|---|---|---|
| `ShowDebug` | `system/priv-app/ShowDebug/ShowDebug.apk`; `com/android/sdk/BootReceiver`, `CheckTask`, `SceduHelper`, `HttpUtils`, `JobUpInfoService` | Boot/job-запуск, license check, uploadinfo, отсутствие launcher/IME |
| `ProductTest` | `system/app/ProductTest/ProductTest.apk`; `TestBroadcast`, `MainActivity`, класс `n` | Триггер `YXTPRODUCTTEST.txt`, MEDIA_MOUNTED, тесты и SharedPreferences-счётчики |
| `RKHcyDeviceTest` | `system/app/RKHcyDeviceTest/RKHcyDeviceTest.apk`; `ConfigFinder`, `TestService`, `BootReceiver`, `RegistTest`, `LanTest`; `assets/testconfig.properties`, `assets/agingconfig.ini` | Файлы `Factory_Test.bin`/`Aging_Test.bin`/`SN_Test.bin`, functional/aging/registration, `persist.vendor.ethstate` |
| `StressTest` | `system/priv-app/StressTest/StressTest.apk`; `MountReceiver`, `RebootReceiver`, `RebootTest`, `RecoveryTest`, `StresstestUtil` | `RK_StressTest.txt`/`cust.xml`, reboot/recovery/wipe-флаги, `ctl.start=getbootmode` |
| Privapp allowlist | `system/etc/permissions/privapp-permissions-platform.xml`, `system/etc/permissions/privapp-permissions-tv-common.xml` | Разрешения `ShowDebug` и `StressTest`; записи нужно удалить вместе с пакетами |
| Boot-mode helper | `vendor/etc/init/hw/init.rockchip.rc`, `system/bin/getbootmode.sh` | Disabled oneshot `getbootmode`; запускается только `StressTest`, не является обязательным для boot |
| Калькулятор | `system/app/ExactCalculator/ExactCalculator.apk`; `Calculator.smali` | Скрытые коды `83,991,906` и `000.`, ведущие в тестовые intents |

## 5. Поэтапный план

### Этап 0. Заморозка, backup и baseline

Цель — иметь возможность доказать исходное состояние и откатиться.

1. Зафиксировать версию прошивки, SHA-256 полного образа, размеры и хэши всех разделов, `parameter.txt`, `super`-метаданные.
2. Сохранить копии четырёх APK, их `aapt dump badging/xmltree`, CSV-аудит и текущий package list.
3. На приставке снять baseline: `getprop`, `pm list packages -U`, `dumpsys package` для четырёх пакетов, `dumpsys activity`, `dumpsys jobscheduler`, `logcat` холодной загрузки, `settings list`, `dumpsys mount`, `df`, `free`.
4. Снять snapshot калибровочных и идентификационных данных из реально смонтированных каталогов: `/persist`, `/misc`, `/vendor`, `/data/vendor`, `/factory` (если существуют), `ro.serialno`, MAC Ethernet/Wi-Fi/Bluetooth, HDMI/EDID, аудио-настройки. Для каждого файла — SHA-256 и дата.
5. Определить эталонную приставку и заводской стенд. Без эталонного прогона нельзя оценивать риск калибровки.
6. Зафиксировать критерии приёмки: удалено 55,0 МиБ; нет fatal в logcat; нет privapp-нарушений; обычные функции работают; заводской прогон подписан; калибровочные хэши не изменились.

### Этап 1. Статический аудит и карта зависимостей

1. Проверить manifest каждого APK: sharedUserId, подпись, exported-компоненты, boot/media receivers, services, providers, permissions.
2. Проверить `privapp-permissions-*.xml`, `default-permissions`, `sysconfig`, `fs_config`, `file_contexts`, init/props на упоминания пакетов.
3. Проверить все внешние intents: калькулятор, setup wizard, factory automation, recovery, OTA.
4. Найти native-бинарники и скрипты, которые могут быть реальной калибровкой: `find` по именам `*calib*`, `*factory*`, `*test*`, `*sensor*`, `*wifi*`, `*bt*`, `*hdmi*`; не удалять их вместе с APK.
5. Составить список файлов, которые нужно удалить, и файлов, которые нужно оставить: HAL, кодеки, firmware, WebView, recovery-зависимости не трогать.

### Этап 2. Обратимый runtime-тест

Сначала не удалять файлы из образа, а отключить пакеты на живой приставке. Это обратимо и показывает реальные зависимости.

1. Включить ADB root на `userdebug` и проверить `pm list packages -U`.
2. Отключать по одному пакету: `pm disable-user --user 0 <package>`.
3. После каждого отключения — холодная перезагрузка, logcat, home/TV Settings, Wi-Fi/Ethernet/BT, HDMI/CEC, USB/SD, OTA, заводской триггер.
4. Для `StressTest` предварительно очистить `reboot_flag`; иначе приставка может уйти в цикл перезагрузок. Для `DeviceTest` проверить, что на носителе нет `Factory_Test.bin`/`Aging_Test.bin`/`SN_Test.bin`.
5. Проверить `dumpsys jobscheduler` на orphan jobs `ShowDebug`, `dumpsys activity services` на `TestService`/`WatchdogService`.
6. Вернуть пакет командой `pm enable <package>` и убедиться, что поведение восстановилось. Если отключение уже ломает заводской сценарий, удаление запрещено.

Порядок отключения для безопасного теста:

| Шаг | Пакет | Почему |
|---:|---|---|
| 1 | `com.cghs.stresstest` | Наименьшая пользовательская роль, но есть reboot/recovery/wipe-риски; проверить, не оставил ли он `reboot_flag` |
| 2 | `com.example.a` | Заводской QA по файлу `YXTPRODUCTTEST.txt`; обычный пользователь его не запускает |
| 3 | `com.android.show` | Persistent license/telemetry; на `userdebug` license check отключён, но телеметрия может идти |
| 4 | `com.rockchip.devicetest` | Главный factory-инструмент; отключать последним и только с представителем производственной линии |

### Этап 3. Заводская приёмка и калибровочный gate

1. На эталонной приставке прогнать `Factory_Test.bin` (functional), `Aging_Test.bin` (aging), `SN_Test.bin` (ADB/USB slave), `YXTPRODUCTTEST.txt` (ProductTest), `RK_StressTest.txt` (StressTest) — по одному сценарию за прогон.
2. Зафиксировать результаты до и после отключения/удаления. Для `DeviceTest` проверить `RegistTest` с `License.bin` и ответ сервера регистрации.
3. Сравнить SHA-256 калибровочных файлов и значения MAC/serial/HDMI/audio до и после. Если хэши изменились без объяснения — остановить работу.
4. Проверить, не остаётся ли `persist.vendor.ethstate` в состоянии теста; при необходимости сбросить его отдельной контролируемой процедурой.
5. Подписать factory sign-off: кто именно подтверждает, что functional/aging/registration/calibration не зависят от удаляемых APK. Без подписи `DeviceTest` не удаляется.

### Этап 4. Удаление из образа и пересборка

#### 4.1. Если есть исходники

1. Удалить пакеты из `PRODUCT_PACKAGES`, `PRODUCT_COPY_FILES` и prebuilt-модулей в device/vendor makefile.
2. Удалить package-specific записи из `privapp-permissions-*.xml`, `default-permissions`, `sysconfig`.
3. При необходимости убрать скрытые intents из `ExactCalculator`; это не влияет на размер, но убирает битые вызовы.
4. Собрать `systemimage`, `superimage`, `otapackage`; проверить, что формат и компрессия не изменились.
5. Подписать OTA теми же ключами, что и исходная прошивка; проверить AVB/vbmeta, если он включён.

#### 4.2. Если есть только prebuilt-образ

1. Работать только с копией `system.img`; исходный образ и `super.img` сохранить.
2. Смонтировать копию read-write или использовать `debugfs -w`; удалить четыре каталога APK, не трогая соседние библиотеки и `oat`/`vdex` других приложений.
3. Удалить package-specific записи privapp allowlist; проверить, что XML остаётся валидным.
4. Выполнить `e2fsck -f` и `resize2fs -M` (или собрать ext4 заново с теми же features). Проверить `dumpe2fs`: block size, features, UUID, labels, xattrs.
5. Пересобрать `super.img` через `lpmake`, сохранив partition groups, attributes, extents и порядок разделов. Сохранить исходные форматы (ext4 для `system`, sparse для `super`), не переходить на erofs и не включать новую компрессию.
6. Обновлять `parameter.txt`/GPT только если действительно уменьшается `super`-раздел; иначе образ останется того же размера, а экономия будет только внутри раздела.
7. Пересчитать AVB/vbmeta, если он используется; проверить `dm-verity`/fstab. Без этого возможен отказ загрузки.
8. Проверить `ls -Z`/`getfattr`: все оставшиеся файлы должны сохранить `u:object_r:system_file:s0` и другие метки.

Важно: удаление APK из смонтированного образа само по себе **не уменьшает размер firmware**. Нужны rebuild ext4, shrink и/или уменьшение `super`/`parameter.txt`. Если цель — только 55 МиБ контента, а не размера файла, это нужно явно записать в отчёте.

### Этап 5. Регрессионная матрица

| Группа | Проверки | Критерий |
|---|---|---|
| Boot | 10 холодных загрузок, `logcat` без fatal, `boot_progress_*`/bootstat | Нет новых ошибок, время загрузки не хуже baseline более чем на согласованный порог |
| Пользователь | Home/launcher, TV Settings, Wi-Fi/Ethernet/BT, HDMI/CEC, аудио, USB/SD, медиа, установка APK, OTA | Все штатные функции работают |
| Заводская | Functional, aging, registration/license, stress/reboot/recovery | Подписанный factory sign-off; нет изменения калибровочных хэшей |
| Package manager | `pm list packages -U`, `dumpsys package`, `dumpsys activity`, `dumpsys jobscheduler` | Нет удалённых пакетов, orphan jobs, privapp-нарушений |
| Security | `ls -Z`, exported-компоненты, разрешения, сетевые обращения | Нет новых exported-компонентов; тестовые system-UID приложения отсутствуют |
| Rollback | Возврат исходного образа, `pm enable`, factory reset на тестовой приставке | Загрузка и штатные функции восстанавливаются |

### Этап 6. Выпуск и откат

1. Выпустить versioned-отчёт: baseline, список удалённого, размер до/после, хэши, результаты тестов, подпись factory.
2. Хранить исходный образ и `super.img` как официальный rollback; не перезаписывать их.
3. Rollback — reflash исходного образа или `pm enable` для runtime-этапа. Factory reset не использовать как способ отката без отдельного подтверждения.
4. После выпуска контролировать logcat/OTA/license на нескольких приставках; при регрессии вернуть OEM-тесты в factory-вариант.

## 6. Матрица рисков

| Риск | Вероятность | Влияние | Смягчение |
|---|---|---|---|
| Потеря возможности заводской functional-проверки | Высокая для `DeviceTest` | Высокое | Factory sign-off; factory-вариант; удалять `DeviceTest` последним |
| Потеря регистрации/лицензии | Средняя | Высокое | Проверить `RegistTest`/`License.bin` на эталонной приставке; сохранить `ShowDebug`/`DeviceTest` при необходимости |
| Потеря aging/прожига | Средняя | Среднее–высокое | Проверить `Aging_Test.bin`, `RK_StressTest.txt`; factory-вариант |
| Потеря recovery/reboot-тестов | Средняя для `StressTest` | Среднее | Оставить factory-вариант; не удалять recovery-зависимости |
| Изменение калибровочных данных | Низкая по статике, неизвестна по факту | Критическое | Snapshot/hash до/после; сравнение MAC/serial/HDMI/audio; остановка при расхождении |
| `persist.vendor.ethstate` остаётся в тестовом состоянии | Средняя | Низкое–среднее | Проверить и сбросить контролируемо; не путать с калибровкой |
| Broken hidden intents калькулятора | Высокая | Низкое | Убрать/обезвредить ветки `83,991,906` и `000.` в production-сборке |
| Orphan privapp/jobs/receivers | Средняя | Среднее | Удалить allowlist-записи; проверить `dumpsys jobscheduler` и logcat |
| Ошибка AVB/подписи | Средняя | Критическое | Пересчитать vbmeta/OTA, проверить на эталонной приставке; не менять формат/компрессию |
| Образ не стал меньше | Высокая, если только удалить файлы | Высокое для цели 200 МБ | Rebuild ext4, shrink, пересборка super/parameter; измерять фактический размер файла |

## 7. Критерии остановки

Работы прекращаются, если:

- после отключения любого пакета приставка не загружается или появляется fatal в logcat;
- заводской functional/aging/registration сценарий не проходит;
- меняются хэши калибровочных файлов или MAC/serial/HDMI/audio без объяснения;
- `pm disable-user` необратим на тестовой приставке;
- после rebuild не совпадают SELinux-метки, AVB/OTA-подпись или fstab;
- фактический размер образа не уменьшился и цель 55 МиБ не достигнута.

## 8. Ограничения текущего этапа

ADB к приставке недоступен, поэтому runtime-матрица, factory sign-off и калибровочный snapshot ещё не выполнены. План основан на статическом анализе APK и разделов; он не подтверждает, что удаление безопасно для конкретной партии. Перед production-сборкой нужен прогон на эталонной приставке с участием производственной линии и владельца лицензионной/телеметрийной функции.
