# Codex CLI + GigaChat: именованные профили

> Это часть репозитория H96max. Профили устанавливаются в `$CODEX_HOME`;
> проектный `.codex/config.toml` остаётся MCP-only, основной профиль Codex не меняется.

Краткая инструкция. Полное исследование, риски и критерии приёмки — в отчёте
[`2026-11-28-codex-gigachat.md`](manus-research/2026-11-28-codex-gigachat.md).

## Что где лежит

```
.codex/profiles/gigachat.config.toml           # источник профиля "gigachat"
.codex/profiles/gigachat-notools.config.toml   # fallback "gigachat-notools"
tools/codex-gigachat.sh                        # install / check / check-project / run / notools
tools/check-codex-project-config.py            # аудит проектного .codex/config.toml
```

Codex **не читает** `.codex/profiles/` автоматически. Именованный профиль
загружается только из `$CODEX_HOME/<name>.config.toml`; установщик копирует
туда файл с режимом `600`.

Проектный `.codex/config.toml` остаётся **MCP-only**. Не добавляйте в него
`model`, `model_provider`, `model_providers`, `profile` или `profiles`:
`model_provider`/`model_providers` Codex игнорирует, а `model` перекрывает
модель профиля.

## Установка

```bash
tools/codex-gigachat.sh install
tools/codex-gigachat.sh check gigachat
tools/codex-gigachat.sh check gigachat-notools
tools/codex-gigachat.sh check-project
```

Если Codex установлен локально через npm и не в `PATH`:

```bash
CODEX_BIN=/path/to/codex tools/codex-gigachat.sh check gigachat
```

`install` отказывается перезаписывать существующий профиль без `--force`.
С `--force` создаёт резервную копию с timestamp.

## Аудит проектного конфига

`check-project` проверяет только верхний уровень `.codex/config.toml` и
завершается с ошибкой, если находит `model`, `model_provider`,
`model_providers`, `profile` или `profiles`. Файл не изменяется.

```bash
tools/codex-gigachat.sh check-project
```

## Запуск

```bash
# Обычный запуск (TUI)
tools/codex-gigachat.sh run

# Неинтерактивный текстовый тест
tools/codex-gigachat.sh run exec --sandbox read-only --ephemeral \
  "Ответь одним словом: OK"

# JSONL + проверка streaming
tools/codex-gigachat.sh run exec --json --sandbox read-only --ephemeral \
  "Ответь одним словом: OK" > /tmp/l2.jsonl

# Tool loop: минимум для признания интеграции рабочей
tools/codex-gigachat.sh run exec --json --sandbox read-only --ephemeral \
  "Выполни printf OK и покажи вывод" > /tmp/l3.jsonl
```

Обёртка всегда передаёт `--profile`, `--strict-config`,
`-c model_provider=gigachat` и `-c model=${GIGACHAT_MODEL:-GigaChat-2-Max}`.
Это защищает от проектного `model` и случайного переключения модели.

Для записи явно добавьте `--sandbox workspace-write` и используйте одноразовую
ветку или `mktemp -d`. По умолчанию профиль read-only.

## Изолированное делегирование

Для автоматической передачи безопасной аналитической подзадачи используйте
только команду `delegate`. Она принимает задание через stdin, запускает
`gigachat-notools` в пустом временном каталоге и не открывает модели дерево
проекта:

```bash
printf '%s\n' \
  'Проверь этот очищенный публичный план на противоречия: ...' \
  | tools/codex-gigachat.sh delegate
```

Передавать разрешено лишь минимальный проверенный публичный контекст. Секреты,
локальные адреса, журналы, незакоммиченные изменения и непросмотренные файлы
передавать нельзя. Политика выбора задач и проверки результата описана в
`docs/AGENTS-REFERENCE.md`, раздел «Делегирование задач в GigaChat».

## Fallback

```bash
tools/codex-gigachat.sh notools exec --sandbox read-only --ephemeral \
  "Суммируй следующий текст: ..."
```

`gigachat-notools` отключает shell, goals, apps, plugins и subagents. В
доверенном проекте всё равно остаются MCP resource tools и
`request_user_input`/`view_image`; это не «ноль инструментов». Вне доверенного
проекта остаются только `request_user_input` и `view_image`.

## Секреты

В Git не должно быть значений `GPT2GIGA_API_KEY` и `GIGACHAT_CREDENTIALS`.
`GPT2GIGA_API_KEY` — клиентский ключ gpt2giga; Codex читает его по имени из
`env_key`. `GIGACHAT_CREDENTIALS` нужен только gpt2giga.

```bash
read -r -s -p "GPT2GIGA_API_KEY: " GPT2GIGA_API_KEY; echo
export GPT2GIGA_API_KEY
```

Проверка утечек:

```bash
git grep -n -I -E 'GPT2GIGA_API_KEY=.+|GIGACHAT_CREDENTIALS=.+' || true
git check-ignore -v .env || echo "WARNING: .env is not ignored"
```

## Минимальные критерии

| Уровень | Проверка | Проход |
|---|---|---|
| L0 | `tools/codex-gigachat.sh check gigachat` + `check-project` | `strict-config OK`; нет `unknown configuration field`; проектный конфиг MCP-only |
| L1 | `exec ... "Ответь OK"` | exit 0, stdout `OK` |
| L2 | `exec --json ...` | `turn.completed`, `agent_message`, нет `turn.failed` |
| L3 | `exec --json ... "printf OK"` | `command_execution` с `exit_code=0`, финальный `agent_message` |
| L4 | MCP-ресурс или 2 команды | 2+ tool calls или `mcp_tool_call`, нет 422 |
| L5 | `tools/codex-gigachat.sh check-project` + хэш `~/.codex/config.toml` | проектный конфиг MCP-only; основной профиль не изменён |

Без L3 интеграция считается chat-only, а не рабочей интеграцией Codex.
