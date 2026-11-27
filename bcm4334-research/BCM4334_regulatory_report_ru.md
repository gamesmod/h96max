# BCM4334 + Android bcmdhd на H96 Max RK3318/RK3328: проверяемые regulatory-данные

Дата среза: **2026-11-27**. Объект: комбо-чип **BCM4334/AP6334** и Android-драйвер **bcmdhd**. H96 Max RK3318/RK3328 рассматривается как семейство устройств, а не как гарантия установленного чипа: в разных ревизиях встречаются разные Wi-Fi-модули, поэтому наличие BCM4334 нужно доказывать по живому устройству.

Инструкция по сборке драйвера для кастомных прошивок: [BUILDING_BCMDHD_RU.md](BUILDING_BCMDHD_RU.md).

BCM4334 — двухдиапазонный чип IEEE 802.11a/b/g/n с Bluetooth 4.0 + HS и FM-приёмником; он рассчитан на внешние 2,4/5 ГГц фронт-энды и интерфейсы SDIO v2.0/gSPI или HSIC [1]. Для regulatory-режима важны три независимых слоя: встроенная в firmware таблица CLM, значения `ccode`/`regrev` в NVRAM и команда, которую Android передаёт через HAL/driver. Ни один из этих слоёв по отдельности не доказывает, какой режим реально работает.

Country code — это не сам regulatory domain, а ключ выбора записи CLM; итоговый domain складывается из набора каналов, DFS, passive/active scan и EIRP, которые получаются после применения firmware, NVRAM и driver. AP6334 — обозначение модуля на BCM4334, а не отдельный чип; в отчёте BCM4334 и AP6334 рассматриваются как одно семейство, но проверка SDIO ID на устройстве обязательна.

Главный проверяемый вывод: в трёх публичных сборках прошивки BCM4334 с CLM 1.6.5 встроенная таблица содержит **49 двухбуквенных country codes плюс региональный код EU/0**; в более новой публичной сборке с CLM 1.21.1 добавляется **BR/4**, то есть 50 country codes плюс EU/0. Кодов `CN`, `HK`, `IN`, `ID`, `MY`, `PH`, `TH`, `VN`, `NZ`, `ZA`, `SA`, `IL`, `EG` в этих CLM-таблицах нет. Это не приговор конкретному экземпляру H96 Max: прошивка на устройстве может быть другой, а firmware может иметь fallback на «world»-режим. Поэтому все выводы ниже, зависящие от конкретной сборки firmware/NVRAM/driver, помечены как **неподтверждённые до live-проверки**.

Ни одна строка ниже не является рекомендацией сменить country code на чужой, включить `ALL` или снять ограничения каналов/мощности. Таблица — инвентаризация того, что декларируют конкретные публичные артефакты. Выборка не исчерпывающая: проверены три независимых публичных источника firmware (Radxa, Armbian, Samsung); официального публичного релиза Rockchip в момент среза не найдено, поэтому возможны другие OEM-сборки с иным CLM.

## Как Android штатно задаёт regulatory domain

Штатный путь Android не читает NVRAM `ccode` напрямую. Framework выбирает country code из нескольких источников и передаёт его в Wi-Fi HAL; HAL или supplicant доводят код до драйвера и firmware. В актуальном AOSP `WifiCountryCode` источниками служат: override для тестов, `TelephonyManager` (страна сети/SIM), country code, полученный драйвером через 802.11d, code, выведенный из scan results, и значение по умолчанию из `WifiSettingsConfigStore` [7]. Порядок выбора в `pickCountryCode` такой: override, затем telephony, затем driver country code, затем framework scan-derived code, затем default из config store [7]. Отдельно есть OEM-значение из system property `ro.boot.wificountrycode`; оно участвует как default, а не как абсолютный приоритет [7].

В Java-коде `WifiCountryCode.isValid` проверяет только длину 2 и буквенно-цифровой состав, а не то, что код входит в ISO 3166-1 или поддерживается firmware [7]. Поэтому Android может отправить `HK`, `CN` или иной двухбуквенный код, даже если CLM-таблица прошивки его не содержит. Ошибка проявится не в Java-проверке, а ниже: в HAL, supplicant или драйвере, часто только в логах.

Начиная с HAL 1.5, country code задаётся на уровне чипа через `IWifiChip.setCountryCode`: HIDL-контракт принимает `int8_t[2]`, AIDL-контракт — `byte[2]`; оба описывают код как двухбайтовый код ISO 3166 и как глобальную настройку чипа, а не отдельного STA/AP-интерфейса [9] [10]. Старый путь `WifiNative.setCountryCode` в ветке AOSP до появления chip-level API отправлял supplicant-команду `DRIVER COUNTRY <XX>` [8]. В Broadcom legacy HAL `wifi_set_country_code` формирует nl80211 vendor-команду `GOOGLE_OUI`/`WIFI_SUBCMD_SET_COUNTRY_CODE` с атрибутом `ANDR_WIFI_ATTRIBUTE_COUNTRY` [11]. Поддерживает ли конкретная сборка Rockchip bcmdhd эту vendor-команду, по имеющимся исходникам однозначно не доказано: в скачанных файлах bcmdhd обработчик этой команды не найден, а `wl_cfg80211.c` лишь регистрирует `wl_cfgvendor_attach` [5]. Это **неподтверждённая часть пути** для H96 Max.

Если Android работает через cfg80211-совместимый путь bcmdhd, `wl_cfg80211_reg_notifier` принимает `NL80211_REGDOM_SET_BY_USER` и `NL80211_REGDOM_SET_BY_COUNTRY_IE` и вызывает `wldev_set_country`; остальные инициаторы логируются как неподдерживаемые и оставляют platform default [5]. Этот notifier собран только при `CONFIG_CFG80211_INTERNAL_REGDB`; без этого флага поведение определяется другими путями драйвера. Поэтому один и тот же Android-код на разных сборках bcmdhd может идти разными маршрутами.

Практически важно, что Android после установки хранит **два разных значения**: `mDriverCountryCode` — то, что реально сообщает драйвер, и `mTelephonyCountryCode`/default — то, что framework хотел установить. `dumpsys wifi` печатает оба; расхождение между ними и есть первый признак того, что firmware не приняла запрошенный код [7]. Android также допускает `00` как служебное значение «world mode»: `setOverrideCountryCode("00")` подменяет его на `config_wifiDriverWorldModeCountryCode` [7]. Это не country code и не замена национальному коду.

Флаги `config_wifi_revert_country_code_on_cellular_loss` и `config_wifiDriverSupportedNl80211RegChangedEvent` управляют откатом при потере сотовой сети и подпиской на события драйвера; их значения тоже нужно фиксировать в `dumpsys`, потому что они меняют поведение при смене SIM/сети [7].

## Что декларирует firmware BCM4334

Публичные бинарные файлы BCM4334 содержат секцию `CLM DATA` и строку `ClmImport`. Внутри, непосредственно перед `CLM DATA`, лежит таблица записей вида `[два ASCII-символа][один байт ревизии]`; например, последовательность `41 45 01` означает `AE/1`. Это не догадка о «похожих строках»: таблица извлекается из файла по фиксированному смещению и проверяется побайтово. Для трёх независимых публичных сборок получились следующие результаты:

| Прошивка | Размер | `CLM DATA` | `ClmImport` | Записей в таблице | Country codes |
|---|---:|---:|---|---:|---:|
| Rockchip/Radxa `fw_bcm4334b1_ag.bin` [16] | 457 249 | offset 403268 | 1.6.5 | 50 | 49 + EU/0 |
| Rockchip `fw_bcm4334b1_ag_apsta.bin` [16] | 413 342 | offset 359380 | 1.6.5 | 50 | 49 + EU/0 |
| Armbian `brcmfmac4334-sdio.bin` [17] | 398 010 | offset 345868 | 1.21.1 | 51 | 50 + EU/0 |
| Samsung `bcmdhd_sta.bin_b2` [18] | 445 072 | offset 391308 | 1.6.5 | 50 | 49 + EU/0 |
| Samsung `bcmdhd_apsta.bin_b2` [18] | 399 080 | offset 345880 | 1.6.5 | 50 | 49 + EU/0 |

Точный список для CLM 1.6.5 (Rockchip/Radxa и Samsung) выглядит так:

`EU/0, AE/1, AR/1, AT/1, AU/2, BE/1, BG/1, BN/1, CA/2, CH/1, CY/1, CZ/1, DE/3, DK/1, EE/1, ES/1, FI/1, FR/1, GB/1, GR/1, HR/1, HU/1, IE/1, IS/1, IT/1, JP/5, KR/24, KW/1, LI/1, LT/1, LU/1, LV/1, MA/1, MT/1, MX/1, NL/1, NO/1, PL/1, PT/1, PY/1, RO/1, RU/5, SE/1, SG/4, SI/1, SK/1, TR/7, TW/2, UA/255, US/46`.

В CLM 1.21.1 добавляется `BR/4`; остальные записи совпадают [17]. Это означает, что утверждение «BCM4334 поддерживает такой-то код» без указания версии CLM неполно. Samsung mfg-прошивка `bcmdhd_mfg.bin_b2` полной таблицы не показала: в её окне перед `CLM DATA` найдена только запись `US/0`, а не 50 стран [18]. Это отдельный пример того, что manufacturing-сборка может быть урезана.

Официального публичного репозитория `rkwifibt` от Rockchip на момент среза не существовало; Radxa и Armbian — публичные зеркала, Samsung — независимый вендорский набор. Это выборка, а не полный каталог всех прошивок BCM4334.

В срезе `linux-firmware` на `git.kernel.org` файл `brcm/brcmfmac4334-sdio.bin` отсутствовал, а `brcm/brcmfmac4334-sdio.txt` не отдавался как NVRAM [34]. Варианты `fw_bcm43341b0_ag.bin` для BCM43341B0 и `brcmfmac43342-sdio.bin` для BCM43342 в этой работе не разобраны; их CLM-таблицы могут отличаться, поэтому выводы таблицы 1 к ним не применяются.

**Статус всех строк таблицы 1: код декларирован конкретным firmware; фактическое принятие и эффективные каналы на конкретном H96 Max — неподтверждены до live-проверки.** Если на устройстве другая прошивка, другой CLM или другой `ccode`, набор может отличаться. Проверка на живом устройстве обязательна.

## Таблица 1: коды, явно перечисленные в CLM-таблицах BCM4334

В столбце «Источник» указаны ревизия CLM и драйверная таблица. Ревизия — это байт, который firmware хранит рядом с кодом; он не равен автоматически `regrev` из NVRAM. В столбце «Ограничения» указана регуляторная база, а не разрешение на конкретные каналы или мощность: точные каналы, DFS и EIRP определяются CLM-записью, NVRAM, драйвером и национальным регулятором.

| Код | Источник и ревизия | Условия поддержки | Ограничения и регуляторная база |
|---|---|---|---|
| EU | CLM 1.6.5/1.21.1: `EU/0` [16] [17]; драйверная таблица по умолчанию `EU` не содержит, только `EXAMPLE_TABLE` [3] | Нужна прошивка с записью EU/0; код должен дойти до firmware без подмены NVRAM/Android | Это регион, не страна ISO 3166-1 [28]; ETSI EN 300 328 и EN 301 893 [26] [27]; не является «обходным» кодом |
| AE | CLM 1.6.5/1.21.1: `AE/1` [16] [17]; драйвер: `AE/1` [3] | Прошивка с CLM 1.6.5+; код передан драйвером; NVRAM не переопределяет | ОАЭ, TDRA; 2,4/5 ГГц; точные 5 ГГц-каналы и DFS — по национальному плану |
| AR | CLM: `AR/1` [16] [17]; драйвер: `AR/1` [3] | То же | Аргентина, ENACOM; 2,4/5 ГГц; национальный план |
| AT | CLM: `AT/1` [16] [17]; драйвер: `AT/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц 36–64 и 100–140 с DFS; австрийские ограничения |
| AU | CLM: `AU/2` [16] [17]; драйвер: `AU/2` [3] | То же | Австралия, ACMA; 2,4 ГГц 1–13; 5 ГГц 5150–5350, 5470–5725, 5725–5850; DFS |
| BE | CLM: `BE/1` [16] [17]; драйвер: `BE/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS; бельгийские ограничения |
| BG | CLM: `BG/1` [16] [17]; драйвер: `BG/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| BN | CLM: `BN/1` [16] [17]; драйвер: `BN/1` [3] | То же | Бруней, AITI; 2,4/5 ГГц; национальный план |
| CA | CLM: `CA/2` [16] [17]; драйвер: `CA/2` [3] | То же | Канада, ISED; 2,4 ГГц 1–11; 5 ГГц 36–64, 100–144, 149–165; DFS |
| CH | CLM: `CH/1` [16] [17]; драйвер: `CH/1` [3] | То же | Швейцария, BAKOM; ETSI-совместимый план; 2,4/5 ГГц |
| CY | CLM: `CY/1` [16] [17]; драйвер: `CY/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| CZ | CLM: `CZ/1` [16] [17]; драйвер: `CZ/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| DE | CLM: `DE/3` [16] [17]; драйвер: `DE/3` [3] | То же; ревизия 3 отличается от соседних ЕС-стран | Германия, BNetzA; ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| DK | CLM: `DK/1` [16] [17]; драйвер: `DK/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| EE | CLM: `EE/1` [16] [17]; драйвер: `EE/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| ES | CLM: `ES/1` [16] [17]; драйвер: `ES/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| FI | CLM: `FI/1` [16] [17]; драйвер: `FI/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| FR | CLM: `FR/1` [16] [17]; драйвер: `FR/1` [3] | То же | Франция, ANFR; ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS; национальные ограничения на некоторые поддиапазоны |
| GB | CLM: `GB/1` [16] [17]; драйвер: `GB/1` [3] | То же | Великобритания, Ofcom; ETSI-совместимый план; 2,4 ГГц 1–13; 5 ГГц с DFS |
| GR | CLM: `GR/1` [16] [17]; драйвер: `GR/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| HR | CLM: `HR/1` [16] [17]; драйвер: `HR/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| HU | CLM: `HU/1` [16] [17]; драйвер: `HU/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| IE | CLM: `IE/1` [16] [17]; драйвер: `IE/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| IS | CLM: `IS/1` [16] [17]; драйвер: `IS/1` [3] | То же | Исландия, ETSI-совместимый план; 2,4/5 ГГц |
| IT | CLM: `IT/1` [16] [17]; драйвер: `IT/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| JP | CLM: `JP/5` [16] [17]; драйвер: `JP/8` [3] — расхождение ревизий | Нужна именно японская CLM-запись; driver table может передать другую ревизию | Япония, MIC; 2,4 ГГц 1–14, причём канал 14 — только 802.11b; 5 ГГц 36–64 и 100–140; indoor/DFS |
| KR | CLM: `KR/24` [16] [17]; драйвер: `KR/24` [3] | То же | Корея, KC; 2,4 ГГц 1–13; 5 ГГц 36–64, 100–140, 149–161; DFS |
| KW | CLM: `KW/1` [16] [17]; драйвер: `KW/1` [3] | То же | Кувейт, CITRA; 2,4/5 ГГц; национальный план |
| LI | CLM: `LI/1` [16] [17]; драйвер: `LI/1` [3] | То же | Лихтенштейн, ETSI-совместимый план; 2,4/5 ГГц |
| LT | CLM: `LT/1` [16] [17]; драйвер: `LT/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| LU | CLM: `LU/1` [16] [17]; драйвер: `LU/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| LV | CLM: `LV/1` [16] [17]; драйвер: `LV/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| MA | CLM: `MA/1` [16] [17]; драйвер: `MA/1` [3] | То же | Марокко, ANRT; 2,4/5 ГГц; национальный план |
| MT | CLM: `MT/1` [16] [17]; драйвер: `MT/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| MX | CLM: `MX/1` [16] [17]; драйвер: `MX/1` [3] | То же | Мексика, IFT; 2,4 ГГц 1–11; 5 ГГц 36–64, 100–144, 149–165; DFS |
| NL | CLM: `NL/1` [16] [17]; драйвер: `NL/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| NO | CLM: `NO/1` [16] [17]; драйвер: `NO/1` [3] | То же | Норвегия, Nkom; ETSI-совместимый план; 2,4/5 ГГц |
| PL | CLM: `PL/1` [16] [17]; драйвер: `PL/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| PT | CLM: `PT/1` [16] [17]; драйвер: `PT/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| PY | CLM: `PY/1` [16] [17]; драйвер: `PY/1` [3] | То же | Парагвай, CONATEL; 2,4/5 ГГц; национальный план |
| RO | CLM: `RO/1` [16] [17]; драйвер: `RO/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| RU | CLM: `RU/5` [16] [17]; драйвер: `RU/1` только под `BCM4330_CHIP` [3] | Нужна CLM-запись RU/5; драйверная таблица для BCM4334 может не содержать RU | Россия, Роскомнадзор; 2,4 ГГц 1–13; 5 ГГц 5150–5350 и 5470–5850; гармонизация 5 ГГц неполная |
| SE | CLM: `SE/1` [16] [17]; драйвер: `SE/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| SG | CLM: `SG/4` [16] [17]; драйвер: `SG/4` [3] | То же | Сингапур, IMDA; 2,4 ГГц 1–13; 5 ГГц 36–64, 100–144, 149–165 |
| SI | CLM: `SI/1` [16] [17]; драйвер: `SI/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| SK | CLM: `SK/1` [16] [17]; драйвер: `SK/1` [3] | То же | ЕС/ETSI; 2,4 ГГц 1–13; 5 ГГц с DFS |
| TR | CLM: `TR/7` [16] [17]; драйвер: `TR/7` [3] | То же | Турция, BTK; 2,4/5 ГГц; национальный план |
| TW | CLM: `TW/2` [16] [17]; драйвер: `TW/1` [3] — расхождение ревизий | Нужна CLM-запись TW/2; driver table может передать TW/1 | Тайвань, NCC; 2,4 ГГц 1–11; 5 ГГц 36–64 и 100–140; 5,8 ГГц — по национальному плану |
| UA | CLM: `UA/255` [16] [17]; драйверная таблица по умолчанию `UA` не содержит [3] | Ревизия 255 — специальная; нельзя трактовать как обычную rev 1; фактическое поведение неподтверждено | Украина, НКРЗИ; 2,4/5 ГГц; точные каналы и DFS — по национальному плану |
| US | CLM: `US/46` [16] [17]; драйвер: `US/5` только под `BCM4330_CHIP` [3] | Нужна CLM-запись US/46; драйверная таблица для BCM4334 может не содержать US | США, FCC 47 CFR 15.247/15.407 [24] [25]; 2,4 ГГц 1–11; 5 ГГц 36–64, 100–144, 149–165; DFS |
| BR | Только CLM 1.21.1: `BR/4` [17]; в CLM 1.6.5 отсутствует [16]; драйверная таблица `BR` не содержит [3] | Поддержка зависит от версии firmware; на старой CLM 1.6.5 код не декларирован | Бразилия, ANATEL; 2,4 ГГц 1–13; 5 ГГц 36–64, 100–144, 149–165; DFS; **неподтверждено для конкретной прошивки** |

### Что из таблицы следует

Во-первых, набор кодов не совпадает с полным списком ISO 3166-1: в CLM 1.6.5 нет `CN`, `HK`, `IN`, `ID`, `MY`, `PH`, `TH`, `VN`, `NZ`, `ZA`, `SA`, `IL`, `EG`, а `EU` вообще не является страной [28]. Во-вторых, ревизии в firmware и в драйверной таблице иногда расходятся: `JP/5` в CLM против `JP/8` в драйвере, `TW/2` против `TW/1`, `RU/5` против `RU/1` только под BCM4330, `US/46` против `US/5` только под BCM4330 [3] [16]. Это значит, что даже при совпадении двухбуквенного кода итоговая regulatory-таблица может отличаться: код — не единственный ключ, важна ревизия.

В-третьих, `BR` появился только в CLM 1.21.1 [17]. Поэтому вопрос «поддерживает ли BCM4334 Бразилию» некорректен без вопроса «какая прошивка и какой CLM». То же относится к любой стране: публичная сборка firmware — это конкретный бинарный артефакт, а не вечная характеристика чипа.

## Таблица 2: коды и значения вне CLM-таблицы, которые встречаются в NVRAM, драйвере или Android

| Код или значение | Где встречается | Условия и статус | Ограничения |
|---|---|---|---|
| CN | Rockchip bcmdhd `dhd_conf_preinit` ставит `CN/0` для BCM4334 по умолчанию [2] | В CLM 1.6.5 и 1.21.1 запись `CN` не найдена [16] [17]. Это **неподтверждённое противоречие**: driver default может быть отвергнут firmware, заменён fallback или обработан особым образом | Не считать `CN` поддерживаемым только по default драйвера; нужен live `wl country`/`wl country list` |
| HK | Armbian NVRAM для RK3318 `ccode=HK` [13] | В CLM 1.6.5 и 1.21.1 запись `HK` не найдена [16] [17]. NVRAM-значение — только начальная конфигурация, не доказательство поддержки | **Неподтверждено**; возможен fallback/ошибка; проверять boot logs и `wl country` |
| BR | CLM 1.21.1 `BR/4` [17] | Поддерживается только новой сборкой; в CLM 1.6.5 отсутствует [16] | **Firmware-dependent**; на старой прошивке не декларирован |
| IN, ID, MY, PH, TH, VN, NZ, ZA, SA, IL, EG | В CLM 1.6.5/1.21.1 не найдены [16] [17]; при этом Infineon WHD enum содержит сотни кодов, включая эти [31] | WHD — другой драйвер/стек; его enum не доказывает поддержку BCM4334. Для BCM4334 статус — **не подтверждено** | Не переносить список WHD на BCM4334; проверять конкретный CLM и firmware |
| ALL | Samsung mfg NVRAM `ccode=ALL` [15] | Специальное «world»-значение, не country code; в CLM-таблице как страна отсутствует | Не является национальным кодом; не рекомендуется как замена; фактическое поведение **неподтверждено** |
| 00 | Android `WifiCountryCode`: `setOverrideCountryCode("00")` подменяет на `config_wifiDriverWorldModeCountryCode` [7] | Служебное значение world mode, не ISO 3166-1 | Не страна; не использовать как доказательство поддержки каналов |
| XZ | Драйверная таблица: IR, SD, SY, GL, PS, TL, MH -> `XZ/11` [3] | Специальный fallback-код драйвера, не страна | Не считать национальным кодом; фактический CLM-режим **неподтверждён** |
| XY | `EXAMPLE_TABLE` драйвера: KR/AU/CN/TW/AR/MX -> `XY/3` [3] | Пример, не боевая таблица по умолчанию | Не доказательство для конкретной сборки |
| EU | CLM 1.6.5/1.21.1 `EU/0` [16] [17] | Региональный код ЕС, не ISO 3166-1 [28] | Не национальный код; ETSI-база, но национальные различия сохраняются |

Отдельное противоречие: driver table содержит `ID/1` [3], но в разобранных CLM-таблицах записи `ID` нет [16] [17]. Это ещё один пример, почему нельзя выводить поддержку из одного слоя.

## Драйвер bcmdhd: где код может потеряться или подмениться

Rockchip bcmdhd для BCM4334 использует firmware-имя `bcm4334b1_ag` (chip ID BCM4334, chiprev 3) [2]. При инициализации `dhd_conf_preinit` задаёт default `CN/0` для BCM4334; это значение попадает в `conf->cspec` и может быть отправлено в firmware, если NVRAM/config не переопределили его [2]. Далее `dhd_conf_read_config` умеет читать `ccode=` и `regrev=` из config.txt, а `country_list=` позволяет сопоставить Android-код с парой `ccode/regrev` [2]. Это важно: в одной сборке Android-код `US` может быть отображён на `US/46`, в другой — на `US/5` или на `XZ/11`.

`wldev_set_country` работает так: читает текущий `country` iovar, копирует переданный двухбуквенный код в `country_abbrev` и `ccode`, пытается сопоставить его с `country_list`, иначе вызывает `dhd_get_customized_country_code`, затем пишет iovar `country`, вызывает `dhd_conf_fix_country`, повторно читает `country` и логирует результат [4]. Если firmware отвергает код, `wldev_iovar_setbuf` возвращает ошибку, и в логе появляется `set country for ... failed` [4]. Это и есть практическая точка доказательства: не «Android сказал», а «firmware приняла и вернула».

`dhd_conf_fix_country` добавляет ещё один слой: если чтение valid channels не удалось или в 2,4 ГГц оказалось меньше 11 каналов, драйвер пытается применить `country_list`/default, а при неудаче — `US/0` [2]. Это значит, что при «неудобном» коде firmware может не сохранить запрошенный режим, а драйвер может подставить свой default. Конкретное поведение зависит от сборки и **неподтверждено** без логов.

`dhd_apply_default_clm` скачивает CLM-блоб, а `dhd_check_current_clm_data` трактует `#n` в текущем country iovar как отсутствие CLM-данных [6]. Если CLM-блоба нет или он не принят, инициализация может завершиться ошибкой; в этом случае никакой country code сам по себе не восстановит regulatory-таблицу.

`dhd_get_customized_country_code` в Rockchip-версии использует `translate_custom_table`: AE/1, AR/1, AT/1, AU/2, BE/1, BG/1, BN/1, CA/2, CH/1, CY/1, CZ/1, DE/3, DK/1, EE/1, ES/1, FI/1, FR/1, GB/1, GR/1, HR/1, HU/1, IE/1, IS/1, IT/1, ID/1, JP/8, KR/24, KW/1, LI/1, LT/1, LU/1, LV/1, MA/1, MT/1, MX/1, NL/1, NO/1, PL/1, PT/1, PY/1, RO/1, SE/1, SI/1, SK/1, TR/7, TW/1; IR/SD/SY/GL/PS/TL/MH -> XZ/11; RU/1 и US/5 — только под `BCM4330_CHIP` [3]. Для BCM4334-сборки это означает, что `RU` и `US` могут не найтись в драйверной таблице и уйти в firmware «как есть»; что firmware с ними сделает — вопрос к конкретному CLM.

`wl_cfg80211_reg_notifier` принимает только `NL80211_REGDOM_SET_BY_USER` и `NL80211_REGDOM_SET_BY_COUNTRY_IE`; остальные инициаторы не поддерживаются и оставляют platform default [5]. Если H96 Max использует старый WEXT-путь, `wl_iw_handler` вообще не содержит обработчика `SIOCSIWPRIV` для country (в скачанном `wl_iw.c` он `NULL`) [5]. Это ещё одна причина, почему один и тот же Android framework на разных прошивках даёт разный результат.

## NVRAM: что показывают публичные варианты

Публичные NVRAM для BCM4334 различаются не только набором RF-калибровок, но и `ccode`. Armbian-файл для RK3318 содержит `ccode=HK`, `manfid=0x2d0`, `prodid=0x05c1`, `vendid=0x14e4`, `devid=0x4380`, `boardtype=0x05de`, `boardrev=0x1200` [13]. В комментарии сказано, что исходный Android-файл назывался `nvram_HS2734A.txt`, а версия была заменена на файл из Samsung N7100 Murata; это объясняет, почему в RK3318-файле оказался `HK`, а не код, связанный с конкретным рынком [13].

Samsung N7100 production NVRAM содержит `ccode=GB` и те же SDIO/board IDs [14]. `GB` есть в CLM-таблице BCM4334 с ревизией 1 [16] [17], поэтому этот вариант внутренне согласован: NVRAM просит код, который firmware декларирует. Samsung mfg NVRAM содержит `ccode=ALL` [15]; `ALL` — специальное world-значение, а не страна, и в CLM-таблице как страна отсутствует. Это хороший пример того, что manufacturing-файл и production-файл решают разные задачи: первый может быть «world», второй — конкретный рынок.

Armbian-файл с `ccode=HK` — пример **неподтверждённого** варианта: код `HK` в CLM 1.6.5/1.21.1 не найден [16] [17]. Это не означает автоматически, что Wi-Fi не работает: firmware может принять код и применить fallback, драйвер может переопределить его, а Android позже установит другой. Но считать `HK` «поддерживаемым BCM4334» на основании одной строки NVRAM нельзя. Нужны boot logs, `wl country` и `wl channels`.

NVRAM `regrev` в этих трёх файлах не задан; ревизия берётся из CLM-таблицы или из драйверного `country_list`. Если в конкретной H96 Max прошивке есть `regrev=...`, он может изменить выбор regulatory-записи. Поэтому при live-проверке нужно фиксировать не только `ccode`, но и `regrev`, а также хэш NVRAM.

## Bluetooth: отдельный regulatory-контур

BCM4334 поддерживает Bluetooth 4.0 + HS, HCI UART/PCM и AFH; Wi-Fi и Bluetooth используют общую антенну через встроенный iTR-переключатель [1]. В Linux/Android Bluetooth-часть загружает патч-файл `BCM4334B0.hcd` (или совместимый HCD) через `btbcm`/`hci_uart`; в Android это может делать Broadcom `libbt` через vendor library [19] [20] [21]. HCD — это патч firmware/controller configuration, а не regulatory-база: в нём нет country code, аналогичного `WLC_SET_COUNTRY`.

В стандартном Android Bluetooth API нет параметра country code: `BluetoothAdapter`, `BluetoothManager`, `BluetoothGatt` и Bluetooth HAL оперируют включением, сканированием, pairing, профилями и HCI-параметрами, но не национальным планом частот [22] [23]. `dumpsys bluetooth_manager`, `btmgmt info`, `hciconfig -a` показывают адрес, версию HCI, features, class of device, power state, но не «страну». Это структурный вывод; вендорская прошивка может иметь проприетарные параметры, но они не являются стандартным Android country code.

Регуляторно Bluetooth работает в полосе 2,4 ГГц ISM 2400–2483,5 МГц. Технология использует FHSS: 79 каналов по 1 МГц в BR/EDR и 40 каналов по 2 МГц в BLE; AFH исключает занятые участки. Ограничения разных стран касаются EIRP, класса мощности, duty cycle, внеполосных излучений и AFH, а не выбора «страновой» таблицы каналов, как у Wi-Fi 5 ГГц. FCC 15.247 и ETSI EN 300 328 задают базовые требования к 2,4 ГГц ISM/FHSS [24] [26]; Bluetooth SIG Core 4.0 задаёт радио и процедуры, но не национальные планы [29] [30]. Класс мощности — это EIRP и настройка контроллера, а не country code [1].

Практический вывод: Wi-Fi `ccode` и Bluetooth regulatory — разные контуры. Wi-Fi country code меняет CLM-таблицу каналов/мощности; Bluetooth использует общую 2,4 ГГц-полосу, AFH и класс мощности, задаваемые HCD/OTP/RF-калибровкой. Смена Wi-Fi `ccode` не является «настройкой Bluetooth-страны»; она может повлиять только на coexistence-параметры (`btc_params` в NVRAM), но это не regulatory domain. Конкретные `btc_params` в Armbian NVRAM (`btc_params80=0`, `btc_params6=10`, `btc_params95=53`) — параметры сосуществования, а не country code [13].

## Пошаговый read-only план проверки на устройстве

Ниже — только чтение и сбор доказательств. Ни одна команда не меняет country code, каналы, мощность, NVRAM или firmware. Часть команд требует root; без root используйте `adb bugreport`, `dumpsys`, `logcat` и доступные `getprop`. Перед началом зафиксируйте модель, сборку и хэши файлов, чтобы отчёт был воспроизводимым.

1. **Идентифицируйте устройство и Android.** Выполните `getprop ro.product.model`, `getprop ro.product.device`, `getprop ro.build.version.release`, `getprop ro.build.version.sdk`, `getprop ro.build.fingerprint`, `getprop ro.boot.wificountrycode`, `getprop ro.board.platform`, `uname -a`, `cat /proc/version`. Запишите вывод в файл. Это отделяет H96 Max RK3318 от RK3328 и фиксирует OEM-default country code.

2. **Докажите наличие именно BCM4334/AP6334.** Проверьте `dmesg | grep -iE 'bcm|brcm|4334|bcmdhd|sdio|wifi'`, `ls -l /sys/bus/sdio/devices/`, `cat /sys/bus/sdio/devices/*/uevent`, `lsmod | grep bcmdhd`, `modinfo bcmdhd` и `cat /sys/module/bcmdhd/parameters/*` в доступной части. Для BCM4334 ориентиры из NVRAM: `vendid=0x14e4`, `devid=0x4380`, `manfid=0x2d0`, `prodid=0x05c1`, `boardtype=0x05de` [13] [14]. Модель H96 Max сама по себе не доказывает BCM4334: в семействе могли быть другие модули.

3. **Зафиксируйте firmware, NVRAM и config.** Выполните `find /vendor /system -iname '*4334*' -o -iname '*AP6334*' -o -iname '*bcmdhd*' 2>/dev/null`, `ls -l /vendor/etc/firmware /system/etc/firmware /system/etc/wifi 2>/dev/null`, `cat /sys/module/bcmdhd/parameters/firmware_path`, `nvram_path`, `config_path`, `clm_path`. Сделайте `sha256sum` всех найденных файлов. Если файлы доступны, скопируйте их через `adb pull` для офлайн-анализа; это чтение, не запись на устройство.

4. **Прочитайте NVRAM без изменения.** Найдите `ccode=`, `regrev=`, `boardtype=`, `devid=`, `macaddr=` в NVRAM. Не публикуйте MAC-адрес; для отчёта достаточно хэша и факта наличия `ccode`. Сравните `ccode` с CLM-таблицей из таблицы 1. Если `ccode=HK`, `ALL` или `CN`, сразу пометьте это как **неподтверждённое** до проверки `wl country`.

5. **Снимите текущий country code и список поддерживаемых кодов.** Если в прошивке есть Broadcom `wl`, выполните только read-only команды: `wl country`, `wl country list`, `wl channels`, `wl ver`, `wl cap`. `wl country` без аргумента читает текущий код; `wl country list` должен вернуть список, если firmware поддерживает `WLC_GET_COUNTRY_LIST`; `wl channels` показывает текущую таблицу каналов/мощности. Не выполняйте `wl country <XX>` и не используйте `wl down/up`: это уже запись. Если `wl` нет, попробуйте `iw reg get`, `iw dev`, `iw phy` — но bcmdhd может не поддерживать nl80211 regulatory, поэтому отсутствие вывода не доказывает отсутствие режима.

6. **Снимите Android-состояние.** Выполните `dumpsys wifi | grep -iE 'country|WifiCountryCode|mDriverCountryCode|mTelephonyCountryCode|DefaultCountryCode|mOverrideCountryCode|isDriverSupportedRegChangedEvent'`, `cmd wifi status` (если есть), `settings get global wifi_country_code` (старые Android), `wpa_cli -i wlan0 status` (поле `country`, если есть), `getprop | grep -i country`, `logcat -d -s WifiCountryCode WifiNative wpa_supplicant bcmdhd | tail -300`. В `dumpsys wifi` ищите `mDriverCountryCode` и `mTelephonyCountryCode`: расхождение — ключевое доказательство того, что запрос framework не стал фактическим режимом драйвера [7].

7. **Проверьте boot-логи драйвера.** Выполните `dmesg | grep -iE 'dhd_conf|country|ccode|regrev|CLM|Firmware up|bcmdhd|wl_cfg80211'`. Ищите строки `dhd_conf_set_country`, `Country code:`, `set country for`, `country code setting failed`, `dhd_conf_fix_country`, `# of channels`. Если есть `country code setting failed` или `set country ... failed`, firmware не приняла код. Если есть `dhd_conf_fix_country` с малым числом каналов, драйвер мог подменить режим [2] [4].

8. **Снимите эффективные каналы и мощность.** Выполните `iw phy` и `iw dev wlan0 info` (или `wlan1`, если интерфейс называется иначе), `cat /proc/net/wireless`, `wl channels`. Зафиксируйте список частот, флаги `disabled`, `no IR`, `radar detection`, `passive scan`, а также максимальную мощность, если она отображается. Это эффективный режим, а не «список стран»; его нужно сопоставить с `mDriverCountryCode` и `wl country`.

9. **Проверьте Bluetooth отдельно.** Выполните `dmesg | grep -iE 'bluetooth|hci|bcm|4334|hcd'`, `ls -l /sys/class/bluetooth/`, `hciconfig -a`, `btmgmt info` (если есть), `dumpsys bluetooth_manager`, `logcat -d -s BluetoothManagerService bluetooth | tail -200`. Найдите HCD-файл `BCM4334B0.hcd`, его хэш и версию патча в `dmesg`. Ожидаемый результат — HCI-версия, features, class of device, power state; country code в стандартном выводе не будет. Это подтверждает, что Bluetooth-контур не управляется Wi-Fi `ccode`.

10. **Соберите пакет доказательств.** Сохраните все выводы в отдельные текстовые файлы, приложите `sha256sum` firmware/NVRAM/HCD, `dumpsys wifi` с `mDriverCountryCode`, `wl country list`, `wl channels`, `iw phy`, `dmesg` с строками country, `getprop ro.boot.wificountrycode`, `dumpsys bluetooth_manager`. Для каждого утверждения укажите, что это: декларация firmware, запрос Android, ответ драйвера или эффективный канал. Не делайте вывод «страна поддерживается» только по `ccode` в NVRAM.

## Что считается доказательством, а что нет

Доказательство уровня firmware — это хэш конкретного файла плюс разобранная CLM-таблица: например, `fw_bcm4334b1_ag.bin` с `ClmImport 1.6.5` и списком 49 country codes + EU/0 [16]. Доказательство уровня runtime — это `wl country list`, `wl country`, `wl channels` и `dmesg` с успешной установкой кода. Доказательство уровня Android — это `dumpsys wifi` с `mDriverCountryCode` и `logcat` с `updateCountryCode`/`setCountryCodeNative`. Только совпадение этих уровней доказывает, что конкретный H96 Max работает в заявленном режиме.

`ccode=` в NVRAM — не доказательство поддержки: это начальное пожелание, которое firmware может отвергнуть, драйвер может переопределить, а Android позже заменить. `wl country` без `wl country list` показывает только текущий код, но не доказывает, что firmware содержит полную таблицу для этого кода. `iw phy` показывает эффективные каналы, но не источник режима. `WHD_COUNTRY_*` enum из Infineon WHD содержит сотни кодов, но WHD — другой драйвер/стек, и его список не является списком CLM для BCM4334 [31] [32].

Все выводы, зависящие от конкретной прошивки, помечены в таблицах как **неподтверждённые**. К ним относятся: фактическая поддержка `CN`, `HK`, `ALL`, `BR` на конкретном H96 Max; ревизии `JP`, `TW`, `RU`, `US` в конкретной драйверной сборке; поведение `dhd_conf_fix_country`; поддержка vendor-команды Broadcom HAL в Rockchip bcmdhd; наличие CLM-блоба и его версия; Bluetooth-параметры HCD. Для их подтверждения нужны файлы и логи с устройства, а не модель устройства или название прошивки.

## References

[1]: https://datasheet4u.com/datasheets/Cypress/BCM4334/1257382 "Cypress BCM4334 datasheet, dual-band 802.11a/b/g/n with Bluetooth 4.0 and FM"

[2]: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-4.4/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/dhd_config.c "Rockchip bcmdhd dhd_config.c, develop-4.4 branch, snapshot 2026-11-27"

[3]: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-4.4/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/dhd_custom_gpio.c "Rockchip bcmdhd dhd_custom_gpio.c, country locale table"

[4]: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-4.4/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/wldev_common.c "Rockchip bcmdhd wldev_common.c, wldev_set_country"

[5]: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-4.4/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/wl_cfg80211.c "Rockchip bcmdhd wl_cfg80211.c, reg notifier and vendor registration"

[6]: https://raw.githubusercontent.com/rockchip-linux/kernel/develop-4.4/drivers/net/wireless/rockchip_wlan/rkwifi/bcmdhd/dhd_common.c "Rockchip bcmdhd dhd_common.c, CLM validation"

[7]: https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiCountryCode.java "AOSP WifiCountryCode.java, main branch, snapshot 2026-11-27"

[8]: https://github.com/aosp/wifi/blob/master/service/java/com/android/server/wifi/WifiNative.java "AOSP WifiNative.java mirror, legacy DRIVER COUNTRY path"

[9]: https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/wifi/aidl/android/hardware/wifi/IWifiChip.aidl "AOSP IWifiChip.aidl, setCountryCode contract"

[10]: https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/wifi/1.5/IWifiChip.hal "AOSP IWifiChip.hal 1.5, chip-level setCountryCode"

[11]: https://android.googlesource.com/platform/hardware/broadcom/wlan/+/master/bcmdhd/wifi_hal/wifi_hal.cpp "Broadcom bcmdhd legacy Wi-Fi HAL, SetCountryCodeCommand"

[12]: https://source.android.com/docs/core/connect/wifi-hal "Android Wi-Fi HAL documentation"

[13]: https://raw.githubusercontent.com/armbian/firmware/master/brcm/brcmfmac4334-sdio.rockchip,rk3318-box.txt "Armbian BCM4334 NVRAM for RK3318 with ccode HK"

[14]: https://raw.githubusercontent.com/KashaMalaga/proprietary_vendor_samsung/jellybean/n7100/proprietary/system/etc/wifi/nvram_net.txt "Samsung N7100 production NVRAM with ccode GB"

[15]: https://raw.githubusercontent.com/KashaMalaga/proprietary_vendor_samsung/jellybean/n7100/proprietary/system/etc/wifi/nvram_mfg.txt "Samsung N7100 manufacturing NVRAM with ccode ALL"

[16]: https://raw.githubusercontent.com/radxa/rkwifibt/master/firmware/broadcom/all/wifi/fw_bcm4334b1_ag.bin "Rockchip Radxa BCM4334 firmware fw_bcm4334b1_ag.bin, CLM 1.6.5"

[17]: https://raw.githubusercontent.com/armbian/firmware/master/brcm/brcmfmac4334-sdio.bin "Armbian BCM4334 firmware brcmfmac4334-sdio.bin, CLM 1.21.1"

[18]: https://raw.githubusercontent.com/KashaMalaga/proprietary_vendor_samsung/jellybean/n7100/proprietary/system/etc/wifi/bcmdhd_sta.bin_b2 "Samsung N7100 BCM4334 firmware bcmdhd_sta.bin_b2, CLM 1.6.5"

[19]: https://raw.githubusercontent.com/armbian/firmware/master/brcm/BCM4334B0.hcd "Broadcom BCM4334B0 Bluetooth HCD patch"

[20]: https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/drivers/bluetooth/btbcm.c "Linux kernel btbcm Bluetooth driver"

[21]: https://android.googlesource.com/platform/hardware/broadcom/libbt/ "Android Broadcom libbt Bluetooth vendor library"

[22]: https://android.googlesource.com/platform/packages/modules/Bluetooth/ "Android Bluetooth stack source tree"

[23]: https://source.android.com/docs/core/connect/bluetooth "Android Bluetooth HAL documentation"

[24]: https://www.ecfr.gov/current/title-47/chapter-I/subchapter-A/part-15/subpart-C/section-15.247 "FCC 47 CFR 15.247, 2.4 GHz ISM and FHSS rules"

[25]: https://www.ecfr.gov/current/title-47/chapter-I/subchapter-A/part-15/subpart-E/section-15.407 "FCC 47 CFR 15.407, 5 GHz U-NII rules"

[26]: https://www.etsi.org/deliver/etsi_en/300300_300399/300328/ "ETSI EN 300 328, 2.4 GHz wideband transmission systems"

[27]: https://www.etsi.org/deliver/etsi_en/301800_301899/301893/ "ETSI EN 301 893, 5 GHz RLAN"

[28]: https://www.iso.org/iso-3166-country-codes.html "ISO 3166 country codes"

[29]: https://www.bluetooth.com/specifications/specs/core-specification-4-0/ "Bluetooth Core Specification 4.0"

[30]: https://www.bluetooth.com/learn-about-bluetooth/regulatory/ "Bluetooth SIG regulatory information"

[31]: https://infineon.github.io/wifi-host-driver/html/whd__types_8h.html "Infineon WHD whd_types.h country code enumeration"

[32]: https://github.com/Infineon/whd-bsp-integration "Infineon WHD board support package integration, CY_WIFI_COUNTRY"

[33]: https://source.android.com/docs/core/connect/wifi-overview "Android Wi-Fi overview"

[34]: https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git/tree/brcm "linux-firmware brcm directory, snapshot 2026-11-27"
