# Сторонние APK в образе: источники и подписи (проверено 2026-10-27)

## Штатный файловый менеджер из A12-образа платы — 2026-11-23

Единственный бинарник vendor-образа в git: `prebuilt/stock-filemanager/FileExploresky.apk`.
Остальной A12 остаётся локально в `dl/` и в git не входит.

| Пакет | Источник | SHA-256 APK | SHA-256 подписанта |
|---|---|---|---|
| `com.droidlogic.FileBrower` | `dl/a12/Image/lp/system.img` → `/system/priv-app/FileExploresky/FileExploresky.apk` | `e7bdfdc011c17f8df3875b4c77a654ba47ee50efa8186026ef72c470cba740aa` | `2d370c21f5dfd553d2a796314b70925fb38adeef90864c920bbbbb12887d3522` |

## Восстановленный комплект — 2026-11-10

| Пакет | Источник и версия | SHA-256 APK | SHA-256 подписанта |
|---|---|---|---|
| `dev.imranr.obtainium` | [Obtainium v1.7.4](https://github.com/ImranR98/Obtainium/releases/tag/v1.7.4), arm64 | `f9216f721a7713b75929e0806bc18586f8fcf7328fbbff99b6aef1debb9eef80` | `b353601f6a1d5fd6603ae2f50be80cf301367b86b6ab8b1f66243da96cd57362` |
| `org.maintainteam.hypatia` | [Hypatia v3.18](https://github.com/MaintainTeam/Hypatia/releases/tag/v3.18) | `d0e31c8dbb338e61220373be61ee524c8476f39aa2d59517984fe45c5e7576c3` | `1b008d64bb95ab4774d68b87f22b8be9a272f4924df52029d7e6183835d918cc` |
| `org.videolan.vlc` | [VLC 3.7.2](https://f-droid.org/packages/org.videolan.vlc/), arm64, сборка F-Droid | `e748c3e0bb9ec447f3e49f71d5c73e0b48c2c0309faa7fb79806d3afb5601b66` | `80535be61eedb9a03b0476a6f493d496c3498770404339ea7a8000f5e61d22c0` |
| `ru.yourok.torrserve` | [TorrServe MatriX.143.FOSS](https://f-droid.org/packages/ru.yourok.torrserve/), сборка F-Droid | `c1021faf83e99bd9cdb7359581ec9c7dabf306ca37edaf286685b30d73e772a6` | `ce166643e2c6aad3f7433a9e2c8e67a34f88dcd0edea74fd244f9737f01a0287` |

GitHub APK сверены с SHA-256 релиза и проверены `apksigner`. Для APK F-Droid проверены подпись индекса и отпечаток сертификата репозитория, дайджест индекса, SHA-256 APK и подписант из индекса. Установка APK не требует установки магазина F-Droid.

Проверка подписи: `tools/apkcert.sh` (apksigner 35.0.2 + apksig из sdk/x, схемы v1/v2/v3). Файлы лежат в `dl/apk/<пакет>.apk`,
ставятся при первой загрузке как пользовательские (`tvbox-firstboot.sh`, из /product/tvbox/apk) — для проверки новых версий установлен Obtainium.
