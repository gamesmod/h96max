# Передача контекста следующей сессии

Канонический checkout: `/home/dsadm/h96max`, remote `git@github.com:gamesmod/h96max.git`, ветка по умолчанию `master`.
Не продолжать в соседнем `/home/dsadm/rk3318-android` и не использовать родительский `/home/dsadm/.git`.

## Куда смотреть

| Вопрос | Файл |
|---|---|
| Правила и процедуры | [`AGENTS.md`](AGENTS.md) |
| Версия сборки | `ro.tvbox.release` в [`build-sd.sh`](build-sd.sh) (сейчас 0.4.9) |
| Незакрытые цели | [`REMAINING-GOALS.md`](REMAINING-GOALS.md) |
| История базовых целей | [`GOALS.md`](GOALS.md) |
| Состав приложений | [`APPS.MD`](APPS.MD), [`apps-sources.md`](apps-sources.md) |
| Gate и доказательства | [`docs/release-quality-gate.md`](docs/release-quality-gate.md), [`docs/release-qa/`](docs/release-qa/) |
| Маршрут сборки/проверки | [`docs/LUNA-WORKFLOW.md`](docs/LUNA-WORKFLOW.md) |
| Архивный снимок сборки | [`BUILD-AND-STATE.md`](BUILD-AND-STATE.md) — не инструкция для текущего SD |

## Снимок 2026-11-23

- Исходники: **0.4.9** (`release/notes/0.4.9.txt` — штатный FileExploresky, расширенные IPTV-источники, без стороннего файлового менеджера).
- Приставка: **0.4.6**, ADB TCP доступен; протоколов `docs/release-qa/0.4.9.*` нет.
- Исключено из целей и образа: Алиса, RuStore, игры, отдельный SD-релиз, QR/веб VLC, zRAM на приставке, ярлык `allsettings`, бенч `tools/bench`.
- Приоритет: QR TorrServer → «Смотреть на ТВ», сборка/приёмка 0.4.9, Actions secrets + входы, затем подтверждение пользователя на публикацию и на wipe при установке.

Длинные журналы сессий 2026-11-06 и частные адреса/секреты в этот файл не возвращать; сырые логи и PCAP не коммитить.
