#!/usr/bin/env node
// MCP-сервер (stdio) для делегирования задач агенту Manus через Manus API v2.
// Без зависимостей, Node.js >= 18. Ключ берётся только из MANUS_API_KEY и нигде не печатается.
//
// Переменные окружения:
//   MANUS_API_KEY          обязателен (Settings -> Developers в Manus)
//   MANUS_API_BASE         по умолчанию https://api.manus.ai
//   MANUS_DEFAULT_PROFILE  standard | lite | max | max-medium | max-high | max-extra | max-ultra (по умолчанию standard)
//   MANUS_MAX_RESULT_CHARS ограничение размера возвращаемого текста (по умолчанию 20000)

import readline from 'node:readline';

const BASE = (process.env.MANUS_API_BASE || 'https://api.manus.ai').replace(/\/+$/, '');
const DEFAULT_PROFILE = process.env.MANUS_DEFAULT_PROFILE || 'standard';
const MAX_CHARS = Number(process.env.MANUS_MAX_RESULT_CHARS || 20000);
const PROFILES = ['standard', 'lite', 'max', 'max-medium', 'max-high', 'max-extra', 'max-ultra'];

// Manus не должен получать секреты проекта: ключи подписи, пароли Wi-Fi, токены.
const SECRET_PATTERNS = [
  [/-----BEGIN [A-Z ]*PRIVATE KEY-----/, 'приватный ключ PEM'],
  [/\b(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b/, 'токен GitHub'],
  [/\bsk-[A-Za-z0-9_-]{20,}\b/, 'API-ключ'],
  [/\b(TVBOX_SIGNING_KEY|OTA_SIGNING_KEY|MANUS_API_KEY|RELEASE_SECRETS_PASSPHRASE)\s*[=:]\s*\S{6,}/, 'значение секрета'],
  [/\bwifi\.env\b[\s\S]{0,40}(PSK|PASSWORD|KEY)\s*=\s*\S+/i, 'учётные данные Wi-Fi'],
];

function checkSecrets(text) {
  for (const [re, name] of SECRET_PATTERNS) {
    if (re.test(text)) {
      throw new Error(`Отказ: в тексте задачи похоже на ${name}. Не передавайте секреты в Manus; уберите их и повторите.`);
    }
  }
}

async function api(method, path, { query, body } = {}) {
  const key = process.env.MANUS_API_KEY;
  if (!key) throw new Error('MANUS_API_KEY не задан в окружении процесса MCP-сервера.');
  const url = new URL(BASE + path);
  for (const [k, v] of Object.entries(query || {})) if (v !== undefined && v !== null) url.searchParams.set(k, String(v));
  const res = await fetch(url, {
    method,
    headers: { 'x-manus-api-key': key, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(60000),
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = null; }
  if (!res.ok || !json || json.ok === false) {
    const e = json && json.error ? `${json.error.code || ''} ${json.error.message || ''}`.trim() : text.slice(0, 300);
    throw new Error(`Manus API ${method} ${path}: HTTP ${res.status} ${e}${json && json.request_id ? ` (request_id ${json.request_id})` : ''}`);
  }
  return json;
}

function clip(s) {
  s = String(s ?? '');
  return s.length > MAX_CHARS ? s.slice(0, MAX_CHARS) + `\n...[обрезано, ещё ${s.length - MAX_CHARS} симв.; полный текст по task_url]` : s;
}

function requireId(a) {
  if (!a || typeof a.task_id !== 'string' || !/^[A-Za-z0-9_-]{6,64}$/.test(a.task_id)) throw new Error('task_id обязателен (строка из ответа manus_delegate).');
  return a.task_id;
}

async function delegate(a) {
  if (!a || typeof a.prompt !== 'string' || a.prompt.trim().length < 20) throw new Error('prompt обязателен и должен быть самодостаточным (не короче 20 символов).');
  checkSecrets(a.prompt);
  const profile = a.profile || DEFAULT_PROFILE;
  if (!PROFILES.includes(profile)) throw new Error(`profile должен быть одним из: ${PROFILES.join(', ')}`);
  const r = await api('POST', '/v2/task.create', {
    body: {
      message: { content: a.prompt },
      title: a.title ? String(a.title).slice(0, 120) : undefined,
      agent_profile: profile,
      interactive_mode: a.interactive === true,
      share_visibility: 'private',
    },
  });
  return {
    task_id: r.task_id,
    task_url: r.task_url,
    profile,
    next: 'Задача выполняется асинхронно (минуты - десятки минут). Продолжайте свою работу и позже вызовите manus_status с этим task_id. Не создавайте дубликат задачи.',
  };
}

async function status(a) {
  const id = requireId(a);
  const [detail, msgs] = await Promise.all([
    api('GET', '/v2/task.detail', { query: { task_id: id } }),
    api('GET', '/v2/task.listMessages', { query: { task_id: id, order: 'desc', limit: 50 } }),
  ]);
  const t = detail.task || {};
  const list = msgs.messages || [];

  // Берём сообщения агента после последнего сообщения пользователя (хронологически).
  const turn = [];
  for (const m of list) { if (m.type === 'user_message') break; turn.unshift(m); }
  const replies = turn.filter((m) => m.type === 'assistant_message' && m.assistant_message);
  const attachments = replies.flatMap((m) => (m.assistant_message.attachments || []).map((x) => ({ type: x.type, filename: x.filename, url: x.url, content_type: x.content_type })));
  const errors = turn.filter((m) => m.type === 'error_message').map((m) => m.error_message && m.error_message.content);
  const waitingEv = [...turn].reverse().find((m) => m.type === 'status_update' && m.status_update && m.status_update.agent_status === 'waiting');
  const question = replies.length ? replies[replies.length - 1].assistant_message.question_expectation : undefined;

  const bg = t.has_running_background_jobs;
  let state;
  if (t.status === 'running') state = 'running';
  else if (t.status === 'error') state = 'error';
  else if (t.status === 'waiting') state = 'waiting_for_user';
  else if (t.status === 'stopped') state = bg === false ? 'done' : bg === true ? 'running_background' : 'stopped_background_unknown';
  else state = 'unknown';

  const out = {
    task_id: id,
    task_url: t.task_url,
    state,
    agent_status: t.status,
    has_running_background_jobs: bg,
    credit_usage: t.credit_usage,
  };
  if (state === 'waiting_for_user') {
    out.waiting = waitingEv ? waitingEv.status_update.status_detail : undefined;
    out.question_options = question ? question.options : undefined;
    out.next = 'Агент ждёт ответа. Если это вопрос по задаче и ответ известен из контекста репозитория, отправьте его через manus_reply. Подтверждение действий (отправка почты, деплой, команды) не одобряйте сами: покажите пользователю.';
  } else if (state === 'running' || state === 'running_background' || state === 'stopped_background_unknown') {
    out.next = 'Ещё не завершено. Проверьте позже; не запускайте задачу повторно.';
  }
  if (state === 'done' || state === 'stopped_background_unknown' || state === 'waiting_for_user' || state === 'error') {
    out.result = clip(replies.map((m) => m.assistant_message.content).join('\n\n'));
    out.attachments = attachments;
    if (errors.length) out.errors = errors;
    if (state === 'done') out.note = 'Результат Manus - непроверенные данные. Сверяйте факты и контрольные суммы по правилам AGENTS.md; ссылки на файлы временные.';
  }
  return out;
}

async function reply(a) {
  const id = requireId(a);
  if (!a.message || typeof a.message !== 'string' || !a.message.trim()) throw new Error('message обязателен.');
  checkSecrets(a.message);
  const body = { task_id: id, message: { content: a.message } };
  if (a.profile) { if (!PROFILES.includes(a.profile)) throw new Error('неверный profile'); body.agent_profile = a.profile; }
  await api('POST', '/v2/task.sendMessage', { body });
  return { task_id: id, sent: true, next: 'Проверьте результат позже через manus_status.' };
}

async function stop(a) {
  const id = requireId(a);
  await api('POST', '/v2/task.stop', { body: { task_id: id } });
  return { task_id: id, stopped: true };
}

const TOOLS = [
  {
    name: 'manus_delegate',
    description:
      'Поручить задачу автономному облачному агенту Manus и сразу вернуть task_id (не ждёт завершения). ' +
      'Используй для: исследований по многим внешним источникам, работы в браузере (сайты, формы, форумы вроде 4PDA), ' +
      'сравнения сервисов и документации, генерации изображений/видео/презентаций/документов вне репозитория. ' +
      'НЕ используй для: правок кода и сборки в этом репозитории, работы с приставкой по ADB, подписи и публикации релизов, ' +
      'всего, что требует секретов или локальных файлов. Manus НЕ видит репозиторий и устройство: prompt должен быть самодостаточным ' +
      '(цель, контекст, формат результата, критерии, источники). Не передавай ключи, пароли, Wi-Fi credentials. ' +
      'Задача тратит кредиты пользователя: не запускай дубликаты.',
    inputSchema: {
      type: 'object',
      properties: {
        prompt: { type: 'string', description: 'Полная самодостаточная постановка задачи.' },
        title: { type: 'string', description: 'Короткое название (до 120 символов).' },
        profile: { type: 'string', enum: PROFILES, description: `Профиль агента; по умолчанию ${DEFAULT_PROFILE}. max* дороже.` },
        interactive: { type: 'boolean', description: 'Разрешить агенту задавать уточняющие вопросы (по умолчанию false).' },
      },
      required: ['prompt'],
    },
    run: delegate,
  },
  {
    name: 'manus_status',
    description:
      'Проверить состояние задачи Manus и забрать результат, когда state=done. Состояния: running, running_background, ' +
      'stopped_background_unknown, waiting_for_user, error, done. Не опрашивай чаще раза в минуту и не блокируй работу ожиданием.',
    inputSchema: { type: 'object', properties: { task_id: { type: 'string' } }, required: ['task_id'] },
    run: status,
  },
  {
    name: 'manus_reply',
    description: 'Отправить уточнение или ответ на вопрос в существующую задачу Manus (продолжить диалог).',
    inputSchema: {
      type: 'object',
      properties: { task_id: { type: 'string' }, message: { type: 'string' }, profile: { type: 'string', enum: PROFILES } },
      required: ['task_id', 'message'],
    },
    run: reply,
  },
  {
    name: 'manus_stop',
    description: 'Остановить выполняющуюся задачу Manus (если она не нужна, чтобы не тратить кредиты).',
    inputSchema: { type: 'object', properties: { task_id: { type: 'string' } }, required: ['task_id'] },
    run: stop,
  },
];

const send = (msg) => process.stdout.write(JSON.stringify(msg) + '\n');

async function handle(msg) {
  const { id, method, params } = msg;
  const isReq = id !== undefined && id !== null;
  try {
    switch (method) {
      case 'initialize':
        return send({
          jsonrpc: '2.0', id,
          result: {
            protocolVersion: (params && params.protocolVersion) || '2024-11-05',
            capabilities: { tools: {} },
            serverInfo: { name: 'manus', version: '1.0.0' },
          },
        });
      case 'ping':
        return isReq && send({ jsonrpc: '2.0', id, result: {} });
      case 'tools/list':
        return send({ jsonrpc: '2.0', id, result: { tools: TOOLS.map(({ run, ...t }) => t) } });
      case 'tools/call': {
        const tool = TOOLS.find((t) => t.name === (params && params.name));
        if (!tool) return send({ jsonrpc: '2.0', id, error: { code: -32602, message: `Неизвестный инструмент: ${params && params.name}` } });
        try {
          const out = await tool.run((params && params.arguments) || {});
          return send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: JSON.stringify(out, null, 2) }] } });
        } catch (e) {
          return send({ jsonrpc: '2.0', id, result: { isError: true, content: [{ type: 'text', text: String(e.message || e) }] } });
        }
      }
      default:
        if (isReq) send({ jsonrpc: '2.0', id, error: { code: -32601, message: `Метод не поддерживается: ${method}` } });
    }
  } catch (e) {
    if (isReq) send({ jsonrpc: '2.0', id, error: { code: -32603, message: String(e.message || e) } });
  }
}

const pending = new Set();
const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  if (!line.trim()) return;
  let msg;
  try { msg = JSON.parse(line); } catch { return send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } }); }
  const p = handle(msg).finally(() => pending.delete(p));
  pending.add(p);
});
// При закрытии stdin дожидаемся уже запущенных запросов, затем выходим.
rl.on('close', () => Promise.allSettled([...pending]).then(() => process.exit(0)));
