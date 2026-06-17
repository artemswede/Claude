// Обёртка над KV: анонимные счётчики (батч на завершении, фикс A11) + лиды с согласием (A8).
export interface Env {
  METRICS?: KVNamespace;
  LEADS?: KVNamespace;
}

async function inc(kv: KVNamespace, key: string, by = 1): Promise<void> {
  const cur = parseInt((await kv.get(key)) ?? "0", 10) || 0;
  await kv.put(key, String(cur + by));
}

// Одна пачка записей на завершении прохождения (а не на каждый шаг).
export async function recordCompletion(
  env: Env,
  data: { verdict?: string; archetype?: string; industry?: string; csat?: number; timeMs?: number; steps?: number }
): Promise<void> {
  const kv = env.METRICS;
  if (!kv) return;
  await inc(kv, "count:complete");
  if (data.verdict) await inc(kv, `count:verdict:${data.verdict}`);
  if (data.archetype) await inc(kv, `count:archetype:${data.archetype}`);
  if (data.industry) await inc(kv, `count:industry:${data.industry}`);
  if (typeof data.csat === "number") await inc(kv, `count:csat:${data.csat}`);
  if (typeof data.timeMs === "number") {
    await inc(kv, "sum:time_ms", Math.round(data.timeMs));
    await inc(kv, "count:time_n");
  }
}

export async function recordStart(env: Env): Promise<void> {
  if (env.METRICS) await inc(env.METRICS, "count:start");
}

export async function recordCsat(env: Env, csat: number): Promise<void> {
  if (env.METRICS && csat >= 1 && csat <= 5) await inc(env.METRICS, `count:csat:${csat}`);
}

export async function saveLead(
  env: Env,
  lead: { name?: string; email?: string; consent?: boolean; verdict?: string; archetype?: string; industry?: string }
): Promise<boolean> {
  if (!env.LEADS || !lead.consent || !lead.email) return false;
  const id = crypto.randomUUID();
  const rec = { ts: new Date().toISOString(), ...lead };
  // срок хранения 90 дней (A8)
  await env.LEADS.put(`lead:${id}`, JSON.stringify(rec), { expirationTtl: 60 * 60 * 24 * 90 });
  return true;
}

export async function readMetrics(env: Env): Promise<Record<string, number>> {
  const kv = env.METRICS;
  if (!kv) return {};
  const out: Record<string, number> = {};
  const list = await kv.list({ limit: 1000 });
  for (const k of list.keys) {
    out[k.name] = parseInt((await kv.get(k.name)) ?? "0", 10) || 0;
  }
  return out;
}
