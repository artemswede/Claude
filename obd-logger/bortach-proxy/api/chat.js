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
// POST /api/chat — тело как у OpenAI chat/completions ({messages, thinking}); можно сжать gzip
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

/** The newest general Gemini model (Flash): not lite, not image/audio/embedding. */
export function pickGemini(ids) {
  const ok = ids
    .map((id) => id.replace(/^models\//, ""))
    .filter((id) => /^gemini-\d/.test(id) && /flash/.test(id))
    .filter((id) => !/lite|image|tts|audio|live|embedding|exp|thinking|8b/.test(id));
  const version = (id) => parseFloat(id.match(/^gemini-(\d+(?:\.\d+)?)/)[1]);
  ok.sort((a, b) => version(b) - version(a) || Number(/preview/.test(a)) - Number(/preview/.test(b)) || a.length - b.length);
  return ok[0] || "gemini-2.5-flash";
}

let cachedModel = null;

async function model(p, env = process.env) {
  if (p === "gemini") {
    if (env.GEMINI_MODEL) return env.GEMINI_MODEL;
    if (cachedModel) return cachedModel;
    try {
      const r = await fetch(`${GEMINI}/models`, { headers: { Authorization: `Bearer ${env.GEMINI_API_KEY}` } });
      const j = await r.json();
      cachedModel = pickGemini((j.data || []).map((m) => m.id || ""));
    } catch {
      cachedModel = "gemini-2.5-flash";
    }
    return cachedModel;
  }
  if (p === "deepseek") return env.DEEPSEEK_MODEL || "deepseek-chat";
  return env.OPENROUTER_MODEL || "deepseek/deepseek-v4-flash";
}

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

/** The request to the provider: the same messages, deep reasoning when asked for. */
export function upstreamBody(p, m, body, extras) {
  const out = { model: m, messages: body.messages, stream: false };
  if (!extras) return out;
  const think = body.thinking !== false;
  if (p === "gemini") out.reasoning_effort = think ? "high" : "low";
  else if (p === "deepseek") {
    out.thinking = { type: think ? "enabled" : "disabled" };
    if (think) out.reasoning_effort = "high";
  } else out.reasoning = think ? { effort: "high" } : { enabled: false };
  return out;
}

async function ask(p, body) {
  const { base, key } = target(p);
  const m = await model(p);
  const call = (extras) =>
    fetch(`${base}/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
      body: JSON.stringify(upstreamBody(p, m, body, extras)),
    });
  let r = await call(true);
  // A model that does not know the reasoning fields: the same question without them.
  if (r.status === 400) r = await call(false);
  // The remembered Gemini model was retired: pick again next time.
  if (r.status === 404 && p === "gemini") cachedModel = null;
  return { r, model: m };
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
    return send(res, 200, { ok: true, provider: p, model: await model(p), region: process.env.VERCEL_REGION || null });
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
