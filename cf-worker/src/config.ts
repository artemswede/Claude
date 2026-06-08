/**
 * Конфигурация воркера: переменные окружения Cloudflare + сборка провайдера и
 * базового запроса.
 */

import { TenderQuery } from "./filters";
import { TenderProvider } from "./providers/base";
import { MockProvider } from "./providers/mock";
import { AggregatorProvider } from "./providers/aggregator";

export interface Env {
  // Binding KV (задаётся в wrangler.toml)
  TENDER_KV: KVNamespace;

  // Секреты (wrangler secret put ...)
  BOT_TOKEN: string;
  WEBHOOK_SECRET?: string;
  AGGREGATOR_API_KEY?: string;

  // Переменные (wrangler.toml [vars])
  PROVIDER?: string;
  OKPD2_PREFIXES?: string;
  KEYWORDS?: string;
  MIN_PRICE?: string;
  MAX_PRICE?: string;
  ONLY_WITH_WINNER?: string;
  POLL_LOOKBACK_DAYS?: string;
  MAX_RESULTS_PER_QUERY?: string;

  AGGREGATOR_BASE_URL?: string;
  AGGREGATOR_SEARCH_PATH?: string;
  AGGREGATOR_AUTH_HEADER?: string;
  AGGREGATOR_AUTH_SCHEME?: string;

  // Режим имитации (временный предпросмотр формата)
  SIM_TZ_OFFSET?: string; // часовой пояс рабочего времени, по умолчанию +3 (МСК)
  SIM_HOUR_START?: string; // начало рабочего времени, по умолчанию 9
  SIM_HOUR_END?: string; // конец рабочего времени, по умолчанию 19
  SIM_PROBABILITY?: string; // вероятность отправки на каждом 30-мин тике
}

function csv(value: string | undefined, fallback: string[]): string[] {
  if (!value) return fallback;
  return value
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

function num(value: string | undefined): number | undefined {
  if (value == null || value.trim() === "") return undefined;
  const n = parseFloat(value);
  return isNaN(n) ? undefined : n;
}

function int(value: string | undefined, fallback: number): number {
  const n = parseInt(value ?? "", 10);
  return isNaN(n) ? fallback : n;
}

function bool(value: string | undefined, fallback: boolean): boolean {
  if (value == null) return fallback;
  return ["1", "true", "yes", "y", "on", "да"].includes(value.trim().toLowerCase());
}

export function buildBaseQuery(env: Env): TenderQuery {
  return {
    keywords: csv(env.KEYWORDS, ["труб", "тбд", "большого диаметра"]).map((k) =>
      k.toLowerCase(),
    ),
    okpd2Prefixes: csv(env.OKPD2_PREFIXES, ["24.20"]),
    minPrice: num(env.MIN_PRICE),
    maxPrice: num(env.MAX_PRICE),
    onlyWithWinner: bool(env.ONLY_WITH_WINNER, true),
    lookbackDays: int(env.POLL_LOOKBACK_DAYS, 7),
    limit: int(env.MAX_RESULTS_PER_QUERY, 25),
  };
}

export function buildProvider(env: Env): TenderProvider {
  if ((env.PROVIDER ?? "mock").toLowerCase() === "aggregator") {
    return new AggregatorProvider({
      baseUrl: env.AGGREGATOR_BASE_URL ?? "",
      apiKey: env.AGGREGATOR_API_KEY ?? "",
      searchPath: env.AGGREGATOR_SEARCH_PATH ?? "/search",
      authHeader: env.AGGREGATOR_AUTH_HEADER ?? "Authorization",
      authScheme: env.AGGREGATOR_AUTH_SCHEME ?? "Bearer",
    });
  }
  return new MockProvider();
}
