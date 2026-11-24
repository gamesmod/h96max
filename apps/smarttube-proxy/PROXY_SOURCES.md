# Источники прокси

URL и зеркала перечислены в `sources.json`. TXT: строки IP:port или scheme://IP:port; Geonode: JSON (схема и доступность требуют live-проверки). HTTP означает кандидат для CONNECT, а не гарантию поддержки CONNECT. Периодичность из публичной документации, null — не подтверждена. Проверка каждого endpoint из текущей среды и РФ: **не выполнена**. Proxmint и FusionProxies агрегируют другие списки; независимость происхождения адресов не доказана.

Proxy data from [Proxmint's Free Proxy List](https://proxmint.com/free-proxies) ([source](https://github.com/proxmint/free-proxy-list)), licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

## Проверка содержимого 2026-11-24

Через GitHub API прочитаны первые 20 строк восьми SOCKS5-файлов; у каждого 20/20 строк совпали с синтаксической маской. Это проверка формы данных, не доступности raw URL и не прокси из России. Geonode JSON не проверен.

| Источник | Файл | Blob SHA | Строк |
|---|---|---|---:|
| Proxifly | [TXT](https://github.com/proxifly/free-proxy-list/blob/main/proxies/protocols/socks5/data.txt) | `910384559cb5521caec75e9b0817a303fb243c69` | 20/20 |
| ProxyScrape | [TXT](https://github.com/proxyscrape/free-proxy-list/blob/main/proxies/protocols/socks5/data.txt) | `11704b17cc6d84bc6f175429d5452dbf8e0b9512` | 20/20 |
| Vakhov | [TXT](https://github.com/vakhov/fresh-proxy-list/blob/master/socks5.txt) | `55a61414b6ea91c87afce8507a15a1adb4a54b3e` | 20/20 |
| Proxmint | [TXT](https://github.com/proxmint/free-proxy-list/blob/main/proxies/socks5.txt) | `cb9e9d7920413f3d3c6d8a61e7cac9665f992092` | 20/20 |
| Monosans | [TXT](https://github.com/monosans/proxy-list/blob/main/proxies/socks5.txt) | `09702fb6b1fc9211b7923bca1f2681a8c535844f` | 20/20 |
| Dahono | [TXT](https://github.com/Dahono-com/free-proxy-list/blob/main/proxies/socks5.txt) | `46aa20f01120b08fe6b714e8bd802daafd6b7f48` | 20/20 |
| FusionProxies | [TXT](https://github.com/FusionProxies/free-proxy-list/blob/main/proxies/socks5.txt) | `2af6721954794583ff568a8348e6fa3f962691f6` | 20/20 |
| Maximilianfeix | [TXT](https://github.com/maximilianfeix/free-proxy-list/blob/main/socks5.txt) | `a16c26647e312268dff4e2eca661d1c2d6f25241` | 20/20 |
