# Panadonik Tube — сборка из исходников

База: SmartTube 32.83, точный Git SHA и вариант в source.lock.json. Используется F-Droid release flavor; готовые APK SmartTube не скачиваются. Исходники и submodules берутся из зафиксированного дерева Git. Патчи применяются только при совпадении контекста, без best-effort. Два APK: ARMv7 и ARM64 (проверить ABI приставки через adb shell getprop ro.product.cpu.abilist).

## Профиль H96 Max
- Собственный package ID org.tvbox.panadoniktube и search provider; не перезаписывать SmartTube.
- Не строить stable/beta, x86, universal; локализации RU/EN и базовые ресурсы.
- Исключить Google Services и Crashlytics Gradle plugins; выбранный F-Droid flavor не включает beta/stable Crashlytics dependencies. Дополнительная проверка APK обязательна; это не полный аудит телеметрии.
- Отключить встроенный механизм обновлений, автопрокрутку названий карточек по умолчанию; upstream уже отключает previews и UI-анимацию по умолчанию.
- При выборе HTTP 127.0.0.1:18765 в SmartTube принудительно использовать OkHttp с явным локальным proxy для media factory. Не менять глобальные настройки Android, не отключать TLS.
- Не удалять MediaCodec, DASH, SABR, HLS, нативные декодеры, leanback/voice/субтитры до теста зависимостей. R8 и дальнейшее удаление библиотек — отдельная измеряемая оптимизация; не обещать размер или ускорение без теста.

## Сборка
JDK 17, SDK API 34, Build Tools 30.0.3, NDK 21.0.6113669, Git, Python 3. 
`python3 -m unittest -v` затем `bash build.sh` (из этого каталога).
Выход: out/PanadonikTube-*-unsigned.apk, SHA256SUMS, SUBMODULES.txt, PANADONIK.patch, LICENSE.smarttube, source.lock.json. Не устанавливать неподписанный кандидат. Подпись и полный комплект лицензий/source notices — отдельный этап выпуска. Ключи не передавать скачанному Gradle.

## Исследование форков
SmartTube-CN: https://github.com/jinran3/SmartTube-CN/commit/f8d3c09659ecf66b7a3d52ba533bf5f2c00af2cc — идея выделенного OkHttp media route, не переносить чужой updater и Workers. 
SuperTube: https://github.com/jgestal/SuperTube — лёгкие настройки UI. 
Electric Eel: https://github.com/cygnusx-1-org/electriceel — исправления SABR требуют согласованных изменений MediaServiceCore; пока не переносим.
SmarterTube: https://github.com/joaotapparo/smarter-tube/commit/f6f19352501227cde3edcae9b91865c8e81f91dd — recovery; upstream уже инвалидирует кеш форматов при long buffering, не копировать без регрессионных тестов.
Yandex VOT: https://github.com/Adrena1ine-ai/SmartTube-Yandex-VOT — отдельная опциональная функция, не включена в минимальную сборку (внешний сервис, зависимости).

## Статус
Кандидат сборочной интеграции, не часть прошивки до приёмки. Автономная медиадиагностика и автоматическая смена прокси с обновлением IP-bound URL ещё не реализованы. Не передавать пользовательские signed media URLs в сторонние приложения/логи. Использовать публичное тестовое видео и изолированную диагностику без Google credentials. Сеть РФ, реальный H96 Max, скорость и размер не подтверждены.
