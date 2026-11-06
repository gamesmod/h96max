# Приложения и системные компоненты

Документ описывает ПО, которое проект устанавливает или собирает для образа. APK Panadonik собираются из `apps/` и подписываются существующим ключом проекта; версия APK берётся из `ro.tvbox.release` в `build-sd.sh` через `tools/app-version.sh`. Системные JAR собираются теми же Java/dx-инструментами без APK-подписи. Полный процесс сборки описан в [`BUILD-AND-STATE.md`](BUILD-AND-STATE.md), состав пользовательского интерфейса и стороннего ПО — в [`README.md`](README.md).

## Собственные приложения и компоненты

| Название | Пакет / артефакт | Функция | Сборка и размещение в образе |
|---|---|---|---|
| ТВ-каналы | `org.tvbox.iptv` · `TvboxIptv.apk` | Индексирует M3U-плейлист, показывает группы и карточки каналов, запускает выбранный поток во внешнем VLC. Фоновый `IndexOnBootService` строит индекс при старте и показывает ход выполнения. | `apps/iptv/build.sh`; APK `/system/app/TvboxIptv`. Плейлист и источники описаны в [`README.md`](README.md#iptv-источники-и-проверка). |
| Установка | `org.tvbox.installer` · `TvboxInstaller.apk` | Проверяет состояние внутренней памяти, находит и устанавливает подписанные OTA из сети или с USB/SD; настраивает источники обновлений, показывает ход операции и журнал; управляет установкой, проверкой, резервным копированием и восстановлением образа eMMC. | `apps/installer/build.sh`; APK `/system/app/TvboxInstaller`; привилегированные операции выполняет `tvboxd`. |
| Антивирус | `org.tvbox.antivirus` · `TvboxAntivirus.apk` | Показывает состояние Hypatia; по запросу запускает сканирование, обновляет подписанные базы, включает или выключает защиту в реальном времени и открывает настройки Hypatia. Сам экран не выполняет постоянного фонового сканирования. | `apps/antivirus/build.sh`; APK `/system/app/TvboxAntivirus`; управление службой и базами через `tvboxd`. Зависимость Hypatia — стороннее приложение, см. таблицу ниже. |
| Производительность | `org.tvbox.clean` · `TvboxClean.apk` | Показывает загрузку CPU/GPU, память, температуру, uptime и процессы; позволяет освободить ресурсы, просматривать историю опросов пока плитка открыта, экспортировать CSV и настраивать размер zRAM в RAM. | `apps/clean/build.sh`; APK `/system/app/TvboxClean`; показатели и системные операции запрашиваются у `tvboxd`. Настройка zRAM относится к сжатой памяти в ОЗУ. |
| Питание | `org.tvbox.power` · `TvboxPower.apk` | Меню выключения, перезагрузки, recovery и сна; при загрузке с SD предлагает перезапуск с внутренней памяти, предварительно проверяя её состояние. | `apps/power/build.sh`; привилегированный APK `/system/priv-app/TvboxPower`, разрешения перечислены в `apps/power/privapp-permissions-tvbox.xml`; переход на eMMC через `tvboxd`. |
| Журнал загрузки | `org.tvbox.bootlog` · `TvboxBootLog.apk` | Просмотр журналов загрузки с выделением критичных ошибок и фильтрацией известных сообщений vendor-драйверов. | `apps/bootlog/build.sh`; APK `/system/app/TvboxBootLog`. |
| Ссылки | `org.tvbox.links` · `TvboxLinks.apk` | Показывает адреса доступных веб-служб приставки в виде QR-кодов; помогает открыть VLC и Total Commander. Для WiFi Transfer передаёт пользователя в плагин Total Commander, который формирует актуальный QR. | `apps/links/build.sh`; APK `/system/app/TvboxLinks`. |
| Все настройки | `org.tvbox.allsettings` | Системный ярлык, открывающий штатную страницу настроек Android. Это небольшой исходник-обёртка, не самостоятельное приложение настроек. | `apps/allsettings` не имеет build script и исключён из текущего образа; в релизный список артефактов не входит. |
| tvboxd | `tvboxd.jar` · `org.tvbox.daemon.Main` | Привилегированная системная служба для установки/проверки/резервирования eMMC, OTA, управления Hypatia, метрик и операций питания. Клиенты обращаются к ней через локальный сокет; команды ограничены UID клиентов. | `apps/tvboxd/build.sh`; JAR `/system/framework/tvboxd.jar`, запускается init как служба. |
| Загрузочный экран | `tvbox-bootscreen.jar` · `org.tvbox.bootscreen.Main` | Показывает ход запуска системы и журнал ядра/Android до появления рабочего стола; выводит этапы загрузки и фильтрует типовой vendor-шум. | `apps/bootscreen/build.sh`; JAR `/system/framework/tvbox-bootscreen.jar`, запускается вместо штатной анимации загрузки. |

APK-компоненты, которые входят в собираемый релиз, перечислены в `tools/build-release.sh`: Антивирус, Журнал загрузки, Производительность, Установка, ТВ-каналы, Ссылки и Питание. JAR `tvboxd` и загрузочного экрана также устанавливаются скриптом сборки образа. `Все настройки` описан для полноты исходников, но в текущую сборку не включается. Версия и package ID отдельных APK заданы на этапе сборки общей версией выпуска; `versionName` в исходных манифестах служит значением по умолчанию.

## Стороннее ПО

| Приложение | Package ID | Назначение в системе | Источник / регистрация |
|---|---|---|---|
| VLC | `org.videolan.vlc` | Внешний видеоплеер для IPTV и TorrServe. | [VideoLAN VLC for Android](https://github.com/videolan/vlc-android); Obtainium. |
| TorrServe | `ru.yourok.torrserve` | Сервер и воспроизведение торрентов; веб-интерфейс приставки на порту 8090. | [YouROK/TorrServe](https://github.com/YouROK/TorrServe); Obtainium. |
| TV Launcher | `com.tvlauncher` | Главный экран Android TV и плитки приложений. | [wiiguy/lightweight_androidtv_launcher](https://github.com/wiiguy/lightweight_androidtv_launcher); Obtainium. |
| SmartTube | `org.smarttube.stable` | Клиент YouTube для Android TV. | [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube); Obtainium. |
| Obtainium | `dev.imranr.obtainium` | Получение обновлений приложений из их источников. | [ImranR98/Obtainium](https://github.com/ImranR98/Obtainium); источники приложений импортируются из `/sdcard/tvbox/obtainium-apps.json`. |
| Hypatia | `org.maintainteam.hypatia` | Антивирусный сканер и служба защиты; управляется плиткой «Антивирус». | [MaintainTeam/Hypatia](https://github.com/MaintainTeam/Hypatia); приложение и базы обновляются отдельно. |
| Total Commander | `com.ghisler.android.TotalCommander` | Файловый менеджер и точка запуска плагинов LAN/FTP/SFTP/WiFi Transfer. | [Страница приложения](https://www.ghisler.com/android.htm); сторонний репозиторий плагинов приведён в [`apps-sources.md`](apps-sources.md). |
| Плагины Total Commander | отдельные package ID | Доступ к LAN, FTP, SFTP и передаче файлов по Wi‑Fi. | Репозиторий из [`apps-sources.md`](apps-sources.md), тип источника Obtainium — F-Droid Third Party Repo. |
| LineageOS WebView | системный WebView | Движок для отображения веб-страниц приложениями Android. | Сборка LineageOS; версия и происхождение указаны в [`README.md`](README.md#сторонние-приложения). |

Фактические версии сторонних приложений меняются независимо от APK Panadonik; текущий список версий и инструкция Obtainium поддерживаются в [`README.md`](README.md#сторонние-приложения). Настроенные источники обновлений не включают Алису или RuStore.
