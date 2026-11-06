# Передача контекста следующей сессии

Дата: 2026-11-06. Канонический checkout проекта: `/home/dsadm/h96max` (домашний путь `~/h96max`), remote `git@github.com:gamesmod/h96max.git`, ветка `master`. Не продолжать работу в соседнем `/home/dsadm/rk3318-android`: это прежняя рабочая копия. В корне проекта уже отдельный Git-репозиторий; не использовать родительский `/home/dsadm/.git`.

## Пользовательские решения

- Общение на русском; комментарии в добавляемом коде писать по-русски.
- Обновление только eMMC подписанным OTA из приложения «Установка». Отдельный SD-образ исключён; установка с SD остаётся штатной функцией Android 12 recovery.
- Дисковый swap запрещён навсегда. Не создавать swap-файл/раздел и не включать zRAM writeback на накопитель. `/proc/swaps` на приставке пуст.
- В образах и публичном Git не должно быть Wi-Fi credentials. MAC randomization отключать, MAC не подменять.
- APK/OTA должны подписываться прежними ключами проекта; ключи не менять и не коммитить.
- Главная плитка не должна дублировать самостоятельные приложения; плитка VLC не нужна, VLC — зависимость для ТВ и TorrServe. Игры и PXE удалены из целей. IPTV-категории должны быть в одном списке, дубли качества исключаются.
- SD-релиз не собирать. В README описать состав приложений, настройки плиток, отличие от базы и установку с нуля.
- Алиса должна вызываться кнопкой голосового USB-пульта; также изучалась поддержка микрофона Samsung TV, но через HDMI микрофон Tizen приставке не передаётся.
- Изменения сначала проверять на горячей системе, когда приставка доступна через ADB; перезагрузку делать только для тех системных изменений, которые её требуют.

## Состояние целевой приставки в этой сессии

- Устройство: `panadonik`, Android 12, `ro.tvbox.release=0.3.9`.
- Доступно по ADB TCP из локальной сети; адрес не включать в публичные документы. ADB daemon root, `ro.debuggable=1`.
- USB ADB физически не перечислялся на машине сборки, но сетевой ADB работал.
- `adb remount` включил overlayfs для `/vendor` и `/product`; `/system` пропустил. Команда предупредила, что настройки remount требуют reboot. Не перезагружать без отдельной необходимости.
- Обновлены без очистки данных APK плиток: antivirus, bootlog, clean, installer, IPTV, links, power (`adb install -r`). Package Manager принял их как обновления системных приложений, сохранил данные и перенёс APK в `/data/app`. Resolver видит Activity у Links/IPTV. Для `org.tvbox.links` сертификат нового APK совпал с установленным: SHA-256 `f52a109a77d33ff95bc97760c4b85358b678384f805035ebe0ef9d692e26f388`.
- APK собраны в целевом checkout `h96max` с локальными `sdk/`, `dl/` и неизменёнными signing keys; эти каталоги игнорируются Git.
- На момент первичной проверки Алиса и Obtainium отсутствовали, был установлен `app.flicky`. Установка Obtainium и Алисы на устройство требует отдельной проверки их официальных APK/источников.
- В этой сессии через overlayfs применены USB keylayout и обновлённый `tvbox-firstboot.sh`; скрипт выполнил миграцию раскладки плиток и замену Flicky на Obtainium. Obtainium установлен официальным APK, его SHA-256 был проверен до установки. Алиса пока не установлена и голосовая кнопка не проверена. `/system` остаётся read-only; bootanimation и `tvboxd.jar` нельзя заменить обычным `adb remount` без отдельной процедуры.
- Рабочий update URL до применения новой сборки был локальным HTTP источником. Целевой образ теперь настраивается на GitHub Releases, а tvboxd умеет читать `latest-emmc.txt` и загружать OTA-ассет с HTTPS.
- `/proc/swaps` на приставке пуст; `/data` имеет около 23 ГБ, свободно около 23 ГБ.
- Свежая выборка logcat после установок не показала crash приложений; видны стандартные AVC-отказы, в permissive-режиме. Провести отдельный функциональный прогон плиток/QR/видео/IPTV после продолжения.

## Изменения, присутствующие в коде

- `overlay/vendor/usr/keylayout/ff1b0030_pwm.kl` и сборка USB keylayout: keycode 582 → `VOICE_ASSIST`; USB-устройство в логах `MC,LTD Mic Device`, VID:PID `1915:1025`.
- `overlay/vendor/bin/tvbox-firstboot.sh`: Алисе выдаётся `RECORD_AUDIO`, `appops RECORD_AUDIO allow`, назначается Assistant role при наличии. При установке уже после boot нужен следующий boot, чтобы firstboot hook сработал.
- `obtainium-apps.json`: добавлены приложения и RuStore-источник Алисы `com.yandex.aliceapp`.
- QR-приложение `apps/links`: кнопки запуска VLC и Total Commander; WiFi Transfer Total Commander должен выдавать собственный QR и URL. Нужен тест на устройстве.
- `tools/iptv-sources.txt` содержит взрослый источник `https://raw.githubusercontent.com/zwrt/IPTV/Files/Adult.m3u group=Для взрослых`. `tools/iptv-merge.py` включает adult-каналы по умолчанию, опция `--exclude-adult` отключает их; общий список один, дубли удаляются.
- Сгенерированный `overlay/system/etc/tvbox/iptv.m3u` намеренно исключён: его адреса содержат похожие на секретные токены.
- `README.md` документирует состав и поведение. `build-sd.sh` всё ещё исторически имеет имя SD, но предназначен для eMMC, `tools/build-release.sh` собирает eMMC OTA. Не делать отдельный SD-образ.
- Утилиты собирались/проверялись: `bash -n`, `sh -n`, JSON Obtainium parse; приложения antivirus, bootlog, bootscreen, clean, installer, iptv, links, power, tvboxd собирались.

## Настройка среды сборки

Текущая подтверждённая среда — Ubuntu/Linux x86_64, Android Platform Tools ADB 34.0.5, Bash, Python 3. В рабочей прежней копии доступны:

- OpenJDK 21 (`sdk/jdk/.../java-21-openjdk-amd64`), компиляция Java с `--release 8`;
- Android SDK platform API 23 (`android.jar`), `aapt`, `dx`, `apksigner`/apksig;
- `aarch64-linux-gnu-gcc`, `sgdisk`, `e2fsprogs`, `sudo` для loop mount;
- Rockchip A12 vendor base распакован в `dl/a12/Image/lp`, нужные разделы и проприетарные компоненты — локальные, не коммитить;
- прежние ключи подписи хранятся в `secrets/` (`tvbox-key.pem`, `tvbox-cert.pem`, `ota-key.pem`, `ota-cert.pem`); никогда не копировать их в public repository. `wifi.env` для сборки не требуется.

Для локальной сборки в `h96max` уже восстановлены `sdk/`, `dl/a12/`, signing keys и результаты app build; все закрытые каталоги игнорируются Git. Workflow `.github/workflows/release.yml` запускается тегом `v<версия>`, сверяет его с `ro.tvbox.release`, скачивает приватный архив release inputs по URL и SHA из Actions Secrets, использует прежние ключи из Secrets, собирает и прикладывает eMMC OTA и SHA256SUMS, публикует автоматические GitHub Release Notes. Workflow проверяет свободное место; для большего диска можно задать Actions variable `RELEASE_RUNNER` с меткой подключённого GitHub-hosted larger runner. Аппаратные критерии нужно закрыть вручную до тега.

Проверки toolchain:

```sh
tools/setup-sdk.sh --check
bash -n build-sd.sh tools/build-release.sh apps/*/build.sh
sh -n overlay/vendor/bin/*.sh
python3 -m json.tool obtainium-apps.json >/dev/null
```

Полная сборка eMMC OTA требует vendor base, SDK и ключи:

```sh
tools/build-release.sh
```

Не публиковать OTA/образы, пока не проведены предусмотренные проектом проверки безопасности, логов, QR, сети, видео, графики и загрузки менее 20 секунд.

## Git и публичная публикация

- Целевой Git remote: `git@github.com:gamesmod/h96max.git`, ветка `master`. Исходный код уже опубликован коммитом `195fd87`; текущие изменения ещё не закоммичены. Продолжать только из `~/h96max`, проверить diff и секреты, затем отправить обычный commit без force push.
- Корневые источники проекта перенесены в этот checkout. Проверить, что нет вложенной второй копии `apps/apps` (её быть не должно).
- Добавлен `.gitignore`: исключает `sdk/`, `dl/`, `secrets/`, `build/`, `release/`, APK/JAR/IMG/ZIP/BIN, кэши, логи, диагностику, Wi-Fi XML, `overlay-wifi/` и собранный IPTV M3U.
- Для проекта добавлена красивая эмблема `assets/icon.svg` и матричный баннер `assets/panadonik.svg`; оба используются в README.
- Лицензия проекта не определена; не придумывать лицензию автоматически.
- Не коммитить результат 0.3.9 OTA/образы и приватный IPTV M3U.

## Что осталось

1. Убедиться, что Wi-Fi credentials не остались в `/vendor/etc/tvbox/wifi.env`; проверить наличие файла без чтения содержимого и удалить его, если есть. Подтвердить, что Obtainium остаётся в плитках, Flicky/F-Droid отсутствуют, MAC randomization выключен, `/proc/swaps` пуст.
2. Алиса пока не установлена. Использовать официальный магазин/источник и сверить подпись APK; затем проверить голосовую кнопку и USB-микрофон.
3. Провести горячий функциональный прогон QR VLC/TC, IPTV, сети и просмотра; не заявлять успешное воспроизведение без проверки.
4. Не перезагружать устройство до согласованного окна: bootanimation и системный tvboxd.jar потребуют reboot, поскольку `/system` read-only.
5. Завершить Actions Secrets и загрузить приватный архив из `tools/package-ci-inputs.sh`. Проверить workflow статически и дождаться сборки CI до первого релизного тега.
6. Не создавать tag/release, пока на приставке не пройдены проверки безопасности, логов, QR, сети, IPTV/видео, графики и загрузки менее 20 секунд. GitHub-hosted runner не проверяет аппаратные критерии.
