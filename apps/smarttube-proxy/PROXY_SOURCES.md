# Источники прокси

Фактическая конфигурация находится в `sources.json`. Каждый источник имеет собственный формат, protocol allowlist, флаг `enabled`, зеркала и заявленный период. Зеркало не считается независимым источником. Размер ответа ограничен 2 MiB, число принятых кандидатов — 128 на источник/протокол; разрешён только HTTPS. Адреса нормализуются и дедуплицируются, private/reserved/loopback/multicast отвергаются.

| ID | Тип | Формат | Обновление | Лицензия/ограничения | Оценка |
|---|---|---|---|---|---|
| `proxyscrape_csv` | GitHub raw + CDN mirror | CSV | repo около 5 минут | MIT; соблюдать GitHub/jsDelivr limits | Основной структурированный источник, но локальная проверка обязательна |
| `geonode_json` | публичный API | Geonode JSON | каталог без uptime SLA | provider terms; разумный rate limit | Независимый API, метаданные нельзя считать доказательством доступности |
| `proxmint_json` | GitHub raw | JSON | около 30 минут | CC BY 4.0, обязательна атрибуция | Предварительно проверенный агрегатор; происхождение может пересекаться |
| `maximilianfeix_csv` | GitHub raw | CSV | около часа | MIT | Публикует latency/uptime, но значения перепроверяются локально |
| `vakhov_txt` | GitHub raw + Pages mirror | TXT | заявлено ежедневно | MIT | Простые отдельные списки четырёх протоколов |
| `free_proxy_list_html` | HTML-страница | HTML-таблица | заявлено часто | отдельная лицензия на данные не указана; provider terms | Использовать только пока публичный доступ и условия разрешают; при запрете отключить адаптер |

Проверка 2026-11-27 подтвердила HTTP 200 и синтаксически разбираемые ответы ProxyScrape CSV, Geonode JSON, Free Proxy List HTML, Proxmint JSON и Maximilianfeix CSV. Старый URL Vakhov `all.txt` возвращал 404; конфигурация использует существующие protocol-файлы. Это проверка источников, а не работоспособности опубликованных прокси и не тест из РФ.

HTTP/HTTPS в списке означает кандидата на CONNECT, а не гарантию CONNECT. SOCKS4 используется как SOCKS4a, SOCKS5 — с удалённым DNS. Записи с credentials из публичных источников не принимаются. `manual-proxies.txt`, `proxy-allowlist.txt` и `proxy-denylist.txt` являются private app data и не публикуются.

Публичные прокси могут журналировать домены, менять ответы незашифрованного HTTP, блокировать или ограничивать YouTube и быть honeypot. TLS trust store и hostname verification не отключаются. Кандидат исключается при TLS/hostname ошибке, неверном CONNECT/SOCKS handshake, таймауте, неожиданном HTTP/Content-Type/Range либо повторной деградации. Ни один источник не даёт основания использовать proxy для Google login, платежей или иных чувствительных действий.

Proxy data from [Proxmint's Free Proxy List](https://proxmint.com/free-proxies) ([source](https://github.com/proxmint/free-proxy-list)), licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
