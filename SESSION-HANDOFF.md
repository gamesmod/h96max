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
- Доступно по ADB TCP: `10.12.54.40:5555`; ADB daemon root, `ro.debuggable=1`.
- USB ADB физически не перечислялся на машине сборки, но сетевой ADB работал.
- `adb remount` включил overlayfs для `/vendor` и `/product`; `/system` пропустил. Команда предупредила, что настройки remount требуют reboot. Не перезагружать без отдельной необходимости.
- Обновлены без очистки данных APK плиток: antivirus, bootlog, clean, installer, IPTV, links, power (`adb install -r`). Package Manager принял их как обновления системных приложений, сохранил данные и перенёс APK в `/data/app`. Resolver видит Activity у Links/IPTV. Для `org.tvbox.links` сертификат нового APK совпал с установленным: SHA-256 `f52a109a77d33ff95bc97760c4b85358b678384f805035ebe0ef9d692e26f388`.
- Новые APK собраны в прежней копии `rk3318-android` с SDK и ключами проекта. В `h96max` нужно настроить локальные невключаемые в Git `sdk/`, `dl/` и `secrets/`, затем повторять сборку там.
- На приставке отсутствуют Алиса и Obtainium; остался пакет `app.flicky`. Новые Obtainium/Alice APK не устанавливались. Поддержка Алисы на уровне исходников настроена, но реальный вызов голосовой клавишей не подтверждён.
- `adb remount` сделал vendor/product writable, но файлы keylayout, firstboot, bootanimation и прочие vendor-изменения на живой системе не копировались. Их изменения требуют отдельного применения и, вероятно, reboot.
- `/vendor/etc/tvbox/update-urls.txt` на приставке указывает на `http://10.12.54.20:8099/`; адрес совпадает с IP компьютера сборки. В текущем checkout отсутствуют release artifacts, локальный HTTP update server не проверен.
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
- прежние ключи подписи хранятся в `secrets/` (`tvbox-key.pem`, `tvbox-cert.pem`, `ota-key.pem`, `ota-cert.pem`), а также `wifi.env`; никогда не копировать их в public repository.

В целевом checkout `h96max` сейчас нет `sdk/`, `dl/`, `secrets/` или `release/`. Подготовить их локально из проверенного исходного окружения; сверить права на signing keys (0600), не менять ключи, не включать секретные файлы в Git. Перед полным OTA `tools/build-release.sh` требуется `release/notes/<version>.txt`, а выход `release/` исключён из Git.

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

- Целевой Git remote уже настроен: `git@github.com:gamesmod/h96max.git`, ветка `master`. В этой сессии `git push` не завершился, поскольку работали в другом каталоге; продолжать публикацию из `~/h96max` после просмотра полного `git diff` и проверки секретов.
- Корневые источники проекта перенесены в этот checkout. Проверить, что нет вложенной второй копии `apps/apps` (её быть не должно).
- Добавлен `.gitignore`: исключает `sdk/`, `dl/`, `secrets/`, `build/`, `release/`, APK/JAR/IMG/ZIP/BIN, кэши, логи, диагностику, Wi-Fi XML, `overlay-wifi/` и собранный IPTV M3U.
- README проекта просил подобрать красивый значок: пока отдельного корневого логотипа нет. В приложениях уже есть функциональные иконки/баннеры; не выдавать значок выключения или IPTV за брендовый логотип. Если сделать эмблему, сохранить её как `assets/panadonik.svg` и отослать README к ней.
- Лицензия проекта не определена; не придумывать лицензию автоматически.
- Не коммитить результат 0.3.9 OTA/образы и приватный IPTV M3U.

## Что осталось

1. В новой сессии сразу открыть `/home/dsadm/h96max` и этот handoff; не продолжать соседнюю копию.
2. Проверить `git status`, локальные изменения, целостность исходников и дубли папок; проверить `git remote` и push в публичную ветку.
3. Установить Obtainium и Алису на приставку из официальных источников. Учитывать user confirmation для установки сторонних APK; не загружать непроверенные бинарники.
4. Проверить keycode 582: удержание кнопки запускает Алису, USB microphone захватывает звук; проверить привилегии, assistant role и logcat.
5. Горячо применить возможные vendor changes через remount, подтвердить SELinux labels; перезагрузка нужна для voice keylayout/boot screen и некоторых init changes.
6. Проверить общий IPTV список после полной проверки источника, QR VLC/TC, плитки и сетевой OTA endpoint.
7. Решить и реализовать обновление из GitHub Releases отдельно: сейчас устройство получает URL из `/vendor/etc/tvbox/update-urls.txt`; сам GitHub source repository не публикует подписанный OTA автоматически.
8. Выбрать брендовый значок для README/GitHub repo, затем обновить документацию.
