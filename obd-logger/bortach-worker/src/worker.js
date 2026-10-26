// Бортач-прокси на Cloudflare Workers: магнитола → этот сервер → OpenRouter (DeepSeek).
// Ключ ИИ хранится в secrets, а не на магнитоле.
//
// Secrets (wrangler secret put):
//   PROXY_TOKEN        — пароль, который вписывается в Бортач
//   OPENROUTER_API_KEY — ключ OpenRouter (sk-or-…)
//
// POST /api/chat — тело как у OpenAI ({messages, thinking, tier, format})
// GET  /api/chat — проверка: провайдер, модель

const OPENROUTER = "https://openrouter.ai/api/v1/chat/completions";

const MODELS = {
  pro: "deepseek/deepseek-r1",
  flash: "deepseek/deepseek-v3-0324",
};

const SCHEMAS = {
  card: {
    type: "object",
    properties: {
      short: { type: "string", description: "Коротко: главный вывод, до 90 знаков" },
      why: { type: "array", items: { type: "string" }, description: "Почему: факты" },
      todo: { type: "string", description: "Что сделать" },
      confidence: { type: "string", description: "высокая/средняя/низкая — и почему" },
      rest: { type: "string", description: "Если не про диагностику — обычный ответ здесь" },
      tags: { type: "array", items: { type: "string" }, description: "Теги Бортача" },
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

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let result = 0;
  for (let i = 0; i < a.length; i++) result |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return result === 0;
}

function authorized(header, token) {
  if (!token) return false;
  const got = String(header || "").replace(/^Bearer\s+/i, "");
  return got.length === token.length && timingSafeEqual(got, token);
}

function json(status, obj) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

function upstreamBody(model, body) {
  const out = { model, messages: body.messages, stream: false };
  const think = body.thinking !== false;
  const schema = SCHEMAS[body.format];
  if (schema) out.response_format = { type: "json_schema", json_schema: { name: body.format, schema } };
  out.reasoning = think ? { effort: "high" } : { enabled: false };
  return out;
}

const transient = (s) => s === 429 || s === 500 || s === 502 || s === 503 || s === 504;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function ask(body, apiKey) {
  const tier = body.tier || "flash";
  const models = tier === "pro"
    ? [MODELS.pro, MODELS.flash]
    : [MODELS.flash, MODELS.pro];

  let last = null;
  for (let i = 0; i < models.length + 1; i++) {
    const m = models[Math.min(i, models.length - 1)];
    const r = await fetch(OPENROUTER, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`,
        "X-Title": "Bortach",
        "HTTP-Referer": "https://github.com/artemswede/Claude",
      },
      body: JSON.stringify(upstreamBody(m, body)),
    });
    if (!transient(r.status)) return { r, model: m };
    last = { r, model: m };
    if (i < models.length) await sleep(1500 * (i + 1));
  }
  return last;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!url.pathname.endsWith("/api/chat") && url.pathname !== "/") {
      return json(404, { error: { message: "Not found" } });
    }

    const token = env.PROXY_TOKEN;
    if (!token) return json(500, { error: { message: "PROXY_TOKEN не задан." } });
    if (!authorized(request.headers.get("Authorization"), token)) {
      return json(401, { error: { message: "Токен не подходит." } });
    }
    const apiKey = env.OPENROUTER_API_KEY;
    if (!apiKey) return json(500, { error: { message: "OPENROUTER_API_KEY не задан." } });

    if (request.method === "GET") {
      return json(200, {
        ok: true,
        provider: "openrouter",
        model: MODELS.flash,
        pro: MODELS.pro,
        region: request.cf?.colo || null,
      });
    }

    if (request.method !== "POST") return json(405, { error: { message: "GET или POST." } });

    let body;
    try {
      const buf = await request.arrayBuffer();
      const gzip = request.headers.get("X-Bortach-Gzip") === "1";
      const raw = gzip ? new Response(new Response(buf).body.pipeThrough(new DecompressionStream("gzip"))) : new Response(buf);
      body = await raw.json();
    } catch {
      return json(400, { error: { message: "Не удалось прочитать запрос." } });
    }
    if (!Array.isArray(body.messages) || body.messages.length === 0) {
      return json(400, { error: { message: "Нет сообщений." } });
    }

    try {
      const { r, model: m } = await ask(body, apiKey);
      const text = await r.text();
      let j;
      try { j = JSON.parse(text); } catch { j = { error: { message: text.slice(0, 300) } }; }
      if (!r.ok) {
        const msg = j.error?.message || `ответ ${r.status}`;
        return json(r.status === 401 || r.status === 403 ? 502 : r.status, { error: { message: `openrouter: ${msg}` } });
      }
      const content = j.choices?.[0]?.message?.content;
      if (typeof content !== "string") return json(502, { error: { message: "openrouter: пустой ответ" } });
      return json(200, {
        model: `openrouter/${m}`,
        choices: [{ message: { role: "assistant", content } }],
        usage: j.usage || null,
      });
    } catch (e) {
      return json(502, { error: { message: `openrouter недоступен: ${e.message}` } });
    }
  },
};
