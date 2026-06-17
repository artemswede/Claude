/**
 * Project 1950 — Cloudflare Worker (Шаг 7 / MVP).
 * Статика (SPA) отдаётся биндингом assets; Worker обслуживает /api/*.
 */
import { evaluate, verdictLabel } from "./engine";
import type { Answers } from "./types";
import { type Env, readMetrics, recordCompletion, recordCsat, recordStart, saveLead } from "./storage/kv";

const json = (data: unknown, status = 200): Response =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8" } });

const METRICS_TOKEN = "1950-metrics"; // простая защита GET /api/metrics (A8)

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const { pathname } = url;

    if (request.method === "POST" && pathname === "/api/evaluate") {
      const answers = (await request.json().catch(() => ({}))) as Answers;
      const result = evaluate(answers);
      return json({ ...result, verdictLabel: verdictLabel(result.verdict) });
    }

    if (request.method === "POST" && pathname === "/api/event") {
      const body = (await request.json().catch(() => ({}))) as Record<string, unknown>;
      if (body.type === "start") await recordStart(env);
      else if (body.type === "csat") await recordCsat(env, typeof body.csat === "number" ? body.csat : 0);
      else if (body.type === "complete")
        await recordCompletion(env, {
          verdict: typeof body.verdict === "string" ? body.verdict : undefined,
          archetype: typeof body.archetype === "string" ? body.archetype : undefined,
          industry: typeof body.industry === "string" ? body.industry : undefined,
          csat: typeof body.csat === "number" ? body.csat : undefined,
          timeMs: typeof body.timeMs === "number" ? body.timeMs : undefined,
        });
      return json({ ok: true });
    }

    if (request.method === "POST" && pathname === "/api/lead") {
      const body = (await request.json().catch(() => ({}))) as Record<string, unknown>;
      // honeypot: скрытое поле должно быть пустым (анти-спам, A8)
      if (typeof body.website === "string" && body.website.length > 0) return json({ ok: true });
      const saved = await saveLead(env, {
        name: typeof body.name === "string" ? body.name : undefined,
        email: typeof body.email === "string" ? body.email : undefined,
        consent: body.consent === true,
        verdict: typeof body.verdict === "string" ? body.verdict : undefined,
        archetype: typeof body.archetype === "string" ? body.archetype : undefined,
        industry: typeof body.industry === "string" ? body.industry : undefined,
      });
      return json({ ok: true, saved });
    }

    if (request.method === "GET" && pathname === "/api/metrics") {
      if (url.searchParams.get("token") !== METRICS_TOKEN) return json({ error: "forbidden" }, 403);
      return json(await readMetrics(env));
    }

    // прочее (включая статику) обслуживает биндинг assets; сюда обычно не доходит
    return new Response("Not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;
