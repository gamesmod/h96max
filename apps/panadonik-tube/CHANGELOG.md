# История изменений Panadonik Tube

## 2026-11-27 — интеграция локального прокси (feature/smarttube-cn-proxy-20261127)
- Добавлен автономный прокси-модуль: AutoProxy, Gateway, ProxyPool, SourceManager, ProxyState, ProxyChecker.
- Встроен в MainApplication; в SmartTube принудительно используется OkHttp с локальным proxySelector.
- Отключён встроенный updater; автопрокрутка карточек выключена по умолчанию.
- Сохранены аппаратные декодеры, DASH/SABR/HLS, TLS; архитектуры ARMv7/ARM64.
- CI: unit-тесты прокси-пула и проверки ABI/package; кандидат не публикуется без аппаратной приёмки.
- Блокеры: подтверждение работы из РФ, измерение 1080p/стабильности, полный D-pad/boot/OTA-путь, подпись и Release.