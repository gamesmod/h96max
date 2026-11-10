# Сторонние APK в образе: источники и подписи (проверено 2026-10-27)

## Восстановленный комплект — 2026-11-10

| Пакет | Источник и версия | SHA-256 APK | SHA-256 подписанта |
|---|---|---|---|
| `dev.imranr.obtainium` | [Obtainium v1.7.4](https://github.com/ImranR98/Obtainium/releases/tag/v1.7.4), arm64 | `f9216f721a7713b75929e0806bc18586f8fcf7328fbbff99b6aef1debb9eef80` | `b353601f6a1d5fd6603ae2f50be80cf301367b86b6ab8b1f66243da96cd57362` |
| `org.maintainteam.hypatia` | [Hypatia v3.18](https://github.com/MaintainTeam/Hypatia/releases/tag/v3.18) | `d0e31c8dbb338e61220373be61ee524c8476f39aa2d59517984fe45c5e7576c3` | `1b008d64bb95ab4774d68b87f22b8be9a272f4924df52029d7e6183835d918cc` |
| `org.smarttube.stable` | [SmartTube 32.68](https://github.com/yuliskov/SmartTube/releases/tag/32.68s), arm64 | `0a3d3f024eae66b8468f75fe85ab421169177f5ea1223a5d097d1dc9e1c84cf3` | `109735aa24fe85e2e1623ebb3b1ecc4bf4263f4c6374e4411d646bfa2eda482a` |
| `org.videolan.vlc` | [VLC 3.7.2](https://f-droid.org/packages/org.videolan.vlc/), arm64, сборка F-Droid | `e748c3e0bb9ec447f3e49f71d5c73e0b48c2c0309faa7fb79806d3afb5601b66` | `80535be61eedb9a03b0476a6f493d496c3498770404339ea7a8000f5e61d22c0` |
| `ru.yourok.torrserve` | [TorrServe MatriX.143.FOSS](https://f-droid.org/packages/ru.yourok.torrserve/), сборка F-Droid | `c1021faf83e99bd9cdb7359581ec9c7dabf306ca37edaf286685b30d73e772a6` | `ce166643e2c6aad3f7433a9e2c8e67a34f88dcd0edea74fd244f9737f01a0287` |

GitHub APK сверены с SHA-256 релиза и проверены `apksigner`. Для APK F-Droid проверены подпись индекса и отпечаток сертификата репозитория, дайджест индекса, SHA-256 APK и подписант из индекса. Установка APK не требует установки магазина F-Droid.

Проверка подписи: `tools/apkcert.sh` (apksigner 35.0.2 + apksig из sdk/x, схемы v1/v2/v3). Файлы лежат в `dl/apk/<пакет>.apk`,
ставятся при первой загрузке как пользовательские (`tvbox-firstboot.sh`, из /product/tvbox/apk) — для проверки новых версий установлен Obtainium.

## Total Commander и плагины — F-Droid-репозиторий разработчика
Источник: https://raw.githubusercontent.com/chrisgch/tca/master/fdroid/repo (index-v1.json, имена пакетов и sha256 файлов взяты из него).
Для обновлений через Obtainium нужно выбрать источник `F-Droid Third Party Repo` и указать этот репозиторий вместе с нужным package ID.
Требуемая подпись TC: E50EB216A57D5EB0B1E67802E783DF7B587BDBDC379E3E296F89B49ED32F00FD — **совпала**; плагины подписаны тем же ключом.

| Пакет | Версия | Файл в репо | SHA-256 файла (= index) | SHA-256 сертификата (apksigner) |
|---|---|---|---|---|
| `com.ghisler.android.TotalCommander` | 3.62d | tcandroid-down362d-universal.apk | `d918ccc0506f6ac3b72fb2cc4dc1eccb3f83eb873077d982a79054d8d65467e4` | `e50eb216a57d5eb0b1e67802e783df7b587bdbdc379e3e296f89b49ed32f00fd` |
| `com.ghisler.tcplugins.LAN` | 3.60 | tcandroidlan360.apk | `c3091b317e4f4c4a0139a98830cef4269a79fc18bd27dce6c94d10e7bae308e8` | `e50eb216a57d5eb0b1e67802e783df7b587bdbdc379e3e296f89b49ed32f00fd` |
| `com.ghisler.tcplugins.FTP` | 3.0 | tcandroidftp30.apk | `aa5608bf13826f086c00a6fa161ecc197e4d2bfed15b55810c7eee517ba4a5bc` | `e50eb216a57d5eb0b1e67802e783df7b587bdbdc379e3e296f89b49ed32f00fd` |
| `com.ghisler.tcplugins.SFTP` | 2.94 | tcandroidsftp294.apk | `10b08c835d4b7d1f65a61a1c027fbe0d87ed5c7a79ea4b54240e43a87d4e2090` | `e50eb216a57d5eb0b1e67802e783df7b587bdbdc379e3e296f89b49ed32f00fd` |

## Pixel Wheels — f-droid.org
Индекс https://f-droid.org/repo/index-v1.jar: подпись JAR проверена вручную (CMS + дайджесты SF→MANIFEST→index-v1.json, SHA1withRSA),
сертификат индекса 43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab = опубликованный отпечаток репозитория F-Droid.
APK сверен с этим индексом: sha256 файла и подписант (`signer`) совпали.

| `com.agateau.tinywheels.android` | 1.0.1 | com.agateau.tinywheels.android_33.apk | файл `61a50936e728518ce90e7191f8454a98ff24a80b866fa58292a0b474d2dabcd1` | сертификат `d24912992e85d99daa175dbdc14561d2287b253dd656fbbb14bd8fd4a86138cc` (= signer в индексе F-Droid) |

## SuperTux — релиз GitHub SuperTux/supertux v0.7.0 (в F-Droid нет; там только SuperTuxKart, ему нужен GLES3)
Файл SuperTux-v0.7.0-arm64-v8a.apk, sha256 сверен с `digest` релиза; GLES 2.0, arm64.

| `org.supertux.supertux2` | 0.7.0 | SuperTux-v0.7.0-arm64-v8a.apk | файл `7dd41db4c38862a22625ca0cb66f382d2c30653254a7a2807887cedde4b8f0f9` | сертификат `bc1ac64e45435b9d69beaf4a77cb02c3e9e36bb6dc0d85ceb33665b9f85e1b94` |
