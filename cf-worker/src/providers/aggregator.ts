/**
 * Универсальный адаптер под платный REST-агрегатор (Контур/Тендерплан/Seldon).
 * Подгоняется под конкретного вендора через FIELD_MAP / PARAM_MAP без правки логики.
 */

import { Money, Tender, statusFromRaw } from "../models";
import { TenderQuery, filterTenders } from "../filters";
import { TenderProvider } from "./base";

export interface AggregatorConfig {
  baseUrl: string;
  apiKey: string;
  searchPath: string;
  authHeader: string;
  authScheme: string;
}

// Пути в JSON-записи агрегатора. Поправьте под формат вашего источника.
const FIELD_MAP: Record<string, string> = {
  id: "id",
  registryNumber: "regNumber",
  title: "name",
  okpd2: "okpd2.code",
  law: "law",
  customer: "customer.name",
  customerInn: "customer.inn",
  winner: "result.winner.name",
  winnerInn: "result.winner.inn",
  startPrice: "startPrice",
  finalPrice: "result.contractPrice",
  currency: "currency",
  volume: "volume.value",
  volumeUnit: "volume.unit",
  platform: "platform.name",
  status: "status",
  publishedAt: "publishDate",
  resultDate: "result.date",
  url: "url",
};

const PARAM_MAP = {
  search: "q",
  okpd2: "okpd2",
  dateFrom: "dateFrom",
  limit: "limit",
  status: "status",
};

const RESULTS_PATH = "items"; // где лежит массив записей

function dig(obj: any, path: string): any {
  let cur = obj;
  for (const part of path.split(".")) {
    if (cur && typeof cur === "object") cur = cur[part];
    else return undefined;
    if (cur == null) return undefined;
  }
  return cur;
}

function parseNumber(value: any): number | undefined {
  if (value == null) return undefined;
  if (typeof value === "number") return value;
  const cleaned = String(value).replace(/[\s ]/g, "").replace(",", ".");
  const n = parseFloat(cleaned);
  return isNaN(n) ? undefined : n;
}

export class AggregatorProvider implements TenderProvider {
  readonly name = "aggregator";

  constructor(private cfg: AggregatorConfig) {}

  private buildUrl(query: TenderQuery): string {
    const keywordText = query.freeText ?? query.keywords.join(" ");
    const dateFrom = new Date(
      Date.now() - query.lookbackDays * 86_400_000,
    )
      .toISOString()
      .slice(0, 10);

    const base = this.cfg.baseUrl.replace(/\/$/, "");
    const path = this.cfg.searchPath.startsWith("/")
      ? this.cfg.searchPath
      : `/${this.cfg.searchPath}`;
    const url = new URL(base + path);
    url.searchParams.set(PARAM_MAP.search, keywordText);
    url.searchParams.set(PARAM_MAP.okpd2, query.okpd2Prefixes.join(","));
    url.searchParams.set(PARAM_MAP.dateFrom, dateFrom);
    url.searchParams.set(PARAM_MAP.limit, String(query.limit));
    if (query.onlyWithWinner) url.searchParams.set(PARAM_MAP.status, "completed");
    return url.toString();
  }

  private mapRecord(rec: any): Tender {
    const currency = dig(rec, FIELD_MAP.currency) ?? "RUB";
    const money = (key: string): Money | undefined => {
      const amount = parseNumber(dig(rec, FIELD_MAP[key]));
      return amount == null ? undefined : { amount, currency };
    };
    const id = dig(rec, FIELD_MAP.id);
    return {
      id: id != null ? String(id) : "",
      registryNumber: dig(rec, FIELD_MAP.registryNumber),
      title: dig(rec, FIELD_MAP.title) ?? "",
      okpd2: dig(rec, FIELD_MAP.okpd2),
      law: dig(rec, FIELD_MAP.law),
      customer: dig(rec, FIELD_MAP.customer),
      customerInn: dig(rec, FIELD_MAP.customerInn),
      winner: dig(rec, FIELD_MAP.winner),
      winnerInn: dig(rec, FIELD_MAP.winnerInn),
      startPrice: money("startPrice"),
      finalPrice: money("finalPrice"),
      volume: parseNumber(dig(rec, FIELD_MAP.volume)),
      volumeUnit: dig(rec, FIELD_MAP.volumeUnit),
      platform: dig(rec, FIELD_MAP.platform),
      status: statusFromRaw(dig(rec, FIELD_MAP.status)),
      publishedAt: dig(rec, FIELD_MAP.publishedAt),
      resultDate: dig(rec, FIELD_MAP.resultDate),
      url: dig(rec, FIELD_MAP.url),
    };
  }

  async search(query: TenderQuery): Promise<Tender[]> {
    const url = this.buildUrl(query);
    const authValue = this.cfg.authScheme
      ? `${this.cfg.authScheme} ${this.cfg.apiKey}`
      : this.cfg.apiKey;
    let payload: any;
    try {
      const resp = await fetch(url, {
        headers: { [this.cfg.authHeader]: authValue, Accept: "application/json" },
      });
      if (!resp.ok) {
        console.error(`Aggregator HTTP ${resp.status} for ${url}`);
        return [];
      }
      payload = await resp.json();
    } catch (err) {
      console.error("Aggregator request failed", err);
      return [];
    }

    let records: any = Array.isArray(payload) ? payload : dig(payload, RESULTS_PATH);
    if (!Array.isArray(records)) return [];
    const tenders = records
      .filter((r) => r && typeof r === "object")
      .map((r) => this.mapRecord(r));
    return filterTenders(tenders, query);
  }
}
