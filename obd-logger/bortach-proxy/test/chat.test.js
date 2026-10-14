import test from "node:test";
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import { Readable } from "node:stream";
import handler, { pickGemini, provider, authorized, upstreamBody } from "../api/chat.js";

test("Flash-Lite first, the newest stable one", () => {
  const ids = ["models/gemini-2.5-flash", "models/gemini-2.5-flash-lite", "models/gemini-3.0-flash-lite", "models/gemini-3.0-flash-lite-preview", "models/gemini-3.0-flash", "models/gemini-2.5-pro", "models/text-embedding-004", "models/gemini-3.0-flash-image"];
  assert.equal(pickGemini(ids), "gemini-3.0-flash-lite");
  assert.equal(pickGemini([]), "gemini-2.5-flash-lite");
});

test("provider and token", () => {
  assert.equal(provider({ GEMINI_API_KEY: "g" }), "gemini");
  assert.equal(provider({ DEEPSEEK_API_KEY: "d" }), "deepseek");
  assert.equal(provider({ PROVIDER: "openrouter", GEMINI_API_KEY: "g" }), "openrouter");
  assert.equal(provider({}), null);
  assert.ok(authorized("Bearer secret", "secret"));
  assert.ok(!authorized("Bearer secreT", "secret"));
  assert.ok(!authorized("", "secret"));
  assert.ok(!authorized("Bearer x", ""));
});

test("reasoning fields per provider", () => {
  const b = { messages: [{ role: "user", content: "hi" }], thinking: true };
  assert.equal(upstreamBody("gemini", "m", b, true).reasoning_effort, "high");
  assert.equal(upstreamBody("gemini", "m", { ...b, thinking: false }, true).reasoning_effort, "low");
  assert.equal(upstreamBody("gemini", "m", b, false).reasoning_effort, undefined);
  assert.deepEqual(upstreamBody("deepseek", "m", b, true).thinking, { type: "enabled" });
});

function call(method, headers, body) {
  const req = Readable.from(body ? [body] : []);
  req.method = method;
  req.headers = headers;
  return new Promise((resolve) => {
    const res = { statusCode: 0, headers: {}, setHeader(k, v) { this.headers[k] = v; }, end(s) { resolve({ status: this.statusCode, json: JSON.parse(s) }); } };
    handler(req, res);
  });
}

test("gzip request goes to Gemini and back", async () => {
  process.env.PROXY_TOKEN = "t0ken";
  process.env.GEMINI_API_KEY = "gk";
  process.env.GEMINI_MODEL = "gemini-x-flash";
  const seen = [];
  globalThis.fetch = async (url, opts) => {
    seen.push({ url, body: JSON.parse(opts.body), auth: opts.headers.Authorization });
    return new Response(JSON.stringify({ choices: [{ message: { content: "работает" } }] }), { status: 200 });
  };
  const payload = gzipSync(Buffer.from(JSON.stringify({ messages: [{ role: "user", content: "Скажи «работает»" }], thinking: true })));
  const r = await call("POST", { authorization: "Bearer t0ken", "x-bortach-gzip": "1" }, payload);
  assert.equal(r.status, 200);
  assert.equal(r.json.choices[0].message.content, "работает");
  assert.match(seen[0].url, /generativelanguage\.googleapis\.com\/v1beta\/openai\/chat\/completions/);
  assert.equal(seen[0].auth, "Bearer gk");
  assert.equal(seen[0].body.model, "gemini-x-flash");

  const bad = await call("POST", { authorization: "Bearer nope" }, Buffer.from("{}"));
  assert.equal(bad.status, 401);
  const health = await call("GET", { authorization: "Bearer t0ken" });
  assert.equal(health.json.provider, "gemini");
});

test("retries without reasoning fields on 400", async () => {
  let n = 0;
  globalThis.fetch = async (url, opts) => {
    n++;
    const b = JSON.parse(opts.body);
    if (b.reasoning_effort) return new Response(JSON.stringify([{ error: { message: "unknown field" } }]), { status: 400 });
    return new Response(JSON.stringify({ choices: [{ message: { content: "ок" } }] }), { status: 200 });
  };
  const r = await call("POST", { authorization: "Bearer t0ken" }, Buffer.from(JSON.stringify({ messages: [{ role: "user", content: "x" }] })));
  assert.equal(r.status, 200);
  assert.equal(n, 2);
});

test("overloaded model: pause, then the next one answers", async () => {
  process.env.GEMINI_MODEL = "";
  const { ask, rankGemini, resetModels } = await import("../api/chat.js");
  resetModels();
  assert.deepEqual(rankGemini(["models/gemini-3.0-flash", "models/gemini-3.0-flash-lite", "models/gemini-2.5-flash"]).slice(0, 3),
    ["gemini-3.0-flash-lite", "gemini-3.0-flash", "gemini-2.5-flash"]);
  const tried = [];
  globalThis.fetch = async (url, opts) => {
    if (url.endsWith("/models")) return new Response(JSON.stringify({ data: [{ id: "models/gemini-3.0-flash-lite" }, { id: "models/gemini-3.0-flash" }] }));
    const m = JSON.parse(opts.body).model;
    tried.push(m);
    if (m === "gemini-3.0-flash-lite") return new Response(JSON.stringify([{ error: { code: 503, message: "The model is overloaded." } }]), { status: 503 });
    return new Response(JSON.stringify({ choices: [{ message: { content: "ок" } }] }), { status: 200 });
  };
  const { r, model } = await ask("gemini", { messages: [{ role: "user", content: "x" }] }, { pause: 1 });
  assert.equal(r.status, 200);
  assert.equal(model, "gemini-3.0-flash");
  assert.deepEqual(tried, ["gemini-3.0-flash-lite", "gemini-3.0-flash"]);
});

test("Pro first for tier pro, then Flash, then Lite; 429 from Pro goes on at once", async () => {
  const { ask, rankFor, resetModels } = await import("../api/chat.js");
  const ids = ["models/gemini-2.5-pro", "models/gemini-3.0-pro-preview", "models/gemini-3.0-flash", "models/gemini-3.0-flash-lite", "models/gemini-2.5-flash"];
  assert.deepEqual(rankFor(ids, "pro").slice(0, 3), ["gemini-3.0-pro-preview", "gemini-2.5-pro", "gemini-3.0-flash"]);
  assert.equal(rankFor(ids, "flash")[0], "gemini-3.0-flash-lite");
  process.env.GEMINI_MODEL = "";
  resetModels();
  const tried = [];
  const t0 = Date.now();
  globalThis.fetch = async (url, opts) => {
    if (url.endsWith("/models")) return new Response(JSON.stringify({ data: ids.map((id) => ({ id })) }));
    const m = JSON.parse(opts.body).model;
    tried.push(m);
    if (/pro/.test(m)) return new Response(JSON.stringify([{ error: { code: 429, message: "quota" } }]), { status: 429 });
    return new Response(JSON.stringify({ choices: [{ message: { content: "ок" } }] }), { status: 200 });
  };
  const { r, model } = await ask("gemini", { messages: [{ role: "user", content: "x" }], tier: "pro" }, { pause: 5000 });
  assert.equal(r.status, 200);
  assert.equal(model, "gemini-3.0-flash");
  assert.deepEqual(tried, ["gemini-3.0-pro-preview", "gemini-2.5-pro", "gemini-3.0-flash"]);
  assert.ok(Date.now() - t0 < 2000);
});

test("JSON schema for the card and the opinion; dropped with the extras", async () => {
  const { upstreamBody } = await import("../api/chat.js");
  const b = { messages: [{ role: "user", content: "x" }], format: "card" };
  assert.equal(upstreamBody("gemini", "m", b, true).response_format.json_schema.name, "card");
  assert.equal(upstreamBody("gemini", "m", { ...b, format: "opinion" }, true).response_format.json_schema.name, "opinion");
  assert.equal(upstreamBody("gemini", "m", b, false).response_format, undefined);
  assert.equal(upstreamBody("gemini", "m", { messages: [] }, true).response_format, undefined);
});
