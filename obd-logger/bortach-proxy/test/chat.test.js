import test from "node:test";
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import { Readable } from "node:stream";
import handler, { pickGemini, provider, authorized, upstreamBody } from "../api/chat.js";

test("picks the newest stable Flash", () => {
  const ids = ["models/gemini-2.5-flash", "models/gemini-2.5-flash-lite", "models/gemini-3.0-flash-preview", "models/gemini-3.0-flash", "models/gemini-2.5-pro", "models/text-embedding-004", "models/gemini-3.0-flash-image"];
  assert.equal(pickGemini(ids), "gemini-3.0-flash");
  assert.equal(pickGemini([]), "gemini-2.5-flash");
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
