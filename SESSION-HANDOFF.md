# Передача контекста следующей сессии

Канонический checkout: `/home/dsadm/h96max`, remote `git@github.com:gamesmod/h96max.git`, ветка по умолчанию `master`.
Не продолжать в соседнем `/home/dsadm/rk3318-android` и не использовать родительский `/home/dsadm/.git`.

## Куда смотреть

| Вопрос | Файл |
|---|---|
| Правила и процедуры | [`AGENTS.md`](AGENTS.md) |
| Версия сборки | `ro.tvbox.release` в [`build-sd.sh`](build-sd.sh) (сейчас 0.4.12) |
| Незакрытые цели | [`REMAINING-GOALS.md`](REMAINING-GOALS.md) |
| История базовых целей | [`GOALS.md`](GOALS.md) |
| Состав приложений | [`APPS.MD`](APPS.MD), [`apps-sources.md`](apps-sources.md) |
| Gate и доказательства | [`docs/release-quality-gate.md`](docs/release-quality-gate.md), [`docs/release-qa/`](docs/release-qa/) |
| Маршрут сборки/проверки | [`docs/LUNA-WORKFLOW.md`](docs/LUNA-WORKFLOW.md) |
| Архивный снимок сборки | [`BUILD-AND-STATE.md`](BUILD-AND-STATE.md) — не инструкция для текущего SD |

## Снимок 2026-11-27

- Исходники: **0.4.12**, актуальный HEAD и точное состояние upstream всегда
  перепроверять перед началом работы. Заметки —
  [`release/notes/0.4.12.txt`](release/notes/0.4.12.txt).
- Локальный кандидат 0.4.12 собран; проверки хоста и SHA-256 записаны в
  [`docs/release-qa/0.4.12.md`](docs/release-qa/0.4.12.md) и `.json`. Сборка
  выполнялась при параллельном изменении тестового файла, поэтому перед
  аппаратной приёмкой нужен новый воспроизводимый Actions artifact из
  неизменяемого commit.
- 0.4.12 не опубликован и не установлен. Аппаратная приёмка, свежесть Hypatia,
  полный IPTV/torrent/VLC/пульт/file-manager gate, анализ трафика и security
  review остаются `pending`. Исторические pass 0.4.11 не переносить.
- Текущий размер упакованных eMMC-регионов 0.4.12 — 987478331 байт против
  903146457 у 0.4.11; цель ≤703146457 байт остаётся блокером stable.
- Приоритеты и точные критерии продолжения находятся в
  [`REMAINING-GOALS.md`](REMAINING-GOALS.md). В образ и цели включать только
  комплект из [`APPS.MD`](APPS.MD); Dicio и VoiceBridge исключены.
- Публикационный тег/Release и установка с wipe требуют отдельных явных
  подтверждений. QR/веб-управление VLC и zRAM на приставке не возвращать.

Длинные журналы сессий 2026-11-06 и частные адреса/секреты в этот файл не возвращать; сырые логи и PCAP не коммитить.
