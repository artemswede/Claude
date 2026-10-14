// Посредник Бортача: магнитола → этот сервер (Vercel, вне РФ) → ИИ.
// Ключ ИИ хранится здесь, в переменных окружения, а не на магнитоле.
//
// Переменные окружения (Vercel → Settings → Environment Variables):
//   PROXY_TOKEN       — пароль, который вписывается в Бортач (обязательно)
//   GEMINI_API_KEY    — ключ Google AI Studio (провайдер по умолчанию)
//   GEMINI_MODEL      — необязательно: модель Gemini (иначе выбирается сама)
//   DEEPSEEK_API_KEY  — необязательно: ключ platform.deepseek.com
//   OPENROUTER_API_KEY, OPENROUTER_MODEL — необязательно: OpenRouter
//   PROVIDER          — gemini | deepseek | openrouter (по умолчанию — первый, у которого есть ключ)
//
// POST /api/chat — тело как у OpenAI chat/completions ({messages, thinking, tier, format}); можно сжать gzip
//   tier: "pro" — сначала Gemini Pro (Бортач сам считает дневную квоту), иначе Flash-Lite;
//   format: "card" | "opinion" — ответ JSON по схеме (раскладка ответа в приложении);
//   (заголовок X-Bortach-Gzip: 1) — так большой контекст проходит в лимит Vercel 4,5 МБ.
// GET  /api/chat — проверка: какой провайдер и модель отвечают.

import { gunzipSync } from "node:zlib";
import { timingSafeEqual } from "node:crypto";

const GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai";
const DEEPSEEK = "https://api.deepseek.com";
const OPENROUTER = "https://openrouter.ai/api/v1";

export function provider(env = process.env) {
  const p = (env.PROVIDER || "").toLowerCase();
  if (p === "gemini" || p === "deepseek" || p === "openrouter") return p;
  if (env.GEMINI_API_KEY) return "gemini";
  if (env.DEEPSEEK_API_KEY) return "deepseek";
  if (env.OPENROUTER_API_KEY) return "openrouter";
  return null;
}

function target(p, env = process.env) {
  if (p === "gemini") return { base: GEMINI, key: env.GEMINI_API_KEY };
  if (p === "deepseek") return { base: DEEPSEEK, key: env.DEEPSEEK_API_KEY };
  return { base: OPENROUTER, key: env.OPENROUTER_API_KEY };
}

const version = (id) => parseFloat(id.match(/^gemini-(\d+(?:\.\d+)?)/)[1]);

/**
 * Gemini models to try in order: the newest stable Flash-Lite first (the largest free
 * limits, rarely overloaded), then Flash — when one is overloaded (503) the next answers.
 */
export function rankGemini(ids) {
  const all = ids
    .map((id) => id.replace(/^models\//, ""))
    .filter((id) => /^gemini-\d/.test(id) && /flash/.test(id))
    .filter((id) => !/image|tts|audio|live|embedding|exp|thinking|8b/.test(id));
  const order = (a, b) =>
    Number(/lite/.test(b)) - Number(/lite/.test(a)) ||
    version(b) - version(a) ||
    Number(/preview/.test(a)) - Number(/preview/.test(b)) ||
    a.length - b.length;
  const out = [...new Set(all)].sort(order);
  for (const d of ["gemini-2.5-flash-lite", "gemini-2.5-flash"]) if (!out.includes(d)) out.push(d);
  return out.slice(0, 4);
}

/** Gemini Pro models, the newest stable first (for the first questions of the day). */
export function rankPro(ids) {
  const all = ids
    .map((id) => id.replace(/^models\//, ""))
    .filter((id) => /^gemini-\d/.test(id) && /-pro/.test(id))
    .filter((id) => !/image|tts|audio|live|embedding|exp|thinking|vision|computer/.test(id));
  const order = (a, b) => version(b) - version(a) || Number(/preview/.test(a)) - Number(/preview/.test(b)) || a.length - b.length;
  const out = [...new Set(all)].sort(order);
  if (!out.includes("gemini-2.5-pro")) out.push("gemini-2.5-pro");
  // A preview without a free quota answers 429 at once: then the stable one.
  return out.slice(0, 2);
}

/** Order for a tier: Pro → Flash → Flash-Lite, or the usual Flash-Lite first. */
export function rankFor(ids, tier) {
  const flash = rankGemini(ids);
  if (tier !== "pro") return flash;
  const full = flash.filter((m) => !/lite/.test(m));
  const lite = flash.filter((m) => /lite/.test(m));
  return [...rankPro(ids), ...full, ...lite].slice(0, 6);
}

/** The first model to ask (see [rankGemini]). */
export function pickGemini(ids) {
  return rankGemini(ids)[0];
}

let cachedModels = null;
/** For tests: forget the remembered model list. */
export function resetModels() {
  cachedModels = null;
}

/** Models to try in order (the first one is «the» model). */
async function models(p, env = process.env, tier = "") {
  if (p === "gemini") {
    if (!cachedModels) {
      try {
        const r = await fetch(`${GEMINI}/models`, { headers: { Authorization: `Bearer ${env.GEMINI_API_KEY}` } });
        const j = await r.json();
        cachedModels = (j.data || []).map((m) => m.id || "");
      } catch {
        cachedModels = [];
      }
    }
    const list = rankFor(cachedModels, tier);
    // GEMINI_MODEL pins the model of the usual tier; Pro questions still start with Pro.
    if (!env.GEMINI_MODEL || tier === "pro") return list;
    return [env.GEMINI_MODEL, ...list.filter((m) => m !== env.GEMINI_MODEL)];
  }
  if (p === "deepseek") return [env.DEEPSEEK_MODEL || "deepseek-chat"];
  return [env.OPENROUTER_MODEL || "deepseek/deepseek-v4-flash"];
}

async function model(p, env = process.env, tier = "") {
  return (await models(p, env, tier))[0];
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/** Overloaded or rate-limited for a moment: worth another try (another model, after a pause). */
export const transient = (status) => status === 429 || status === 500 || status === 502 || status === 503 || status === 504;

export function authorized(header, token) {
  if (!token) return false;
  const got = Buffer.from(String(header || "").replace(/^Bearer\s+/i, ""));
  const want = Buffer.from(token);
  return got.length === want.length && timingSafeEqual(got, want);
}

async function readBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(typeof c === "string" ? Buffer.from(c) : c);
  let buf = Buffer.concat(chunks);
  if (req.headers["x-bortach-gzip"] === "1") buf = gunzipSync(buf);
  return JSON.parse(buf.toString("utf8"));
}

/** JSON answers the app lays out itself: the chat card and «Мнение ИИ» on the overview. */
export const SCHEMAS = {
  card: {
    type: "object",
    properties: {
      short: { type: "string", description: "Коротко: главный вывод, до 90 знаков" },
      why: { type: "array", items: { type: "string" }, description: "Почему: факты с числом, режимом и временем → что значит; в порядке рядов графика" },
      todo: { type: "string", description: "Что сделать: одно-два действия" },
      confidence: { type: "string", description: "высокая/средняя/низкая — и почему" },
      rest: { type: "string", description: "Если вопрос не про диагностику — обычный ответ здесь" },
      tags: { type: "array", items: { type: "string" }, description: "Теги Бортача целиком: [график: …], [проверка: …], [наблюдать: …], [запись ТО: …], [пробег: …]" },
    },
    required: ["short", "why", "todo", "confidence", "tags"],
  },
  opinion: {
    type: "object",
    properties: {
      headline: { type: "string" },
      urgency: { type: "string" },
      confidence: { type: "string", enum: ["высокая", "средняя", "низкая"] },
      why: {
        type: "array",
        items: { type: "object", properties: { label: { type: "string" }, value: { type: "string" }, deviating: { type: "boolean" } }, required: ["label", "value"] },
      },
      next: { type: "string" },
      chart: { type: "string" },
    },
    required: ["headline", "confidence", "why", "next"],
  },
};

/** The request to the provider: the same messages, deep reasoning when asked for. */
export function upstreamBody(p, m, body, extras) {
  const out = { model: m, messages: body.messages, stream: false };
  if (!extras) return out;
  const think = body.thinking !== false;
  const schema = SCHEMAS[body.format];
  if (schema && p !== "openrouter") out.response_format = { type: "json_schema", json_schema: { name: body.format, schema } };
  if (p === "gemini") out.reasoning_effort = think ? "high" : "low";
  else if (p === "deepseek") {
    out.thinking = { type: think ? "enabled" : "disabled" };
    if (think) out.reasoning_effort = "high";
  } else out.reasoning = think ? { effort: "high" } : { enabled: false };
  return out;
}

export async function ask(p, body, { pause = 2000 } = {}) {
  const { base, key } = target(p);
  const list = await models(p, process.env, body.tier);
  const call = (m, extras) =>
    fetch(`${base}/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
      body: JSON.stringify(upstreamBody(p, m, body, extras)),
    });
  let last = null;
  // Up to 4 tries (5 with Pro): an overloaded model (503/429) gets a pause, then the next model on the list.
  const tries = Math.max(4, list.length);
  for (let i = 0; i < tries; i++) {
    const m = list[Math.min(i, list.length - 1)];
    let r = await call(m, true);
    // A model that does not know the reasoning fields: the same question without them.
    if (r.status === 400) r = await call(m, false);
    // A retired model: drop the cached list so the next question picks again.
    if (r.status === 404 && p === "gemini") {
      cachedModels = null;
      last = { r, model: m };
      if (i + 1 < list.length) continue;
      return last;
    }
    if (!transient(r.status)) return { r, model: m };
    last = { r, model: m };
    // Pro out of its daily quota (429): straight to Flash, no waiting.
    if (/-pro/.test(m) && r.status === 429) continue;
    if (i < tries - 1) await sleep(pause * (i + 1));
  }
  return last;
}

function send(res, status, obj) {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.end(JSON.stringify(obj));
}

export default async function handler(req, res) {
  const token = process.env.PROXY_TOKEN;
  if (!token) return send(res, 500, { error: { message: "На сервере не задан PROXY_TOKEN (Vercel → Settings → Environment Variables)." } });
  if (!authorized(req.headers.authorization, token)) return send(res, 401, { error: { message: "Токен сервера не подходит." } });
  const p = provider();
  if (!p) return send(res, 500, { error: { message: "На сервере нет ключа ИИ: задайте GEMINI_API_KEY (или DEEPSEEK_API_KEY / OPENROUTER_API_KEY)." } });

  if (req.method === "GET") {
    return send(res, 200, { ok: true, provider: p, model: await model(p), pro: p === "gemini" ? await model(p, process.env, "pro") : null, region: process.env.VERCEL_REGION || null });
  }
  if (req.method !== "POST") return send(res, 405, { error: { message: "Только GET и POST." } });

  let body;
  try {
    body = await readBody(req);
  } catch {
    return send(res, 400, { error: { message: "Не удалось прочитать запрос." } });
  }
  if (!Array.isArray(body.messages) || body.messages.length === 0) return send(res, 400, { error: { message: "Нет сообщений." } });

  try {
    const { r, model: m } = await ask(p, body);
    const text = await r.text();
    let j;
    try {
      j = JSON.parse(text);
    } catch {
      j = { error: { message: text.slice(0, 300) } };
    }
    // Gemini wraps errors in a list: [{error: {...}}].
    if (Array.isArray(j)) j = j[0] || {};
    if (!r.ok) {
      const msg = j.error?.message || `ответ ${r.status}`;
      return send(res, r.status === 401 || r.status === 403 ? 502 : r.status, { error: { message: `${p}: ${msg}` } });
    }
    const content = j.choices?.[0]?.message?.content;
    if (typeof content !== "string") return send(res, 502, { error: { message: `${p}: пустой ответ модели` } });
    return send(res, 200, { model: `${p}/${m}`, choices: [{ message: { role: "assistant", content } }], usage: j.usage || null });
  } catch (e) {
    return send(res, 502, { error: { message: `${p} недоступен: ${e.message}` } });
  }
}
