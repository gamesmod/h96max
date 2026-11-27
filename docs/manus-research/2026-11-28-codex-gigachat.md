# Codex CLI 0.172.0 + gpt2giga/GigaChat для Panadonik

**Статус документа:** исследование и рекомендации. Профили и инструменты добавлены в репозиторий `gamesmod/h96max`; проектный `.codex/config.toml` с MCP не изменялся, основной профиль Codex не затрагивался. Реальный GigaChat E2E не выполнялся; секреты не запрашивались и не публиковались.

**Что проверено практически:** Codex CLI 0.172.0 (npm-пакет `@openai/codex`) в изолированной песочнице; загрузка именованного профиля; приоритет проектного `.codex/config.toml`; поведение `--strict-config`; `--ignore-user-config`; `--trust`; состав реального payload на `POST /v1/responses`; полный tool loop через mock-сервер; влияние `[agents] enabled = false`, `[features] goals = false`, `[features] shell_tool = false` на набор инструментов.

**Что не проверено практически:** реальный GigaChat API, реальный gpt2giga с ключом, реальные схемы MCP-инструментов Panadonik. Все выводы о поведении GigaChat-2/3, кроме официальной документации, помечены отдельно. Интеграция не может считаться принятой без реального E2E-теста streaming + tool calling (раздел 8).

---

## 1. Краткий вывод

1. **Codex не читает именованный профиль из репозитория.** Именованный профиль — это отдельный файл `$CODEX_HOME/<name>.config.toml`, который выбирается флагом `--profile <name>` (или `-p`). Проектный `.codex/config.toml` не может его заменить. Поэтому в Git хранится **источник** профиля (`.codex/profiles/gigachat.config.toml`) и установщик, который копирует его в `$CODEX_HOME` с правами `0600`. Сам `$CODEX_HOME` остаётся вне Git.

2. **Проектный `.codex/config.toml` нельзя использовать для провайдера.** Codex игнорирует в проектном файле `model_provider`, `model_providers`, `openai_base_url`, `chatgpt_base_url`, `profile`, `profiles`, `notify`, `otel`, `experimental_realtime_ws_base_url`, `apps_mcp_product_sku`. Это machine-local настройки; Codex выводит предупреждение и не применяет их. Провайдер и авторизация должны быть в файле профиля или в пользовательском `~/.codex/config.toml`.

3. **Проектный `model` имеет приоритет над профилем.** Это критично и проверено в 0.172.0: если в проектном `.codex/config.toml` стоит `model = "bogus-model"`, а в профиле — `GigaChat-2-Max`, Codex использует `bogus-model`, хотя провайдер остаётся `gigachat`. Поэтому проектный конфиг должен оставаться **MCP-only**, без `model`, а обёртка должна явно пинить модель через `-c model=...` (CLI-флаг имеет высший приоритет).

4. **Интеграция без streaming + tool calling не является рабочей.** Обычный ответ Responses API доказывает только транспорт и авторизацию. Codex — агент; его ценность в цикле «модель → инструмент → результат → модель». Минимальная планка приёмки — успешный `exec_command` с `exit_code = 0`, возврат результата и финальный `agent_message`; для production нужен ещё многошаговый цикл и MCP. Текст без инструментов — это chat-only, а не интеграция Codex.

5. **Рекомендуемый baseline — GigaChat-2-Max, gpt2giga 0.3.2, `base_url = http://127.0.0.1:8090/v2`, `wire_api = "responses"`, `model_reasoning_effort = "none"`, `web_search = "disabled"`, `model_context_window = 128000`, `model_auto_compact_token_limit = 96000`.** Для GigaChat-2-Max контекст 128k подтверждён официально. `model_max_output_tokens` в 0.172.0 является неизвестным полем и с `--strict-config` отвергается, поэтому его включать нельзя.

6. **GigaChat-3-Pro нельзя закладывать в релиз.** На официальной странице моделей GigaChat есть GigaChat-3-Ultra, GigaChat-2-Max, GigaChat-2-Pro и GigaChat-2-Lite; GigaChat-3-Pro там отсутствует. Это экспериментальная модель/алиас до появления официальной страницы. Для GigaChat-3-Ultra официальный контекст не опубликован, поэтому переносить на неё `model_context_window = 128000` нельзя.

7. **`GPT2GIGA_NORMALIZE_TOOL_SCHEMAS=True` — не универсальное исправление.** Флаг работает только для моделей GigaChat-2 и только при `GIGACHAT_BASE_URL=https://api.giga.chat/v1` (слэш допустим). Для GigaChat-3 и других base URL он не меняет схемы. Нормализатор обрабатывает `anyOf`/`oneOf`, type-массивы и объекты без `properties`, но не удаляет `additionalProperties`, не разворачивает `$ref` и не объединяет `allOf`. В payload Codex 0.172.0 как раз нет `anyOf`/`oneOf`/`$ref`, зато есть `additionalProperties: false`, который флаг не трогает.

8. **Самый безопасный первый шаг — read-only профиль с отключённым multi-agent namespace.** `[agents] enabled = false` в профиле убирает из payload инструмент `multi_agent_v1` и его 5 вложенных функций; это проверено: с пустым MCP-сервером набор уменьшается с 11 до 10 инструментов. `sandbox_mode = "read-only"` задаёт безопасный default; `--sandbox workspace-write` явно перекрывает его для задач редактирования.

---

## 2. Методика и границы проверки

Исследование опиралось на три группы источников:

| Группа | Что использовано | Как проверено |
|---|---|---|
| Официальная документация Codex | Configuration Reference, Advanced Config, Basic Config, CLI Reference, Non-interactive mode | Прочитаны сохранённые страницы; ключевые утверждения сверены с поведением 0.172.0 |
| Код и документация gpt2giga | Репозиторий `ai-forever/gpt2giga`, тег `v0.3.2`, ветка `main` (0.3.3a1), `docs/configuration.md`, `integrations/codex/README.md`, релизы | Анализ кода нормализации схем, namespace-инструментов, Responses-парсера; запуск не выполнялся |
| Официальная документация GigaChat | Страницы моделей, описание функций, `POST /chat/completions`, режимы `function_call` | Прочитаны страницы; реальные вызовы с ключом не выполнялись |

Практические тесты Codex выполнялись с mock-серверами на `127.0.0.1:8090` и `127.0.0.1:8091`. Mock-серверы воспроизводили SSE-ответы и function call, но не GigaChat. Это позволяет проверить механику Codex, конфигурацию и transport/tool loop, но **не** позволяет утверждать, что GigaChat примет конкретную схему инструмента или сгенерирует корректные аргументы. Все такие утверждения в документе помечены как неподтверждённые.

---

## 3. Ключевые ограничения Codex CLI 0.172.0

### 3.1. Где Codex ищет именованный профиль

Именованный профиль — отдельный TOML-файл:

```
$CODEX_HOME/<name>.config.toml
```

Он выбирается флагом `--profile <name>` или `-p <name>`. Ключи в файле профиля задаются **на верхнем уровне**, а не внутри `[profiles.<name>]`.

```
$ codex --profile gigachat ...
```

Начиная с Codex 0.134.0 `--profile` больше не читает `[profiles.<name>]` из `config.toml`, а top-level `profile = "<name>"` не поддерживается. Это важно: не пытайтесь положить профиль в `~/.codex/config.toml` под `[profiles.gigachat]` и не добавляйте `profile = "gigachat"` — 0.172.0 это не применит.

### 3.2. Порядок приоритетов

| Приоритет | Источник | Комментарий |
|---|---|---|
| 1 (высший) | CLI-флаги и `-c key=value` | Перекрывает всё, включая проектный конфиг |
| 2 | Проектный `.codex/config.toml` | Только для доверенных проектов; ближайший к cwd побеждает |
| 3 | Файл профиля `$CODEX_HOME/<name>.config.toml` | Выбирается `--profile` |
| 4 | Пользовательский `~/.codex/config.toml` | Основной профиль по умолчанию |
| 5 | Cloud-managed config | Если используется |
| 6 | `/etc/codex/config.toml` | Системный |
| 7 (низший) | Встроенные значения Codex | Fallback |

Именно поэтому обёртка с `-c model=...` и `-c model_provider=gigachat` надёжнее, чем надежда на то, что проектный конфиг не содержит `model`.

### 3.3. Что проектный `.codex/config.toml` не может переопределять

Codex игнорирует в проектном конфиге следующие machine-local ключи:

```
model_provider
model_providers
openai_base_url
chatgpt_base_url
profile
profiles
notify
otel
experimental_realtime_ws_base_url
apps_mcp_product_sku
```

При наличии таких ключей Codex выводит предупреждение вида:

```
warning: Ignored unsupported project-local config keys in .../.codex/config.toml:
model_provider, model_providers. If you want these settings to apply, manually set
them in your user-level config.toml.
```

Это не ошибка, а сознательное ограничение безопасности: проектный репозиторий не должен перенаправлять трафик и авторизацию. Провайдер `gigachat` и `env_key` должны быть в файле профиля (или в user-level config).

### 3.4. Критичный тест: проектный `model` перекрывает профиль

В песочнице был создан доверенный проект с таким `.codex/config.toml`:

```toml
model_provider = "bogus"
model = "bogus-model"

[model_providers.bogus]
name = "bogus"
base_url = "http://127.0.0.1:9999/v1"
wire_api = "responses"

[mcp_servers.echo]
command = "echo"
args = ["hi"]
```

Запуск:

```bash
codex --profile gigachat --strict-config exec \
  --trust --skip-git-repo-check --sandbox read-only --ephemeral \
  "Reply OK"
```

Фактический результат в заголовке Codex:

```
model: bogus-model
provider: gigachat
```

То есть проектный `model_provider` был проигнорирован (как и положено), а проектный `model` — применён и перекрыл модель профиля. Проектный конфиг только с `[mcp_servers.echo]` модель профиля не менял.

**Следствие:** если в Panadonik когда-нибудь появится `model` в `.codex/config.toml`, все запуски `codex --profile gigachat` будут молча использовать чужую модель. Защита: (а) держать проектный конфиг MCP-only; (б) всегда запускать через обёртку, которая передаёт `-c model=GigaChat-2-Max`; (в) добавить аудит проекта в CI.

### 3.5. `--strict-config`

Официальное описание: «Error when `config.toml` contains fields this Codex version does not recognize». Поддерживается runtime-командами (`codex`, `codex exec`, `codex review`, `codex resume`, `codex fork`, `codex app-server`, `codex exec-server`), но **не** поддерживается `codex mcp` и `codex debug`.

Важные следствия:

- `--strict-config` валидирует не только профиль, но и загруженный проектный `.codex/config.toml`. Если в проекте есть неизвестное поле, команда упадёт с указанием файла и строки. Это хороший ранний тест, но он может вскрыть старые проблемы проектного конфига, не связанные с GigaChat.
- `codex doctor` не принимает `--profile`: «--profile only applies to runtime commands, `codex mcp`, and `codex migrate-nfs-db`». Для проверки профиля используйте `codex exec --strict-config`, а не `codex doctor`.
- `model_max_output_tokens` в 0.172.0 — неизвестное поле; `--strict-config` его отвергает. `model_context_window` и `model_auto_compact_token_limit` принимаются.

### 3.6. `--ignore-user-config` игнорирует и профиль

Официально `--ignore-user-config` означает «Do not load `$CODEX_HOME/config.toml`». На практике в 0.172.0 он игнорирует и файл профиля: Codex откатывается к встроенным значениям (`gpt-6.2-sol`, провайдер `openai`). Поэтому комбинация

```bash
codex --profile gigachat --ignore-user-config ...
```

не работает так, как можно ожидать: профиль не загрузится. Не используйте её для «чистого» теста профиля.

### 3.7. `--trust` и проектный MCP

Codex загружает проектный `.codex/config.toml` только для доверенных проектов. Доверие можно сохранить однократно:

```bash
codex --profile gigachat --strict-config exec \
  --trust --skip-git-repo-check --sandbox read-only --ephemeral \
  "Ответь OK"
```

`--trust` — флаг подкоманды `exec`; он сохраняет доверие проекту и несовместим с `--ignore-user-config`. Он не меняет основной профиль Codex, но меняет пользовательское состояние доверия (база состояния Codex), поэтому выполнять его нужно осознанно.

### 3.8. Известная проблема с `/model`

В openai/codex issue #35487 описано, что в 0.142.2 на Windows меню `/model` перезаписывает кастомный профиль OpenAI-моделью, сохраняя `model_provider`. Статус в 0.172.0 не проверялся. Практический вывод: не открывайте `/model` в профиле `gigachat`; используйте `--profile`, `-m` или `-c model=...`. Обёртка с `-c model=...` mitigates это на уровне запуска, но не лечит TUI-меню.

---

## 4. Рекомендуемая структура в репозитории

Codex читает только `$CODEX_HOME/<name>.config.toml`. Поэтому в Git нужен **источник профиля**, а не сам профиль по месту чтения. Предлагаемая структура:

```
Panadonik/
├── .codex/
│   ├── config.toml                      # СУЩЕСТВУЮЩИЙ, MCP-only; НЕ МЕНЯТЬ
│   └── profiles/
│       ├── gigachat.config.toml         # источник профиля "gigachat"
│       └── gigachat-notools.config.toml # источник профиля "gigachat-notools"
├── tools/
│   ├── codex-gigachat.sh                # install / check / check-project / run / notools
│   └── check-codex-project-config.py    # аудит: нет model/model_provider в проекте
└── docs/
    ├── codex-gigachat.md                # краткая инструкция для команды
    └── manus-research/
        └── 2026-11-28-codex-gigachat.md # это исследование
```

Пояснения:

| Путь | Роль | Почему так |
|---|---|---|
| `.codex/config.toml` | Существующий проектный конфиг MCP | Codex загружает его для доверенного проекта; трогать не нужно |
| `.codex/profiles/gigachat.config.toml` | Версионируемый источник основного профиля | Не читается Codex автоматически; копируется установщиком в `$CODEX_HOME` |
| `.codex/profiles/gigachat-notools.config.toml` | Версионируемый источник fallback-профиля | Позволяет безопасно откатиться к read-only/no-shell режиму |
| `tools/codex-gigachat.sh` | Установка, проверка, запуск | Убирает ручные ошибки, пинит модель и провайдер |
| `tools/check-codex-project-config.py` | Проверка, что проектный конфиг не содержит machine-local ключей | Защита от случайного `model` в проектном файле |
| `docs/codex-gigachat.md` | Инструкция | Чтобы новый участник не копировал официальный гид gpt2giga, который делает gpt2giga профилем по умолчанию |

Почему не `.codex/gigachat.config.toml`? Потому что Codex не читает этот путь и потому что файл рядом с `config.toml` легко перепутать с проектным конфигом. Подкаталог `profiles/` явно показывает, что это шаблоны, а не активная конфигурация.

Почему не `~/.codex/gigachat.config.toml` в Git? Потому что `$CODEX_HOME` — пользовательский, машинно-зависимый каталог. Он может быть переопределён, может содержать другие профили и не должен быть частью репозитория.

Что **не** нужно делать:

- Не добавлять `model`, `model_provider`, `model_providers`, `profile`, `profiles` в `.codex/config.toml`.
- Не добавлять `[profiles.gigachat]` в `~/.codex/config.toml`: 0.172.0 это не читает.
- Не коммитить `.env`, `GIGACHAT_CREDENTIALS`, `GPT2GIGA_API_KEY` или токены.
- Не менять `~/.codex/config.toml` (основной профиль) в рамках этой задачи.
- Не добавлять `.codex/profiles/` в `.gitignore`: это версионируемый исходник, а не секрет.

---

## 5. Точные TOML-фрагменты

### 5.1. Основной профиль `gigachat`

Файл-источник: `.codex/profiles/gigachat.config.toml`. Устанавливается как `$CODEX_HOME/gigachat.config.toml` с правами `0600`.

```toml
# Источник именованного профиля Codex "gigachat".
# Установка: $CODEX_HOME/gigachat.config.toml, режим 0600.
# Секретов нет: GPT2GIGA_API_KEY читается из окружения.

model = "GigaChat-2-Max"
model_provider = "gigachat"
model_reasoning_effort = "none"
web_search = "disabled"

# GigaChat-2-Max: 128 000 токенов контекста (официально).
# Компакция на 75% оставляет запас на ответ и инструменты.
model_context_window = 128000
model_auto_compact_token_limit = 96000

# Безопасный default; для редактирования явно передайте
# --sandbox workspace-write.
sandbox_mode = "read-only"

# GigaChat-2 не нужен multi-agent namespace Codex.
# Это убирает инструмент multi_agent_v1 и 5 вложенных функций из payload.
[agents]
enabled = false

[model_providers.gigachat]
name = "gpt2giga GigaChat"
base_url = "http://127.0.0.1:8090/v2"
env_key = "GPT2GIGA_API_KEY"
wire_api = "responses"
supports_websockets = false
requires_openai_auth = false
```

Пояснения по полям:

| Поле | Значение | Почему |
|---|---|---|
| `model` | `GigaChat-2-Max` | Подтверждённая официальная модель с контекстом 128k и поддержкой функций |
| `model_provider` | `gigachat` | ID должен совпадать с `[model_providers.gigachat]` |
| `model_reasoning_effort` | `none` | GigaChat не использует OpenAI reasoning; gpt2giga принимает это поле |
| `web_search` | `disabled` | Встроенный web search Codex — hosted OpenAI-инструмент; через GigaChat он не работает |
| `model_context_window` | `128000` | Официальный контекст GigaChat-2-Max |
| `model_auto_compact_token_limit` | `96000` | 75% контекста; запас на ответ и tool outputs |
| `sandbox_mode` | `read-only` | Безопасный default; CLI `--sandbox workspace-write` перекрывает |
| `[agents] enabled` | `false` | Убирает `multi_agent_v1` namespace; проверено на 0.172.0 |
| `base_url` | `http://127.0.0.1:8090/v2` | `/v2` — рекомендованный gpt2giga путь для Codex; v2 нужен для namespace/parallel |
| `env_key` | `GPT2GIGA_API_KEY` | Имя переменной, не значение; ключ никогда не в TOML |
| `wire_api` | `responses` | Codex использует Responses API |
| `supports_websockets` | `false` | gpt2giga не WebSocket-провайдер |
| `requires_openai_auth` | `false` | Это не OpenAI; ключ берётся из `env_key` |

Чего в профиле быть не должно:

- `model_max_output_tokens` — неизвестное поле в 0.172.0, `--strict-config` падает.
- `model_supports_reasoning_summaries` — по данным gpt2giga, 0.146.0 отвергал при `--strict-config`; в 0.172.0 не проверять/не добавлять.
- `[profiles.gigachat]` — не читается с 0.134.0.
- `profile = "gigachat"` на верхнем уровне — не поддерживается.
- Любые `api_key`, `token`, `password` — только имя переменной окружения.
- CLI-флаг `--search` устанавливает `web_search = "live"` и перекрывает `web_search = "disabled"`; не используйте его с GigaChat.

### 5.2. Fallback-профиль `gigachat-notools`

Файл-источник: `.codex/profiles/gigachat-notools.config.toml`. Устанавливается как `$CODEX_HOME/gigachat-notools.config.toml`.

```toml
# Источник именованного профиля Codex "gigachat-notools".
# Установка: $CODEX_HOME/gigachat-notools.config.toml, режим 0600.
# Секретов нет.

model = "GigaChat-2-Max"
model_provider = "gigachat"
model_reasoning_effort = "none"
web_search = "disabled"
model_context_window = 128000
model_auto_compact_token_limit = 96000

# Read-only и без интерактивных подтверждений.
sandbox_mode = "read-only"
approval_policy = "never"

[features]
shell_tool = false
goals = false
apps = false
plugins = false

[agents]
enabled = false

[model_providers.gigachat]
name = "gpt2giga GigaChat"
base_url = "http://127.0.0.1:8090/v2"
env_key = "GPT2GIGA_API_KEY"
wire_api = "responses"
supports_websockets = false
requires_openai_auth = false
```

Что реально остаётся в payload (проверено на 0.172.0):

| Условие | Инструменты | Комментарий |
|---|---|---|
| Доверенный проект с MCP-сервером без собственных tools | `request_user_input`, `view_image`, `list_mcp_resources`, `list_mcp_resource_templates`, `read_mcp_resource` — 5 | MCP-инструменты остаются, потому что проектный конфиг загружен |
| Каталог вне доверенного проекта | `request_user_input`, `view_image` — 2 | Проектный `.codex/config.toml` не загружается |
| Полный профиль с `[agents] enabled = false` и MCP | 10 | 7 функций + 3 MCP resource tools |
| Полный профиль без MCP | 7 | `exec_command`, `write_stdin`, `request_user_input`, `view_image`, `get_goal`, `create_goal`, `update_goal` |

Важно: «notools» — это не «ноль инструментов». Codex 0.172.0 всё равно отправляет `request_user_input` и `view_image`, а в доверенном проекте — ещё и MCP resource tools. Полностью убрать tools конфигурацией 0.172.0 не удалось. Если нужен режим «только текст», запускайте профиль вне доверенного проекта (тогда остаются 2 инструмента) или используйте обычный чат вне Codex.

### 5.3. Проектный `.codex/config.toml`: ожидаемая форма

Существующий проектный конфиг должен оставаться MCP-only. Его форма может быть такой:

```toml
# .codex/config.toml — существующий проектный конфиг Panadonik.
# Здесь только MCP-серверы и их параметры.
# НЕ добавлять сюда model, model_provider, model_providers, profile, profiles.

[mcp_servers.example]
command = "npx"
args = ["-y", "@example/mcp-server"]
# env_vars — это allow-list имён переменных, а не значения.
# Значения приходят из окружения/секрет-менеджера и не коммитятся.
env_vars = ["EXAMPLE_API_KEY"]
```

Это иллюстрация, а не предложение изменить конкретный файл Panadonik. Если в реальном `.codex/config.toml` используются другие ключи MCP (`url`, `http_headers`, `env`, `enabled_tools`, `disabled_tools`), их не нужно трогать в рамках этой задачи. Важно лишь, чтобы там не было machine-local ключей из раздела 3.3.

Аудит можно выполнить скриптом из раздела 6.4. Если аудит найдёт `model`, не удаляйте его автоматически: сначала выясните, зачем он там. Обёртка всё равно пинит модель через `-c model=...`, но прямой запуск `codex --profile gigachat` без обёртки будет подвержен переопределению.

### 5.4. Почему `/v2`, а не `/v1`

Официальный гид gpt2giga для Codex использует:

```toml
base_url = "http://localhost:8090/v2"
```

и отдельно рекомендует `GPT2GIGA_GIGACHAT_API_MODE=v2`. В gpt2giga версия в `base_url` — это явное per-request переопределение контракта GigaChat:

- `/v1/...` всегда идёт в GigaChat v1;
- `/v2/...` всегда идёт в GigaChat v2;
- root URL без версии использует `GPT2GIGA_GIGACHAT_API_MODE`.

Для Codex с namespace-инструментами и потенциальными параллельными вызовами `/v2` предпочтителен. В v1 gpt2giga пропускает namespace-инструменты (`continue` при `api_mode != "v2"`), поэтому `multi_agent_v1` в v1 просто не дойдёт до GigaChat. Если `[agents] enabled = false` уже стоит, это не критично, но `/v2` остаётся более полным маршрутом.

Если `/v2` по какой-то причине не работает с вашей моделью/аккаунтом, можно временно переопределить:

```bash
codex --profile gigachat --strict-config exec \
  -c model_providers.gigachat.base_url=http://127.0.0.1:8090/v1 \
  --skip-git-repo-check --sandbox read-only --ephemeral \
  "Ответь OK"
```

Но это compatibility-режим: namespace-инструменты будут пропущены, а параллельные вызовы — под вопросом. Для GigaChat-3-Pro/Ultra и multi-agent нужен `/v2`.

### 5.5. Почему `127.0.0.1`, а не `localhost`

`localhost` может разрешиться в IPv6 `::1`, тогда как gpt2giga по умолчанию слушает IPv4 `127.0.0.1`. Это классическая причина `connection refused`. Используйте `127.0.0.1` для детерминизма; если gateway слушает IPv6, укажите `http://[::1]:8090/v2`.

---

## 6. Установка, проверка и запуск без ключей

### 6.1. Переменные, которые нужно различать

| Переменная | Где используется | Секрет? | Назначение |
|---|---|---|---|
| `GIGACHAT_CREDENTIALS` | gpt2giga | Да | OAuth-ключ GigaChat для upstream |
| `GIGACHAT_SCOPE` | gpt2giga | Нет | `GIGACHAT_API_PERS` или `GIGACHAT_API_CORP` по типу аккаунта |
| `GIGACHAT_MODEL` | gpt2giga | Нет | Модель upstream, например `GigaChat-2-Max` |
| `GPT2GIGA_API_KEY` | gpt2giga и Codex | Да | Клиентский ключ шлюза; Codex читает его по `env_key` |
| `GPT2GIGA_PASS_MODEL` | gpt2giga | Нет | `False` — всегда использовать `GIGACHAT_MODEL` |
| `GPT2GIGA_GIGACHAT_API_MODE` | gpt2giga | Нет | Версия для root routes; `/v2` в base_url важнее |
| `GPT2GIGA_NORMALIZE_TOOL_SCHEMAS` | gpt2giga | Нет | Lossy-нормализация только для GigaChat-2 и `api.giga.chat/v1` |

Codex видит только `GPT2GIGA_API_KEY`; upstream-ключ `GIGACHAT_CREDENTIALS` остаётся у gpt2giga. Это правильное разделение: если Codex скомпрометирован, он не получает ключ GigaChat.

### 6.2. Запуск gpt2giga без записи секретов в историю

```bash
# В терминале, не в Git.
read -r -s -p "GIGACHAT_CREDENTIALS: " GIGACHAT_CREDENTIALS; echo
export GIGACHAT_CREDENTIALS

read -r -s -p "GPT2GIGA_API_KEY: " GPT2GIGA_API_KEY; echo
export GPT2GIGA_API_KEY

export GIGACHAT_SCOPE="GIGACHAT_API_PERS"   # или GIGACHAT_API_CORP
export GIGACHAT_MODEL="GigaChat-2-Max"
export GPT2GIGA_PASS_MODEL="False"
export GPT2GIGA_GIGACHAT_API_MODE="v2"
export GPT2GIGA_NORMALIZE_TOOL_SCHEMAS="True"
export GPT2GIGA_ENABLE_API_KEY_AUTH="True"
export GPT2GIGA_HOST="127.0.0.1"
export GPT2GIGA_PORT="8090"

gpt2giga
```

Проверка здоровья в другом терминале:

```bash
curl -fsS http://127.0.0.1:8090/health
```

Ожидается HTTP 200 и JSON со статусом. Если `/health` недоступен, Codex не нужно запускать: сначала поднимите gateway.

Важно про `GPT2GIGA_NORMALIZE_TOOL_SCHEMAS`:

- включайте `True` только если upstream — GigaChat-2 и `GIGACHAT_BASE_URL` равен `https://api.giga.chat/v1` (слэш допустим);
- при другом base URL (staging, корпоративный gateway) флаг не действует;
- для GigaChat-3 и неизвестных моделей флаг не действует;
- флаг lossy: он может сузить типы и ослабить ограничения; это не замена валидации JSON Schema.

Если вы не уверены в base URL, начните с `False` (native schemas), прогоните валидацию функций и включайте `True` только при доказанной необходимости.

### 6.3. Установка профилей

Прямые команды без скрипта:

```bash
install -d "${CODEX_HOME:-$HOME/.codex}"
install -m 600 .codex/profiles/gigachat.config.toml \
  "${CODEX_HOME:-$HOME/.codex}/gigachat.config.toml"
install -m 600 .codex/profiles/gigachat-notools.config.toml \
  "${CODEX_HOME:-$HOME/.codex}/gigachat-notools.config.toml"
```

Проверка прав:

```bash
stat -c '%a %n' "${CODEX_HOME:-$HOME/.codex}"/gigachat*.config.toml
# ожидается 600 для обоих файлов
```

На macOS `stat -c` может не работать; используйте `stat -f '%Lp %N'`.

Через скрипт:

```bash
tools/codex-gigachat.sh install
tools/codex-gigachat.sh check
```

`install` отказывается перезаписывать существующий профиль без `--force`; с `--force` делает резервную копию с timestamp. `check` выполняет strict-config проверку без ключа (см. 6.5).

Если Codex установлен локально через npm и не находится в `PATH`, задайте `CODEX_BIN`:

```bash
CODEX_BIN=./node_modules/.bin/codex tools/codex-gigachat.sh check
```

Итоговые TOML-файлы из этого документа были проверены именно так: установлены в отдельный `CODEX_HOME` с режимом `600` и прогнаны через `--strict-config` без ключа. Оба профиля прошли проверку; ошибка была только про отсутствие `GPT2GIGA_API_KEY`. С mock-сервером основной профиль отправил 10 инструментов (7 функций + 3 MCP resource tools, без `multi_agent_v1`), а fallback — 5 инструментов (3 MCP + `request_user_input` + `view_image`). Это подтверждает конфигурацию и состав payload, но не поведение GigaChat.

### 6.4. Аудит проектного конфига

```bash
python3 tools/check-codex-project-config.py .codex/config.toml
```

Скрипт проверяет только верхний уровень и завершается с ошибкой, если находит `model`, `model_provider`, `model_providers`, `profile` или `profiles`. Он не меняет файл и не проверяет MCP-секреты; это отдельная задача.

### 6.5. Strict-config проверка без ключа

```bash
env -u GPT2GIGA_API_KEY codex --profile gigachat --strict-config exec \
  --skip-git-repo-check --sandbox read-only --ephemeral \
  "Ответь OK"
```

Ожидаемый результат — ошибка ровно про отсутствие переменной:

```
ERROR: Missing environment variable: `GPT2GIGA_API_KEY`.
```

Это доказывает, что TOML разобран и все поля распознаны. Если вместо этого появляется:

```
Error loading config.toml: ... unknown configuration field ...
```

значит, профиль содержит поле, которого нет в 0.172.0. Исправьте профиль, а не отключайте `--strict-config`.

Если ошибка указывает на `.codex/config.toml` с номером строки — это неизвестное поле в проектном конфиге. Не удаляйте его вслепую: выясните происхождение. `--strict-config` валидирует объединённый конфиг, и это его полезное свойство.

### 6.6. Базовый текстовый тест (L1)

```bash
codex --profile gigachat --strict-config exec \
  --skip-git-repo-check --sandbox read-only --ephemeral \
  "Ответь одним словом: OK"
```

Ожидается exit code 0 и `OK` в stdout. Это ещё не доказательство агентной работоспособности; это только транспорт, авторизация и обычный Responses-ответ.

### 6.7. Streaming/JSONL тест (L2)

```bash
codex --profile gigachat --strict-config exec --json \
  --skip-git-repo-check --sandbox read-only --ephemeral \
  "Ответь одним словом: OK" \
  > /tmp/codex-gigachat-l2.jsonl 2> /tmp/codex-gigachat-l2.err
```

Проверка событий:

```bash
python3 - <<'PY'
import json
events = [json.loads(line) for line in open('/tmp/codex-gigachat-l2.jsonl') if line.strip()]
types = [e.get('type') for e in events]
print(types)
assert 'turn.completed' in types, 'no turn.completed'
assert 'turn.failed' not in types, 'turn.failed'
assert any(
    e.get('type') == 'item.completed'
    and (e.get('item') or {}).get('type') == 'agent_message'
    for e in events
), 'no agent_message'
print('L2 OK')
PY
```

`item.completed` с `item.type = "error"` может появиться как предупреждение о метаданных модели (`Model metadata ... not found`). Это не фатально; фатален `turn.failed`. Если `turn.failed` есть, смотрите `/tmp/codex-gigachat-l2.err` и JSONL.

### 6.8. Tool loop тест (L3)

Без записи в репозиторий, в read-only sandbox:

```bash
codex --profile gigachat --strict-config exec --json \
  --skip-git-repo-check --sandbox read-only --ephemeral \
  "Выполни команду printf OK и покажи её вывод" \
  > /tmp/codex-gigachat-l3.jsonl 2> /tmp/codex-gigachat-l3.err
```

Проверка:

```bash
python3 - <<'PY'
import json
events = [json.loads(line) for line in open('/tmp/codex-gigachat-l3.jsonl') if line.strip()]
completed = [
    e for e in events
    if e.get('type') == 'item.completed'
    and (e.get('item') or {}).get('type') == 'command_execution'
]
assert completed, 'no command_execution item'
assert any((e.get('item') or {}).get('exit_code') == 0 for e in completed), 'command failed'
assert any(
    e.get('type') == 'item.completed'
    and (e.get('item') or {}).get('type') == 'agent_message'
    for e in events
), 'no final agent_message'
assert any(e.get('type') == 'turn.completed' for e in events), 'no turn.completed'
assert not any(e.get('type') == 'turn.failed' for e in events), 'turn.failed'
print('L3 OK:', len(completed), 'command item(s)')
PY
```

Ожидаемая последовательность типов событий (поля могут отличаться, важны типы):

```
thread.started
item.completed (error — предупреждение о метаданных, необязательно)
turn.started
item.started   (command_execution)
item.completed (command_execution, exit_code=0, aggregated_output="OK")
item.completed (agent_message)
turn.completed
```

Если `exec_command` не вызывается, а модель сразу отвечает текстом, это L1/L2, но не L3. Для диагностики можно усилить промпт: «Ты обязан вызвать exec_command ровно один раз; не отвечай, пока не получишь вывод». Но это не должно быть единственным доказательством: проверяйте JSONL.

Для теста записи используйте отдельный временный каталог или одноразовую ветку:

```bash
tmpdir="$(mktemp -d)"
cd "$tmpdir"
codex --profile gigachat --strict-config exec --json \
  --skip-git-repo-check --sandbox workspace-write --ephemeral \
  "Создай файл proof.txt с содержимым OK и покажи его" \
  > /tmp/codex-gigachat-l3w.jsonl 2> /tmp/codex-gigachat-l3w.err
```

Не запускайте workspace-write в рабочем дереве Panadonik до прохождения L3.

### 6.9. MCP-тест (L4)

Сначала убедитесь, что проект доверенный и MCP-серверы запускаются. Затем:

```bash
codex --profile gigachat --strict-config exec --json \
  --skip-git-repo-check --sandbox read-only --ephemeral \
  "Перечисли доступные MCP-ресурсы проекта и покажи их названия" \
  > /tmp/codex-gigachat-l4.jsonl 2> /tmp/codex-gigachat-l4.err
```

Проверьте наличие `mcp_tool_call` или `command_execution` с вызовом MCP-инструмента, а также `turn.completed`. Если MCP-сервер не имеет ресурсов, тест может вернуть пустой список — это не ошибка, но и не доказательство работы конкретного инструмента. Для реального Panadonik нужен тест на реальном MCP-сервере с известным ресурсом/инструментом.

Для изоляции L3 от MCP можно временно отключить конкретный сервер:

```bash
codex --profile gigachat --strict-config exec --json \
  -c mcp_servers.example.enabled=false \
  --skip-git-repo-check --sandbox read-only --ephemeral \
  "Выполни printf OK и покажи вывод"
```

Это проверено: отключение MCP-сервера через `-c` убирает его инструменты из payload, не меняя проектный файл.

### 6.10. Проверка, что основной профиль не изменён

До и после установки сравните пользовательский конфиг:

```bash
sha256sum "${CODEX_HOME:-$HOME/.codex}/config.toml"
```

Установщик не пишет в `config.toml`; он создаёт только `gigachat*.config.toml`. Если хэш изменился, разбирайтесь: возможно, вы запускали `codex` без `--profile` или использовали `/model`.

Проверка, что без `--profile` используется прежняя модель/провайдер, требует реального запуска основного профиля (может расходовать кредиты OpenAI). В CI это не обязательно; достаточно зафиксировать, что `config.toml` не изменён и все новые файлы лежат отдельно.

### 6.11. Windows

Скрипт `tools/codex-gigachat.sh` — POSIX (Linux/macOS/WSL). На Windows используйте WSL или эквивалент PowerShell:

```powershell
$codexHome = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $HOME ".codex" }
New-Item -ItemType Directory -Force -Path $codexHome | Out-Null
Copy-Item .codex\profiles\gigachat.config.toml (Join-Path $codexHome "gigachat.config.toml") -Force
```

Права `0600` в Windows-семантике не работают; ограничьте доступ ACL. Для воспроизводимости лучше WSL.

---

## 7. Почему официальный гид gpt2giga нельзя копировать дословно

Официальный `integrations/codex/README.md` gpt2giga делает gpt2giga **профилем по умолчанию**: он предлагает положить `model`, `model_provider` и `[model_providers.gpt2giga]` в `~/.codex/config.toml`. Для Panadonik это неприемлемо: задача требует не менять основной профиль.

Правильная адаптация:

| Официальный гид | Для Panadonik |
|---|---|
| `~/.codex/config.toml` с `model_provider = "gpt2giga"` | `$CODEX_HOME/gigachat.config.toml` с `model_provider = "gigachat"` |
| Влияет на все запуски `codex` | Влияет только на `codex --profile gigachat` |
| `base_url = http://localhost:8090/v2` | `base_url = http://127.0.0.1:8090/v2` |
| `[projects."/path"].trust_level = "trusted"` в user config | Доверие через `codex exec --trust`; не коммитить в проект |
| Модель `GigaChat-3-Ultra` | Baseline `GigaChat-2-Max`; 3-Ultra — отдельный эксперимент |

Официальный гид проверен на `codex-cli 0.142.1` (26.06.2026), а не на 0.172.0. Это ещё одна причина не считать его достаточным доказательством для нашей версии: поля и поведение могли измениться. `model_max_output_tokens` — пример такого изменения.

---

## 8. Критерии приёмки и релиза

### 8.1. Можно ли считать интеграцию рабочей без streaming + tool calling?

**Нет.** Ответ Responses API текстом (L1) доказывает только:
- gpt2giga запущен;
- ключ `GPT2GIGA_API_KEY` принят;
- Codex умеет отправить запрос и разобрать обычный ответ.

Он не доказывает:
- что Codex получает корректный SSE-поток и завершает turn;
- что GigaChat принимает схемы инструментов;
- что GigaChat генерирует валидные аргументы `exec_command`;
- что Codex выполняет инструмент и отправляет `function_call_output` обратно;
- что второй запрос с историей tool call принимается;
- что финальный `agent_message` формируется после tool output.

Без L3 это **chat-only прокси**, а не интеграция Codex. Для production нужен L4: многошаговый цикл или MCP-инструмент.

### 8.2. Уровни приёмки

| Уровень | Что доказывает | Команда | Критерий прохождения |
|---|---|---|---|
| **L0. Конфиг** | Профиль и проектный конфиг распознаны; секретов нет | `env -u GPT2GIGA_API_KEY codex --profile gigachat --strict-config exec ...` | Ошибка только `Missing environment variable: GPT2GIGA_API_KEY`; нет `unknown configuration field`; `stat` показывает 600 |
| **L1. Транспорт** | Gateway, auth, обычный Responses | `codex --profile gigachat --strict-config exec ... "Ответь OK"` | Exit 0; stdout `OK` |
| **L2. Streaming** | SSE и события Codex | `codex --profile gigachat --strict-config exec --json ...` | `turn.completed`, `agent_message`, нет `turn.failed` |
| **L3. Tool loop** | GigaChat → `exec_command` → output → финал | `codex --profile gigachat --strict-config exec --json --sandbox read-only ... "printf OK"` | `command_execution` с `exit_code = 0`; `aggregated_output` содержит `OK`; финальный `agent_message`; `turn.completed` |
| **L4. Multi-step/MCP** | Повторный цикл, MCP или namespace | Промпт на 2 команды или MCP-ресурс | 2+ tool calls или `mcp_tool_call`; нет 422; аргументы не обрезаны; `turn.completed` |
| **L5. Изоляция** | Основной профиль и проектный MCP не сломаны | Сравнить `config.toml`; запустить MCP-тест; `git grep` секретов | `config.toml` не изменён; MCP работает; в Git нет ключей; `.env` ignored |

### 8.3. Release gate

Интеграция может быть помечена как «рекомендована» только после выполнения всех пунктов:

- [ ] L0–L4 пройдены на реальном gpt2giga + реальном GigaChat, а не на mock.
- [ ] Зафиксированы версии: Codex CLI 0.172.0, gpt2giga 0.3.2, модель `GigaChat-2-Max`, base URL `/v2`.
- [ ] Все инструменты из payload (включая MCP Panadonik) прошли проверку схем; результаты `/functions/validate` или эквивалента сохранены.
- [ ] `additionalProperties: false` не вызывает 422, либо есть документированный обходной путь.
- [ ] `multi_agent_v1` либо отключён (`[agents] enabled = false`), либо проверен на `/v2`.
- [ ] `parallel_tool_calls = true` не ломает GigaChat-2-Max, либо зафиксировано, что параллельность не поддерживается и работа идёт последовательно.
- [ ] `function_call_output` без `name` работает в реальном цикле; проверены resume/compaction.
- [ ] Проектный `.codex/config.toml` не содержит `model`/`model_provider`/`model_providers`; аудит проходит.
- [ ] Основной `~/.codex/config.toml` не изменён; хэш до/после совпадает.
- [ ] В Git нет секретов; `GPT2GIGA_API_KEY` и `GIGACHAT_CREDENTIALS` передаются только через окружение/секрет-менеджер.
- [ ] Есть rollback: удалить `$CODEX_HOME/gigachat*.config.toml`; репозиторий не меняется.
- [ ] Зафиксирован план повторного теста при обновлении Codex или gpt2giga.

L0 можно запускать в обычном CI без секретов: он проверяет только парсинг профиля и ожидаемо падает на `Missing environment variable`. L1–L4 требуют запущенного gateway и ключей, поэтому их место — отдельный protected job или nightly-запуск, а не PR из форков. Секреты в CI должны быть masked; логи gpt2giga с промптами не должны публиковаться как артефакты.

### 8.4. Что считать «release» для Panadonik

Релиз — это не «модель ответила». Релиз — это воспроизводимый профиль, который:
1. не меняет default;
2. не ломает MCP;
3. проходит L3 на реальном GigaChat;
4. имеет fallback;
5. имеет документированные ограничения и неподтверждённые места.

Если L3 не проходит, профиль можно оставить как экспериментальный (`docs/experiments/`), но не включать в recommended workflow и не добавлять в CI как обязательный.

---

## 9. Риски схем инструментов GigaChat-2/3

### 9.1. Что именно отправляет Codex 0.172.0

Захваченный payload на `POST /v1/responses` (read-only и workspace-write) содержит:

| Параметр | Значение |
|---|---|
| `stream` | `true` |
| `tool_choice` | `auto` |
| `parallel_tool_calls` | `true` |
| `reasoning` | `{effort: "none", summary: "auto"}` |
| `store` | `false` |
| `include` | `["reasoning.encrypted_content"]` |
| `prompt_cache_key` | присутствует |
| `client_metadata` | присутствует |

Базовый набор без MCP — 8 инструментов:

- функции `exec_command`, `write_stdin`, `request_user_input`, `view_image`, `get_goal`, `create_goal`, `update_goal` (все `strict: false`);
- namespace `multi_agent_v1` с 5 вложенными функциями (`close_agent`, `resume_agent`, `send_input`, `spawn_agent`, `wait_agent`).

С пустым MCP-сервером добавляются 3 resource-инструмента: `list_mcp_resources`, `list_mcp_resource_templates`, `read_mcp_resource` — итого 11. С `[agents] enabled = false` namespace исчезает — 10. Если дополнительно поставить `[features] goals = false` — 7. С `[features] shell_tool = false` + `goals = false` + `agents = false` — 5.

Статистика схем (базовый payload): 0 `anyOf`, 0 `oneOf`, 0 `allOf`, 0 `$ref`, 0 type-массивов; 16 `additionalProperties: false`; 12 `strict: false`. С MCP и включённым `multi_agent_v1`: 19 `additionalProperties: false`, 15 `strict: false`.

Это важный вывод: **основной риск Codex 0.172.0 — не union-схемы, а `additionalProperties: false`**. Нормализатор gpt2giga, который чинит `anyOf`/`oneOf`, для этого payload может почти ничего не изменить.

### 9.2. `additionalProperties: false`

| Аспект | Факт |
|---|---|
| Есть ли в payload Codex | Да, 16 раз в базовом наборе, 19 с MCP и включённым `multi_agent_v1` |
| Удаляет ли gpt2giga | Нет: `normalize_tool_schema` рекурсивно обходит `additionalProperties`, но не удаляет ключ |
| Подтверждён ли отказ GigaChat | Нет. Официальная документация GigaChat не заявляет поддержку/запрет; issue langchain-gigachat #55 доказывает строгость к `items`/`properties`, но не к `additionalProperties` |
| Как проверить | `POST https://api.giga.chat/v1/functions/validate` для каждой схемы; затем реальный L3 |
| Что делать при отказе | Не патчить gpt2giga в production без форка. Варианты: upstream-фикс/PR, узкий shim перед gpt2giga, сокращение набора инструментов, отказ от проблемного MCP-сервера |

Официальный `POST /functions/validate` — это лучший способ проверить конкретную схему без генерации. Он возвращает список ошибок и предупреждений; ошибки нужно исправлять, предупреждения — по возможности. Если схема Codex проходит валидатор, это сильный, но не абсолютный аргумент: валидатор проверяет описание функции, а не поведение модели.

Важно: `/functions/validate` — официальный endpoint GigaChat (`https://api.giga.chat/v1/functions/validate`). В gpt2giga main (0.3.3a1) есть прокси-маршрут `POST /bridge/gigachat/functions/validate`, но в стабильной 0.3.2 его нет. Для production-проверки используйте официальный endpoint с короткоживущим токеном или отдельный диагностический процесс 0.3.3a1, не основной gateway.

### 9.3. `strict: false`

| Аспект | Факт |
|---|---|
| Есть ли в payload | Да, 12 раз в базовом наборе |
| Что делает gpt2giga 0.3.2 | Преобразует top-level `strict` в `raw_extensions = {"function": {"strict": false}}` и удаляет точное значение `false` через `_strip_default_function_strict` |
| Риск для 0.172.0 | Низкий: Codex отправляет именно `false` |
| Риск на будущее | Если Codex начнёт отправлять `strict: true`, gpt2giga его сохранит; GigaChat может отвергнуть |

Вывод: для текущей версии `strict: false` — не главный риск. Но версия Codex должна быть зафиксирована; при обновлении проверьте, не появился ли `strict: true` или новые поля.

### 9.4. Namespace `multi_agent_v1`

| Маршрут | Поведение gpt2giga | Последствие |
|---|---|---|
| `/v1/responses` | Namespace-инструменты пропускаются (`continue` при `api_mode != "v2"`) | Codex не увидит multi-agent tools; ошибки нет, но функция недоступна |
| `/v2/responses` | Namespace разворачивается в плоские функции с именами вида `multi_agent_v1__spawn_agent` | GigaChat видит 5 дополнительных функций; нужен реальный тест |

В 0.172.0 `[agents] enabled = false` полностью убирает namespace из payload — это самый безопасный default для GigaChat-2. Если multi-agent нужен, используйте `/v2` и тестируйте отдельно; не полагайтесь на v1.

Официальные требования GigaChat к имени функции: только латинские буквы, не начинать с цифры. Примеры GigaChat используют подчёркивания (`weather_forecast`, `send_sms`), а `multi_agent_v1__spawn_agent` содержит цифру и подчёркивания. Формально это может быть допустимо (запрещено только начинать с цифры), но реальная валидация не проверялась. Если `[agents] enabled = false`, вопрос снимается.

### 9.5. `parallel_tool_calls = true`

В захваченных payload Codex 0.172.0 всегда было `parallel_tool_calls = true`; конфигурации Codex, которая бы выключала это поле, в документации не найдено.

Официальная документация GigaChat `POST /chat/completions` говорит: параллельная работа с функциями доступна только через `/v2/chat/completions` и `model_options.parallel_tool_calls`, и только для модели `GigaChat-3-Ultra`.

При этом changelog gpt2giga 0.3.2 заявляет поддержку параллельных вызовов для GigaChat-2-Max через v2. Это противоречие между официальной документацией GigaChat и релизными заметками gpt2giga. Для Panadonik это означает:

- не требуйте параллельности для GigaChat-2-Max в критериях L3;
- L3 должен проходить с последовательным вызовом;
- если GigaChat-2-Max отвергает `parallel_tool_calls=true`, зафиксируйте это как ограничение и рассмотрите GigaChat-3-Ultra или upstream-фикс;
- не пытайтесь «выключить» поле через неизвестный TOML-ключ: `--strict-config` его отвергнет.

### 9.6. `function_call_output` без `name`

Codex отправляет `function_call_output` с `call_id`, но без `name`. gpt2giga это умеет: трансформер запоминает `function_call` по `call_id` и восстанавливает имя. Если в истории нет соответствующего `function_call`, gpt2giga возвращает ошибку:

```
GigaChat requires the function name for a tool result. Include the matching
`function_call` item in `input`, or provide `name` on the `function_call_output` item.
```

Практические следствия:

- первый tool loop должен работать: Codex отправляет `function_call` и `function_call_output` вместе;
- после compaction/resume история может быть неполной; это отдельный тест;
- для CI используйте `--ephemeral`, чтобы не зависеть от сохранённых rollout;
- если ошибка появляется, проверьте, не обрезает ли Codex историю tool call.

### 9.7. Область действия `GPT2GIGA_NORMALIZE_TOOL_SCHEMAS`

Официальная документация gpt2giga:

- флаг действует только для моделей GigaChat-2 (включая Pro/Max);
- только при `GIGACHAT_BASE_URL=https://api.giga.chat/v1` (слэш допустим);
- для других base URL (staging, корпоративный gateway) схемы сохраняются как есть;
- для GigaChat-3 и неизвестных моделей схемы сохраняются как есть;
- по умолчанию `False`;
- конверсия lossy: объединяет `anyOf`/`oneOf`, берёт первый non-null тип, добавляет пустой `properties` объектам без свойств; `$ref` не разворачивает, `allOf` не объединяет, другие неподдерживаемые ключи могут быть отвергнуты upstream.

Практическая рекомендация:

1. Проверьте `echo "$GIGACHAT_BASE_URL"` на gateway. Если это не `https://api.giga.chat/v1`, флаг бесполезен.
2. Для GigaChat-2-Max начните с `False`; если `/functions/validate` или реальный запрос показывает 422 на union-схемы, включите `True`.
3. Для GigaChat-3-Ultra/Pro не включайте флаг в надежде на исправление: он не действует.
4. Не считайте `True` исправлением `additionalProperties`/`$ref`/`allOf`.

### 9.8. GigaChat-3-Pro и GigaChat-3-Ultra

**GigaChat-3-Pro.** На официальной странице моделей GigaChat (`https://developers.sber.ru/docs/ru/gigachat/models`) перечислены GigaChat-3-Ultra, GigaChat-2-Max, GigaChat-2-Pro, GigaChat-2-Lite. GigaChat-3-Pro там отсутствует. Модель встречается во вторичных источниках и прессе, но официальной API-страницы не найдено. Вывод: **не закладывать в коммитируемый профиль и не делать release target**. Если аккаунт принимает это имя, это экспериментальный алиас/приватная модель; проверяйте отдельно и не публикуйте как рекомендацию.

**GigaChat-3-Ultra.** Официально доступна физлицам во freemium, base URL `https://api.giga.chat/`, методы `/v1/chat/completions` и `/v2/chat/completions`. Контекст на официальной странице модели не опубликован; вторичные источники говорят о ~128k, но это не подтверждено. Поэтому:

- не ставьте `model_context_window = 128000` для 3-Ultra без измерения;
- если хотите эксперимент, сделайте отдельный локальный профиль (не коммитить) или переопределите `-c model=GigaChat-3-Ultra` и не задавайте контекст;
- `GPT2GIGA_PASS_MODEL=False` + `GIGACHAT_MODEL=GigaChat-3-Ultra` — способ переключить upstream без изменения Codex-профиля, но тогда Codex `model` и upstream model могут расходиться; для чистоты синхронизируйте оба.

### 9.9. `/model` и проектный `model`

Два независимых риска:

1. **TUI `/model`** (issue #35487, 0.142.2): может перезаписать модель кастомного профиля, сохранив провайдер. В 0.172.0 не проверено. Не открывайте `/model` в профиле `gigachat`; используйте `--profile`, `-m` или `-c model=`.
2. **Проектный `model`**: имеет приоритет над профилем (проверено). Держите проектный конфиг MCP-only; используйте обёртку с `-c model=GigaChat-2-Max`; добавьте аудит в CI.

### 9.10. Реальные MCP-инструменты Panadonik

Наш тест с `[mcp_servers.echo]` даёт только 3 resource-инструмента, потому что у echo нет собственных tools. Реальные MCP-серверы Panadonik могут добавить десятки инструментов с произвольными JSON Schema: `anyOf`, `oneOf`, `$ref`, `additionalProperties`, вложенные объекты. Это отдельный, более высокий риск, чем базовые 8 инструментов Codex.

Меры:

- для первого L3 отключите MCP-серверы через `-c mcp_servers.<id>.enabled=false` и проверьте чистый shell-tool loop;
- затем включайте MCP по одному и проверяйте L4;
- используйте `enabled_tools`/`disabled_tools` в конфиге MCP-сервера, чтобы ограничить набор инструментов;
- прогоняйте схемы MCP-инструментов через `/functions/validate`;
- если MCP-инструмент имеет сложную схему и ломает GigaChat, не тащите его в общий профиль; оставьте отдельный профиль/сервер.

Codex 0.172.0 поддерживает `mcp_servers.<id>.enabled_tools` (allow list) и `disabled_tools` (deny list, применяется после allow list). Это документированные поля, их можно использовать в проектном конфиге, но не в рамках этой задачи, если они меняют существующий MCP.

### 9.11. Сводная таблица рисков

| Риск | Доказательство | Влияние | Мера |
|---|---|---|---|
| `additionalProperties: false` | Payload 0.172.0; нормализатор gpt2giga не удаляет | Возможен 422 от GigaChat | `/functions/validate`; при отказе — upstream-фикс/shim/сокращение tools; не считать флаг исправлением |
| `strict: false` | Payload; gpt2giga 0.3.2 удаляет точный `false` | Низкое для 0.172.0 | Зафиксировать версию; при `strict: true` перепроверить |
| `multi_agent_v1` namespace | Payload; v1 пропускает, v2 flatten | Несовместимость имён/схем | `[agents] enabled = false`; если нужен — `/v2` + отдельный тест |
| `parallel_tool_calls = true` | Payload; GigaChat official only 3-Ultra/v2 | GigaChat-2 может игнорировать/отвергать | L3 без параллельности; 3-Ultra для параллельности; upstream-фикс |
| `function_call_output` без `name` | Payload; gpt2giga восстанавливает по `call_id` | 400 при неполной истории | Сохранять `function_call`; тестировать resume/compaction |
| `GPT2GIGA_NORMALIZE_TOOL_SCHEMAS` scope | Docs gpt2giga | Ложное чувство исправления для 3/custom base | Проверить `GIGACHAT_BASE_URL`; не включать для 3 |
| GigaChat-3-Pro отсутствует официально | Страница моделей | 404/непредсказуемое поведение | Не коммитить; 2-Max baseline; 3-Ultra эксперимент |
| 3-Ultra context неизвестен | Официальная страница не публикует | Неверная компакция | Не задавать 128k; измерить |
| `/model` overwrite | Issue #35487 (0.142.2) | Профиль теряет модель | Не открывать `/model`; wrapper `-c model=` |
| Проектный `model` | Проверено в 0.172.0 | Молчаливая подмена модели | MCP-only проект; wrapper `-c model=`; аудит |
| Реальные MCP tools | Не проверены | Сложные схемы, 422 | Отключать на L3; включать по одному; validate |
| Версионный дрейф | Guide 0.142.1 vs 0.172.0 | Поля/события меняются | Pin версии; L0–L4 при каждом обновлении |

---

## 10. Безопасный fallback

### 10.1. Уровень 1: read-only основной профиль

Основной профиль уже содержит `sandbox_mode = "read-only"`. Это означает:

- `codex --profile gigachat` по умолчанию не может писать в репозиторий;
- для чтения кода и анализа этого достаточно;
- для редактирования нужно явно передать `--sandbox workspace-write`, что видно в командной строке и логах;
- `approval_policy` не зафиксирован в основном профиле, чтобы TUI мог спрашивать подтверждение; `codex exec` и так работает с `approval: never` в неинтерактивном режиме.

Проверено: `--sandbox workspace-write` перекрывает `sandbox_mode = "read-only"` профиля (заголовок Codex показывает `sandbox: workspace-write`).

### 10.2. Уровень 2: `gigachat-notools`

Профиль `gigachat-notools`:

- отключает shell (`exec_command`, `write_stdin`);
- отключает goals (`get_goal`, `create_goal`, `update_goal`);
- отключает apps/plugins (в захваченном payload 0.172.0 они и так не появлялись, поэтому эффект не измерялся);
- отключает subagents (`multi_agent_v1`);
- оставляет `request_user_input`, `view_image` и, в доверенном проекте, MCP resource tools;
- работает в read-only с `approval_policy = "never"`.

Он подходит для:
- чата/суммаризации по контексту, который вы вставили в промпт;
- исследования, где не нужен shell;
- проверки, не ломают ли модели даже минимальный набор инструментов.

Он **не подходит** для:
- чтения файлов репозитория через shell (shell отключён);
- редактирования;
- полноценного Codex-агента.

Если запустить его вне доверенного проекта, остаются только 2 инструмента (`request_user_input`, `view_image`). Это максимально близко к «no-tools», но всё равно не ноль. Полностью убрать tools в 0.172.0 через конфиг не удалось.

### 10.3. Уровень 3: chat-only вне Codex

Если GigaChat отвергает даже минимальные схемы, самый безопасный fallback — не использовать Codex для этой задачи:

- отправить запрос напрямую в GigaChat через официальный API/SDK;
- или использовать gpt2giga `/v2/chat/completions` с простым клиентом;
- или использовать Codex с пустым/минимальным набором инструментов в отдельном каталоге.

Это честнее, чем выдавать chat-only за «работающий Codex».

### 10.4. Rollback

Rollback предельно простой:

```bash
rm -f "${CODEX_HOME:-$HOME/.codex}/gigachat.config.toml" \
      "${CODEX_HOME:-$HOME/.codex}/gigachat-notools.config.toml"
```

Проектный `.codex/config.toml` и основной `~/.codex/config.toml` не меняются, поэтому откат не требует правок репозитория. Если использовался `--trust`, доверие проекту остаётся в пользовательском состоянии Codex; при необходимости его можно изменить отдельно.

### 10.5. Безопасность данных

При работе через GigaChat в модель уходят промпты, фрагменты кода и вывод инструментов. Это не «локальная» обработка, даже если gpt2giga запущен на `127.0.0.1`: upstream-запрос уходит в GigaChat. Поэтому:

- не запускайте профиль на репозитории с секретами в открытом виде; `.env`, токены и приватные ключи должны быть исключены;
- по умолчанию используйте `sandbox_mode = "read-only"`; `workspace-write` — только на одноразовой ветке/в `mktemp -d`;
- не передавайте `GIGACHAT_CREDENTIALS` в Codex: он должен видеть только `GPT2GIGA_API_KEY`;
- держите gpt2giga на `127.0.0.1`, а не на `0.0.0.0`, и включайте `GPT2GIGA_ENABLE_API_KEY_AUTH=True`;
- логи gpt2giga могут содержать промпты и вывод инструментов; не ставьте `DEBUG` в общем CI и защищайте файлы логов;
- проверьте политику GigaChat по обработке данных для вашего аккаунта (физлицо/корпоративный) до запуска на коде Panadonik.

Эти меры не заменяют юридическую/политическую оценку допустимости отправки кода в GigaChat; они лишь уменьшают техническую поверхность утечки.

---

## 11. План внедрения по шагам

1. **Добавить файлы без изменения `.codex/config.toml`:**
   - `.codex/profiles/gigachat.config.toml`;
   - `.codex/profiles/gigachat-notools.config.toml`;
   - `tools/codex-gigachat.sh`;
   - `tools/check-codex-project-config.py`;
   - `docs/codex-gigachat.md` (краткая версия этого документа).

2. **Проверить проектный конфиг:** `python3 tools/check-codex-project-config.py .codex/config.toml`. Если есть `model`/`model_provider`, не удалять автоматически; зафиксировать и обсудить.

3. **Установить профили:** `tools/codex-gigachat.sh install`; проверить `stat` (600); выполнить `tools/codex-gigachat.sh check` без ключа.

4. **Поднять gpt2giga:** `GIGACHAT_MODEL=GigaChat-2-Max`, `GPT2GIGA_PASS_MODEL=False`, `GPT2GIGA_GIGACHAT_API_MODE=v2`, `GPT2GIGA_HOST=127.0.0.1`; проверить `/health`.

5. **Пройти L0–L2:** strict-config, текстовый ответ, JSONL `turn.completed`.

6. **Пройти L3:** `printf OK` в read-only; проверить `command_execution` с exit 0 и финальный `agent_message`. Если не проходит — остановиться, не включать в recommended.

7. **Пройти L3 с записью:** только в `mktemp -d` или одноразовой ветке; проверить `workspace-write`.

8. **Пройти L4:** MCP-ресурс или две команды; при необходимости отключать MCP через `-c mcp_servers.<id>.enabled=false` и включать по одному.

9. **Проверить изоляцию:** хэш `~/.codex/config.toml` не изменился; проектный MCP работает; `git grep` не находит секретов.

10. **Задокументировать результат:** версии, модель, base URL, результаты L0–L4, известные ограничения. Только после этого добавлять в README/CI как рекомендованный профиль.

11. **Rollback:** удалить два профиля из `$CODEX_HOME`; репозиторий не трогать.

---

## 12. Первичные источники

### 12.1. Codex CLI

- Configuration — Advanced: <https://developers.openai.com/codex/config-file/config-advanced>
- Configuration Reference: <https://developers.openai.com/codex/config-file/config-reference>
- Configuration — Basic: <https://developers.openai.com/codex/config-file/config-basic>
- CLI Reference: <https://developers.openai.com/codex/cli/reference>
- Non-interactive mode: <https://developers.openai.com/codex/non-interactive-mode>

### 12.2. gpt2giga

- Repository: <https://github.com/ai-forever/gpt2giga>
- Configuration: <https://github.com/ai-forever/gpt2giga/blob/main/docs/configuration.md>
- Codex integration guide: <https://github.com/ai-forever/gpt2giga/blob/main/integrations/codex/README.md>
- Chat Completions bridge: <https://ai-forever.github.io/gpt2giga/chat-completions-bridge>
- API compatibility: <https://ai-forever.github.io/gpt2giga/api-compatibility>
- Bridge compatibility: <https://ai-forever.github.io/gpt2giga/bridge-compatibility>
- Releases: <https://github.com/ai-forever/gpt2giga/releases>
- Operations: <https://github.com/ai-forever/gpt2giga/blob/main/docs/operations.md>

### 12.3. GigaChat

- Модели: <https://developers.sber.ru/docs/ru/gigachat/models>
- GigaChat-2-Max: <https://developers.sber.ru/docs/ru/gigachat/models/gigachat-2-max>
- GigaChat-3-Ultra: <https://developers.sber.ru/docs/ru/gigachat/models/gigachat-3-ultra>
- Описание пользовательских функций (JSON Schema, `/functions/validate`): <https://developers.sber.ru/docs/ru/gigachat/guides/functions/describing-custom-function>
- Режимы `function_call`: <https://developers.sber.ru/docs/ru/gigachat/guides/functions/function-calling-modes>
- `POST /chat/completions`: <https://developers.sber.ru/docs/ru/gigachat/api/reference/rest/post-chat>

### 12.4. Issues и эмпирические отчёты

- langchain-gigachat #55 (строгость GigaChat к `items`/`properties`): <https://github.com/ai-forever/langchain-gigachat/issues/55>
- openai/codex #35487 (`/model` overwrite, 0.142.2): <https://github.com/openai/codex/issues/35487>

---

## 13. Неподтверждённые предположения

Ниже — список мест, где вывод основан на коде, документации или аналогии, но не подтверждён реальным E2E с GigaChat. Перед релизом каждый пункт нужно либо проверить, либо оставить как известное ограничение.

1. **GigaChat-3-Pro существует и доступна через API.** Официальной страницы модели нет; на странице моделей GigaChat перечислены только 3-Ultra, 2-Max, 2-Pro, 2-Lite. Проверка: запросить модель у аккаунта/поддержки, посмотреть ответ `/models`, не коммитить имя до подтверждения.

2. **Контекст GigaChat-3-Ultra равен 128k.** Официальная страница не публикует контекст. Проверка: измерить фактическое поведение/документацию аккаунта; не задавать `model_context_window` без данных.

3. **GigaChat-2/3 принимает или отвергает `additionalProperties: false`.** Прямого подтверждения нет. Проверка: `POST /functions/validate` для каждой схемы и реальный L3.

4. **GigaChat принимает namespace-имена вида `multi_agent_v1__spawn_agent`.** gpt2giga их разворачивает, но реальная валидация GigaChat не проверялась. Проверка: L4 на `/v2` с включённым multi-agent; при отказе — `[agents] enabled = false`.

5. **GigaChat-2-Max поддерживает `parallel_tool_calls`.** Официальная документация GigaChat говорит только про 3-Ultra/v2; changelog gpt2giga заявляет 2-Max. Проверка: L3/L4 с параллельными вызовами; иначе считать последовательным.

6. **GigaChat принимает `function_call_output` без `name` при наличии предыдущего `function_call`.** gpt2giga восстанавливает имя по `call_id`; реальный GigaChat не проверялся. Проверка: L3 и тест resume/compaction.

7. **`model_reasoning_effort = "none"` безопасен для GigaChat.** gpt2giga принимает поле, но upstream-поведение не проверялось. Проверка: L1/L2; если 400 — убрать поле и проверить снова.

8. **`--strict-config` 0.172.0 принимает все поля профиля.** Проверено в песочнице для текущего набора; будущие версии могут добавить/убрать поля. Проверка: L0 при каждом обновлении.

9. **`codex exec --trust` не меняет основной профиль.** В тесте `config.toml` тестового `CODEX_HOME` остался пустым; доверие пишется в состояние Codex. Проверка: хэш `config.toml` до/после, `git diff` не применим.

10. **`GPT2GIGA_NORMALIZE_TOOL_SCHEMAS=True` не ломает Codex-схемы 0.172.0.** Код и payload указывают, что для текущих схем флаг почти no-op, но E2E не проверялся. Проверка: L3 с `True` и `False`, сравнить результаты.

11. **`base_url = /v2` работает с GigaChat-2-Max.** Официальный гид gpt2giga использует `/v2` для 3-Ultra; gpt2giga поддерживает v2 для 2-Max, но E2E не проверялся. Проверка: L1–L3 на `/v2`; при отказе — `/v1` compatibility.

12. **`/functions/validate` принимает схемы Codex.** Это официальный endpoint, но конкретные схемы не прогонялись. Проверка: отправить каждую схему с короткоживущим токеном; сохранить ошибки/предупреждения.

13. **Проектный `.codex/config.toml` Panadonik действительно MCP-only.** Это условие задачи; мы не читали реальный репозиторий. Проверка: аудит-скрипт и `git grep` перед внедрением.

14. **gpt2giga 0.3.2 совместим с Codex 0.172.0 на уровне SSE/tool events.** Официальный гид проверен на 0.142.1; 0.172.0 мог изменить события. Проверка: L2/L3 на реальном gateway; при отказе — тестировать 0.3.3a1 или ждать обновления.

15. **`[agents] enabled = false` убирает namespace и в будущих версиях.** Проверено в 0.172.0; поле может измениться. Проверка: L0 + анализ payload при обновлении.

16. **`--sandbox workspace-write` перекрывает `sandbox_mode = "read-only"` профиля.** Проверено заголовком Codex; запись в реальный файл не тестировалась в этом прогоне. Проверка: L3w в `mktemp -d`.

17. **MCP-инструменты Panadonik имеют совместимые схемы.** Реальные MCP-серверы не тестировались; наш `echo` не имеет tools. Проверка: L4 по одному серверу, `/functions/validate`.

18. **GigaChat-3-Ultra поддерживает функции в той же степени, что 2-Max.** gpt2giga guide использует 3-Ultra, но официальная страница модели не даёт полного описания function support. Проверка: L3 на 3-Ultra.

---

## 14. Итоговая рекомендация

Для Panadonik рекомендуется:

1. Хранить в Git **источники** двух именованных профилей в `.codex/profiles/` и устанавливать их в `$CODEX_HOME` скриптом. Не пытаться заставить Codex читать профиль из репозитория напрямую.

2. Оставить `.codex/config.toml` **MCP-only**. Не добавлять туда `model`, `model_provider`, `model_providers`. Добавить аудит, который ловит эти ключи.

3. Использовать основной профиль `gigachat` с `GigaChat-2-Max`, `/v2`, `model_reasoning_effort = "none"`, `web_search = "disabled"`, `sandbox_mode = "read-only"`, `[agents] enabled = false`.

4. Запускать через обёртку, которая передаёт `--profile gigachat`, `--strict-config`, `-c model_provider=gigachat`, `-c model=GigaChat-2-Max`. Это защищает от проектного `model` и от случайного переключения модели.

5. Не считать интеграцию рабочей до L3 на реальном GigaChat. L0–L2 — диагностика; L3 — минимум; L4 — production.

6. Не закладывать GigaChat-3-Pro в релиз. GigaChat-3-Ultra — отдельный эксперимент без фиксации контекста 128k.

7. Использовать `GPT2GIGA_NORMALIZE_TOOL_SCHEMAS` только для GigaChat-2 на `https://api.giga.chat/v1` и не считать его исправлением `additionalProperties`/`$ref`/`allOf`.

8. Иметь fallback `gigachat-notools` для read-only/no-shell исследования и честно называть его chat-only, а не полноценным агентом.

9. При любом обновлении Codex или gpt2giga повторять L0–L4 и проверять payload на новые поля/схемы.

10. Не коммитить секреты. `GPT2GIGA_API_KEY` и `GIGACHAT_CREDENTIALS` — только через окружение, `read -s`, секрет-менеджер или CI secrets. В Git допустимо только имя `GPT2GIGA_API_KEY` в `env_key`.
