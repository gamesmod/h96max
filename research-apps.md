# Плееры и приложения для ТВ-приставки: исследование (2026-10-27)

Правило отбора: **минимум ресурсов при максимальной практичности**. Приставка: RK3318, 4 ГБ ОЗУ
(занято ~2,5 ГБ), Android 12 без Google, arm64. Замеры ниже сняты с самой приставки.

## 1. Популярность видеоплееров

| Плеер | Google Play | GitHub ★ | 4PDA «Видеоплееры» (упоминаний, 786 сообщений 2023–26) | Открытый | Комментарий |
|---|---|---|---|---|---|
| MX Player | 1B+ | закрытый | **174** (лидер 2025–26) | нет | реклама, трекеры; против принципа «без Google» |
| **VLC** | 500M+ | 4.1k (зеркало) | **133** | да | всеядный, без рекламы, ТВ-интерфейс; **выбран пользователем** |
| Vimu | 100K+ | закрытый | 80 | нет | любимец 4PDA для TorrServe/NAS, платный после пробного периода |
| Kodi | 50M+ | 21.4k | 75 | да | медиацентр, не плеер; самый тяжёлый |
| mpv-android | 500K+ | 3.6k | 49 | да | лучшая картинка, но интерфейс не для пульта; 4K60 HDR — возможны тормоза |
| XPlayer | — | закрытый | 35 (рост в 2026) | нет | рекламный |
| Nova | 1M+ | 4.8k | 28 | да | медиатека, Android TV |
| Next Player | — | 4.6k | 18 | да | ExoPlayer/Media3, активная разработка (v0.19.0, 09.10.26) |
| Just Player | 1M+ | 2.7k | 13 | да | ExoPlayer, самый лёгкий, без библиотеки; v0.217 (11.09.26) |

Тема 4PDA о нашей приставке (H96 MAX RK3318, стр. 1191–1205): почти всё о прошивках и Wi‑Fi;
из приложений — SmartTube, MX Player, лаунчер Projectivy; связка «торрент + VLC 1080p» упирается в блок питания 1 А.
Тема «must have» 4PDA — 2016 года (Android 5): MX Player, Ace Stream, HD VideoBox, Perfect Player, X-plore — устарело.

**Вывод:** VLC — правильный основной плеер (популярен, открыт, всеяден, без рекламы).
Запасной лёгкий плеер для TorrServe — **Just Player** (ExoPlayer, аппаратное декодирование, малый APK).
MX Player/XPlayer популярнее, но с рекламой и трекерами — не берём.

## 2. Установленные приложения: замер и рекомендация

| Приложение | APK | Данные | ОЗУ (PSS) | Рекомендация |
|---|---|---|---|---|
| Kodi 21.3 | 282 МБ | 193 МБ | — | **Удалить** (решение пользователя). Держит IPTV с телепрограммой — см. п. 3 |
| Termux (только ради Samba) | 145 МБ | 93 МБ | — | ✅ **Удалён вместе с Samba 2026-10-27**. Было: заменить: smbd + библиотеки из `dl/samba-termux.tgz` (37 МБ) запускать напрямую службой vendor без приложения Termux. Экономия ~200 МБ |
| VLC 3.7.2 | 139 МБ (только arm64) | 2 МБ | ~106 МБ при просмотре | **Оставить** основным; субтитры выкл. по умолчанию |
| Flicky 4.5.5 | 22 МБ | 29 МБ | 32 МБ, висит в фоне | Оставить или заменить на Droid-ify (★7.6k, «clutterfree»). В любом случае **запретить фон** (`appops RUN_ANY_IN_BACKGROUND ignore`) |
| TorrServe MatriX.143 | 25 МБ | — | 23 МБ | Оставить (клиент TorrServer, выбор плеера). Опционально Lampa — каталог фильмов поверх TorrServer (веб, тяжелее) |
| TV Launcher 1.6.2 | 3 МБ | — | 28 МБ | **Оставить** — на F-Droid «lightweight, low-RAM», лучше альтернатив (Luncher, Projectivy закрытый) |
| glmark2 | 12 МБ | — | — | ✅ **Удалён 2026-10-27** |
| Rockchip MediaCenter (vendor) | 20 МБ | — | 13 МБ, запущен | **Отключить** (`pm disable-user`) — VLC его покрывает |
| Файловый менеджер (droidlogic) | 0,7 МБ | — | — | Оставить — крошечный. Альтернатива Material Files (★9.3k) тяжелее |
| Свои: Питание, Все настройки, Журнал | <0,1 МБ | — | — | Оставить |

## 3. IPTV после удаления Kodi

Свободных IPTV-плееров для ТВ **с телепрограммой (EPG)** на F-Droid нет:
M3UAndroid (EPG, Android TV) — **архивирован**; IPTV Mine Pro (Leanback, 23 МБ) — EPG не заявлен;
TV Viewer — трекинг. Популярные с EPG — закрытые: TiviMate (5M+, премиум платный), Televizo (5M+),
OTT Navigator (бесплатный, нет в Play, APK).

Варианты:
- **A (минимум):** плейлист m3u открывать в VLC — каналы есть, телепрограммы нет.
- **B:** OTT Navigator или Televizo — EPG, но закрытый код (проверить на трекеры перед установкой).
- **C:** оставить Kodi только ради IPTV — самый тяжёлый (~475 МБ).

## 4. Добавить

- **SmartTube** (★35.6k, YouTube без Google, без рекламы) — по решению пользователя к установке.
- **Плитка «Очистка ресурсов»** по аналогии с кнопкой на A9 — своё приложение (убить фоновые процессы, сбросить кэши).
- По желанию: TV Bro (★1.8k, браузер под пульт) — если нужен браузер.

## Источники

- 4PDA: [Видеоплееры (Андроид)](https://4pda.to/forum/index.php?showtopic=1071076) (все 40 стр.), [H96 MAX RK3318](https://4pda.to/forum/index.php?showtopic=952710) (стр. 1191–1205), [Каталог программ для приставок](https://4pda.to/forum/index.php?showtopic=740069), [Набор must have](https://4pda.to/forum/index.php?showtopic=758998)
- Google Play (число установок, 2026-10-27): VLC, MX Player, Kodi, Vimu, Just Player, Nova, mpv-android, TiviMate, Televizo
- GitHub (звёзды, релизы): videolan/vlc-android, xbmc/xbmc, mpv-android, moneytoo/Player, anilbeesetti/nextplayer, nova-video-player/aos-AVP, YouROK/TorrServer, YouROK/TorrServe, yumata/lampa, yuliskov/SmartTube, Droid-ify/client, NeoApplications/Neo-Store, zhanghai/MaterialFiles, truefedex/tv-bro, oxyroid/M3UAndroid (archived), termux/termux-app
- F-Droid: TV Launcher, Luncher, IPTV Mine Pro, TV Viewer, KnightStalker
- Обзоры: [unstore.io](https://unstore.io/discover/best-apps-for-android-tv-media-players-android/), [free-codecs.com](https://www.free-codecs.com/guides/the-best-video-player-for-android-tv.htm), [beebom](https://gadgets.beebom.com/guides/best-android-video-players), [DTV+](https://dtv.plus/players/), [antifriz.tv IPTV 2026](https://antifriz.tv/blog/top-10-iptv-pleerov-dlya-android-tv-i-samsunglg-chto-vybrat-v-2026), [gisuser IPTV](https://gisuser.com/2026/04/best-iptv-player-apps-for-android-tv/)
