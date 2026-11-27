# Сборка bcmdhd для кастомных прошивок BCM4334 на RK3318/RK3328

Эта инструкция описывает сборку Rockchip-варианта `bcmdhd` из этого репозитория для Android-ядра 4.4 и модуля BCM4334/AP6334. Она предназначена для разработки и проверки совместимости, а не для обхода регуляторных ограничений: собранный драйвер не даёт права использовать каналы, мощность или DFS-режимы, запрещённые в вашей юрисдикции. Код страны должен соответствовать стране эксплуатации; `ALL`, чужой country code и отключение ограничений не являются способом «починить» Wi-Fi.

Исходники в `src/` — это срез Rockchip bcmdhd из ветки `develop-4.4`. Для ядра 5.x/6.x он не является готовым портом: API cfg80211, SDIO, GPIO и Android vendor commands там менялись. Если ваша кастомная прошивка использует не 4.4, сначала проверьте, что версия ядра и исходников совпадает.

## 1. Что понадобится

Нужны полный исходный код ядра, соответствующая `.config`, кросс-компилятор, которым собиралось это ядро, и Rockchip platform glue. Одних файлов bcmdhd недостаточно: драйвер вызывает `rkwifi_set_firmware()` и использует заголовок `<linux/rfkill-wlan.h>`; в этом срезе реализация лежит в `src/rk_wifi_config.c`, но она не включена в `DHDOFILES` Makefile. В полноценном Rockchip-ядре эта функция обычно приходит из `rkwifi`/`rfkill-wlan` glue. Если её нет, сборка закончится ошибкой `Unknown symbol rkwifi_set_firmware` или ошибкой на этапе компиляции заголовка.

Минимальный набор:

| Компонент | Требование |
|---|---|
| Ядро | Полный configured tree, а не только `linux-headers`; версия должна совпадать с прошивкой |
| Toolchain | Тот же GCC/Clang и та же версия, что у ядра; иначе возможен `version magic` mismatch |
| Kernel config | `CONFIG_AP6XXX=m` или `=y`, `CONFIG_CFG80211`, `CONFIG_MMC_SDIO`; подробности ниже |
| Rockchip glue | `rfkill-wlan`, `get_wifi_chip_type()`, `rkwifi_set_firmware()` или совместимая замена |
| Firmware | `fw_bcm4334b1_ag.bin` из `fw/` или другой файл, подходящий к вашему chiprev |
| NVRAM | Файл с `boardtype`, `macaddr`, `ccode`; имя может быть любым при явном `nvram_path` |
| CLM | Опционально внешний `bcmdhd_clm.blob`; не путать с NVRAM |
| Упаковка | `bcmdhd.ko` для `/vendor/lib/modules` или `/system/lib/modules`; SELinux context |

Не используйте `CONFIG_BCMDHD=m` как замену `CONFIG_AP6XXX=m`. В этом Makefile модуль подключается строкой `obj-$(CONFIG_AP6XXX) += bcmdhd.o`; `CONFIG_BCMDHD` влияет только на `EXTRA_LDFLAGS` и не создаёт модуль.

## 2. Размещение исходников в дереве ядра

Скопируйте только Rockchip-файлы bcmdhd, а не весь каталог `src/` с Java-файлами, HTML и посторонними исходниками AOSP. В дереве ядра имена должны быть без префикса `rk_`, потому что `rk_Makefile` ожидает `dhd_linux.c`, `dhd_config.c`, `wl_cfg80211.c` и другие имена.

```bash
KERNEL_SRC=/path/to/kernel
B="$KERNEL_SRC/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd"

mkdir -p "$B"
cp src/rk_*.c src/rk_*.h "$B"/
cp src/rk_Makefile "$B/Makefile"

cd "$B"
for f in rk_*.c rk_*.h; do
    [ -e "$f" ] || continue
    mv "$f" "${f#rk_}"
done
```

После копирования проверьте, что в каталоге есть `Makefile`, `dhd_linux.c`, `dhd_config.c`, `dhd_custom_gpio.c`, `wl_cfg80211.c`, `wldev_common.c` и `wlioctl.h`. Файл `wifi_config.c` — платформенная зависимость: не добавляйте его в `DHDOFILES` автоматически, если в вашем ядре уже есть экспортируемая `rkwifi_set_firmware()`.

Если в ядре уже есть другой `bcmdhd`, не перезаписывайте его без резервной копии. Сделайте отдельную ветку ядра и сравните `Makefile`, `dhd_config.c` и `wl_cfg80211.c`: у разных RK3318/RK3328-прошивок отличаются OOB, DTS и Android vendor commands.

## 3. Конфигурация ядра

Включите как минимум следующие символы. Значения `m`/`y` зависят от того, хотите вы модуль или встроенный драйвер.

| Символ | Рекомендуемое значение | Зачем |
|---|---|---|
| `CONFIG_AP6XXX` | `m` для `bcmdhd.ko`; `y` для built-in | Именно этот символ Makefile использует для `bcmdhd.o` |
| `CONFIG_CFG80211` | `y` | Нужен для `wl_cfg80211.c`, nl80211 и Android Wi-Fi HAL |
| `CONFIG_WIRELESS_EXT` | `y`, если ядро 4.4 и нужен WEXT | Добавляет `wl_iw.o`/`wl_escan.o`; на новых ядрах может отсутствовать |
| `CONFIG_MMC` | `y` | SDIO-транспорт |
| `CONFIG_MMC_SDIO` | `y` | SDIO-транспорт |
| `CONFIG_RFKILL` | `y` | Обычно нужен Rockchip `rfkill-wlan` glue; проверьте свой Kconfig |
| `CONFIG_WLAN` | `y` | Общая поддержка WLAN в ядре |

Пример изменения `.config` через `scripts/config`:

```bash
KERNEL_SRC=/path/to/kernel
OUT=/path/to/out

"$KERNEL_SRC/scripts/config" --file "$OUT/.config" \
    -m CONFIG_AP6XXX \
    -e CONFIG_CFG80211 \
    -e CONFIG_WIRELESS_EXT \
    -e CONFIG_MMC \
    -e CONFIG_MMC_SDIO \
    -e CONFIG_RFKILL \
    -e CONFIG_WLAN

make -C "$KERNEL_SRC" O="$OUT" ARCH=arm64 olddefconfig
```

Не добавляйте `CONFIG_BCMDHD_FW_PATH`, `CONFIG_BCMDHD_NVRAM_PATH` и `CONFIG_BCMDHD_CLM_PATH` в `.config`: это C-макросы, а не Kconfig-символы. Для модуля их можно не задавать вообще, если вы передаёте пути через `insmod`. Для built-in драйвера они задаются через `ccflags-y` или `-D` в Makefile.

## 4. Настройка Makefile под плату

В `Makefile` уже выставлены значения для типового Rockchip-варианта:

| Переменная | В этом срезе | Что означает |
|---|---|---|
| `CONFIG_BCMDHD_SDIO` | `y` | SDIO-транспорт; для BCM4334 оставьте включённым |
| `CONFIG_BCMDHD_OOB` | `y` | Out-of-band interrupt; отключите, если на плате in-band SDIO IRQ |
| `CONFIG_BCMDHD_AG` | `y` | `BAND_AG`, двухдиапазонный режим; для BCM4334 обычно нужен |
| `CONFIG_MACH_PLATFORM` | `y` | Добавляет `dhd_gpio.o`; типично для Rockchip |
| `CONFIG_BCMDHD_DTS` | закомментировано | Включайте, если board data берётся из DTS; иначе будут `CUSTOMER_HW` и `DHD_OF_SUPPORT` |
| `CONFIG_VTS_SUPPORT` | `y` | Android vendor commands: GSCAN, RTT, debugability и др. |
| `CONFIG_BCMDHD_PROPTXSTATUS` | `y` | Prop TX status для Android |
| `CONFIG_DHD_USE_STATIC_BUF` | закомментировано | Не включайте без необходимости в статических буферах |

Если плата использует in-band прерывание, замените `CONFIG_BCMDHD_OOB := y` на `CONFIG_BCMDHD_OOB := n` или закомментируйте строку. Если используется DTS, раскомментируйте `CONFIG_BCMDHD_DTS := y`; если board data приходит из platform data, оставьте `CONFIG_MACH_PLATFORM := y` и `CONFIG_BCMDHD_DTS` выключенным.

Не включайте одновременно SDIO, PCIe и USB-ветки: BCM4334 в этом сценарии — SDIO-чип. Лишние транспорты увеличивают число зависимостей и могут дать ошибки на платформах без соответствующих ресурсов.

## 5. Кросс-компиляция

Подставьте свой toolchain и имя defconfig. `rockchip_defconfig` — только пример: у конкретного ядра может быть `rk3328_defconfig`, `rk3318_defconfig` или vendor-имя.

```bash
export ARCH=arm64
export CROSS_COMPILE=/path/to/toolchain/bin/aarch64-linux-android-

KERNEL_SRC=/path/to/kernel
OUT=/path/to/out
B="$KERNEL_SRC/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd"

make -C "$KERNEL_SRC" O="$OUT" ARCH=arm64 \
    CROSS_COMPILE="$CROSS_COMPILE" rockchip_defconfig

make -C "$KERNEL_SRC" O="$OUT" ARCH=arm64 \
    CROSS_COMPILE="$CROSS_COMPILE" olddefconfig

make -C "$KERNEL_SRC" O="$OUT" ARCH=arm64 \
    CROSS_COMPILE="$CROSS_COMPILE" -j"$(nproc)"

make -C "$KERNEL_SRC" O="$OUT" ARCH=arm64 \
    CROSS_COMPILE="$CROSS_COMPILE" modules_prepare

make -C "$KERNEL_SRC" O="$OUT" ARCH=arm64 \
    CROSS_COMPILE="$CROSS_COMPILE" M="$B" modules
```

Готовый файл ищите командой `find "$OUT" "$B" -name bcmdhd.ko -print`: при in-tree сборке с `O=` он обычно лежит в `$OUT/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/`, при external-module сборке — в каталоге модуля. Проверьте артефакт:

```bash
find "$OUT" -name bcmdhd.ko -print
modinfo "$OUT/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/bcmdhd.ko" | \
    grep -E 'filename|vermagic|depends'
file "$OUT/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/bcmdhd.ko"
```

`vermagic` должен соответствовать ядру на устройстве. Если ядро собрано Clang, используйте те же `CC=clang`/`LLVM=1`, что и при сборке ядра; смешивание GCC и Clang часто даёт несовместимый модуль.

## 6. Firmware, NVRAM, CLM и config

`bcmdhd` не хранит country code в модуле. Он получает firmware/NVRAM/CLM из файлов и применяет код, который пришёл от Android или из NVRAM/config. Для BCM4334 в `dhd_config.c` есть отображение `BCM4334_CHIP_ID`/chiprev 3 на имя `bcm4334b1_ag`, но в этом Makefile не определён `FW_PATH_AUTO_SELECT`; поэтому `dhd_conf_set_fw_name_by_chip()` не переименовывает путь автоматически, а только подставляет `CONFIG_BCMDHD_FW_PATH`. Значит, `firmware_path` нужно задать явно.

В `rkwifi_set_firmware()` из этого среза есть ветки для `WIFI_AP6234` и `WIFI_AP6441`, которые указывают на `fw_bcm43341b0_ag.bin`, и нет отдельной ветки `WIFI_AP6334`. Поэтому для BCM4334 надёжнее задать пути явно, а не надеяться на автоматический выбор по имени модуля. Проверьте фактический chip ID по SDIO и `dmesg`.

| Артефакт | Пример пути | Параметр загрузки | Примечание |
|---|---|---|---|
| `fw_bcm4334b1_ag.bin` | `/vendor/etc/firmware/` | `firmware_path=` | Файл из `fw/`; подходит для BCM4334B1/chiprev 3 |
| `nvram_AP6334.txt` | `/vendor/etc/firmware/` | `nvram_path=` | Имя произвольное; в срезе нет автобренча AP6334 |
| `bcmdhd_clm.blob` | `/vendor/etc/wifi/` или `/system/etc/wifi/` | `clm_path=` | Внешний CLM; не NVRAM и не firmware |
| `config.txt` | `/vendor/etc/wifi/` | `config_path=` | Опционально: `country_list`, `ccode`, `regrev` |

`rkwifi_set_firmware()` сначала проверяет каталог `/vendor/etc/firmware/`; если его нет, использует `/system/etc/firmware/`. Это не гарантирует, что файл с нужным именем существует: путь должен указывать на реальный файл.

Для внешнего CLM в `dhd_common.c` используется `clm_path`, а при его отсутствии — `CONFIG_BCMDHD_CLM_PATH`, по умолчанию `/system/etc/wifi/bcmdhd_clm.blob`. Если внешний CLM не нужен, не задавайте `clm_path`; драйвер проверит CLM внутри firmware. Если задаёте путь, файл должен быть валидным CLM-блобом, а не переименованным NVRAM.

Пример загрузки модуля с явными параметрами:

```bash
insmod /vendor/lib/modules/bcmdhd.ko \
    firmware_path=/vendor/etc/firmware/fw_bcm4334b1_ag.bin \
    nvram_path=/vendor/etc/firmware/nvram_AP6334.txt \
    clm_path=/vendor/etc/wifi/bcmdhd_clm.blob \
    config_path=/vendor/etc/wifi/config.txt
```

Если внешнего CLM или config нет, уберите соответствующие строки. Не передавайте `country_code=` как модульный параметр: в этом срезе такого параметра нет; код страны приходит через Android/WEXT/cfg80211, а NVRAM/config задают начальное значение.

Для built-in драйвера параметры `insmod` недоступны. Тогда задайте пути при компиляции, добавив в `Makefile` перед `EXTRA_CFLAGS`:

```make
ccflags-y += -DCONFIG_BCMDHD_FW_PATH=\"/vendor/etc/firmware/fw_bcm4334b1_ag.bin\"
ccflags-y += -DCONFIG_BCMDHD_NVRAM_PATH=\"/vendor/etc/firmware/nvram_AP6334.txt\"
ccflags-y += -DCONFIG_BCMDHD_CLM_PATH=\"/vendor/etc/wifi/bcmdhd_clm.blob\"
```

Кавычки должны попасть в C-строку; проверьте результат через `strings bcmdhd.ko | grep fw_bcm4334` или `strings vmlinux | grep fw_bcm4334`.

## 7. Установка в Android-прошивку

Для теста можно загрузить модуль вручную. Для постоянной интеграции добавьте `bcmdhd.ko` в образ и опишите загрузку в init или `modprobe.d` вашей Android-версии. Точный путь зависит от того, использует прошивка `/vendor/lib/modules` или `/system/lib/modules`.

```bash
adb root
adb remount
adb push bcmdhd.ko /vendor/lib/modules/bcmdhd.ko
adb shell chmod 644 /vendor/lib/modules/bcmdhd.ko
adb shell restorecon /vendor/lib/modules/bcmdhd.ko
adb shell insmod /vendor/lib/modules/bcmdhd.ko \
    firmware_path=/vendor/etc/firmware/fw_bcm4334b1_ag.bin \
    nvram_path=/vendor/etc/firmware/nvram_AP6334.txt
```

Для init-скрипта шаблон выглядит так:

```text
on early-boot
    insmod /vendor/lib/modules/bcmdhd.ko firmware_path=/vendor/etc/firmware/fw_bcm4334b1_ag.bin nvram_path=/vendor/etc/firmware/nvram_AP6334.txt
```

Проверьте SELinux context для `.ko`, firmware, NVRAM и CLM. На Android 8+ файлы в `/vendor` обычно должны иметь vendor-контексты; `restorecon` помогает только если политика уже описывает эти пути. Если ядро требует подпись модулей, `insmod` вернёт `Required key not available`; подпишите модуль ключом ядра или используйте тестовую сборку с разрешённой подписью, но не отключайте защиту на production-устройстве.

## 8. Проверка после загрузки

Сначала убедитесь, что модуль загрузился и получил пути:

```bash
lsmod | grep bcmdhd
cat /sys/module/bcmdhd/parameters/firmware_path
cat /sys/module/bcmdhd/parameters/nvram_path
cat /sys/module/bcmdhd/parameters/clm_path
dmesg | grep -iE 'bcmdhd|dhd|firmware|nvram|CLM|country|SDIO'
```

Затем проверьте фактический regulatory-режим:

```bash
wl country
wl country list
wl channels
dumpsys wifi | grep -iE 'country|mDriverCountryCode|mTelephonyCountryCode'
```

`wl country` без аргумента — чтение текущего кода; `wl country list` — список, который декларирует firmware; `wl channels` — эффективные каналы и мощность. Не используйте `wl country <XX>` для «проверки»: это уже запись. Если `wl` нет, используйте `iw reg get`, `iw phy` и `dumpsys wifi`, но помните, что bcmdhd не обязан поддерживать nl80211 regulatory.

## 9. Типичные ошибки

| Симптом | Вероятная причина | Что проверить |
|---|---|---|
| `firmware path is null` | Не задан `firmware_path` и не определён `CONFIG_BCMDHD_FW_PATH`; `FW_PATH_AUTO_SELECT` в этом Makefile не включён | `dmesg`, `insmod` параметры, `strings bcmdhd.ko` |
| `nvram path is null` | Не задан `nvram_path` | Тот же путь, что для firmware |
| `Unknown symbol rkwifi_set_firmware` | Нет Rockchip `rfkill-wlan`/`wifi_config` glue | Соберите glue или добавьте совместимую реализацию |
| `version magic ... should be` | Модуль собран другим ядром/тулчейном | `uname -r`, `.config`, `CROSS_COMPILE`, `localversion` |
| `Invalid module format` | Несовместимый ABI или подпись | `modinfo`, `CONFIG_MODULE_SIG_FORCE`, архитектура |
| `This FW is not included CLM data` | Firmware без CLM, а внешний CLM не загружен | `clm_path`, `dhd_apply_default_clm`, файл CLM |
| `set country for ... failed` | Код не принят firmware/CLM | `wl country list`, NVRAM `ccode`, config `country_list` |
| `SDIO card not detected` | Неверные DTS/OOB/GPIO/regulator | Схема платы, `dmesg` SDIO, `CONFIG_BCMDHD_OOB` |
| `cfg80211` errors | Не включён или не загружен cfg80211 | `CONFIG_CFG80211`, порядок `modprobe` |

## 10. Минимальный чек-лист

1. Версия ядра и исходников bcmdhd совпадает; для 4.4 используется этот срез, для 5.x/6.x нужен порт.
2. `CONFIG_AP6XXX=m`, `CONFIG_CFG80211=y`, `CONFIG_MMC_SDIO=y`; `CONFIG_BCMDHD` не заменяет `CONFIG_AP6XXX`.
3. `CONFIG_BCMDHD_OOB` соответствует схеме прерывания, `CONFIG_BCMDHD_DTS` — способу передачи board data.
4. `bcmdhd.ko` собран тем же toolchain и имеет правильный `vermagic`.
5. `firmware_path` указывает на `fw_bcm4334b1_ag.bin`, `nvram_path` — на реальный NVRAM, `clm_path` — только на валидный CLM.
6. После загрузки `wl country list` непустой, `wl channels` соответствует ожидаемому режиму, `dmesg` не содержит `set country ... failed`.
7. Country code соответствует юрисдикции; `ALL` и чужие коды не используются для обхода ограничений.

## Ссылки внутри репозитория

- `src/rk_Makefile` — флаги сборки, `CONFIG_AP6XXX`, `CONFIG_BCMDHD_SDIO/OOB/AG`, `DHDCFLAGS`.
- `src/rk_dhd_linux.c` — `firmware_path`, `nvram_path`, `clm_path`, `config_path`, выбор пути и загрузка firmware.
- `src/rk_dhd_config.c` — `chip_name_map`, `BCM4334_CHIP_ID`/chiprev 3 → `bcm4334b1_ag`, `dhd_conf_set_fw_name_by_chip`, `dhd_conf_fix_country`.
- `src/rk_dhd_common.c` — `dhd_apply_default_clm`, `dhd_check_current_clm_data`, проверка `#n`.
- `src/rk_wifi_config.c` — Rockchip `rkwifi_set_firmware()`, пути `/vendor/etc/firmware/` и `/system/etc/firmware/`.
- `src/rk_wl_cfg80211.c` — cfg80211 reg notifier и vendor registration.
- `BCM4334_regulatory_report_ru.md` — какие country codes декларируют публичные CLM 1.6.5/1.21.1 и как проверить устройство.
