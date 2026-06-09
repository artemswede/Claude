/**
 * Провайдер Seldon.API (apitorgi.myseldon.com).
 *
 * Seldon — асинхронный сервис «заказов»:
 *   1) /User/Login            → token (кэшируем в KV на 24 ч)
 *   2) /Contracts/New         → taskId  (по сохранённому фильтру + интервал дат)
 *   3) /Contracts/Status      → опрос до searchStatus.code == 3 (готово)
 *   4) /Contracts/Result      → постранично массив контрактов
 * Затем маппим контракты в наш Tender.
 *
 * ВАЖНО:
 *  - Фильтр ТБД настраивается в веб-интерфейсе Seldon 1.7 (раздел «Контракты»),
 *    а сюда передаётся его filterId. ОКПД2 в запрос напрямую не уходит.
 *  - Контракты доступны только по 44-ФЗ (ограничение Seldon).
 *  - Режим «new» отдаёт только ранее НЕ передававшиеся объекты (и тарифицируется).
 *    Поэтому этот провайдер рассчитан в первую очередь на фоновый опрос (cron):
 *    периодически забираем новые выигранные контракты и шлём подписчикам.
 */

import { Money, Tender } from "../models";
import { TenderQuery } from "../filters";
import { TenderProvider, SearchOptions } from "./base";

export interface SeldonConfig {
  baseUrl: string; // https://apitorgi.myseldon.com
  login: string;
  password: string;
  filterId: number; // сохранённый фильтр ТБД в разделе «Контракты» (reportId=4)
  kv?: KVNamespace; // для кэша токена
  pollAttempts?: number;
  pollDelayMs?: number;
  maxPages?: number;
}

const TOKEN_KV_KEY = "seldon:token";
const TOKEN_TTL_SECONDS = 23 * 3600; // токен живёт 24 ч, обновляем заранее

interface Envelope<T> {
  status?: { code?: number; descr?: string };
  result?: T;
}

function num(value: unknown): number | undefined {
  if (value == null) return undefined;
  if (typeof value === "number") return value;
  const n = parseFloat(String(value));
  return isNaN(n) ? undefined : n;
}

// Короткие обозначения единиц измерения (ОКЕИ).
const UNIT_BY_CODE: Record<string, string> = {
  "168": "т", // тонна
  "166": "кг",
  "006": "м",
  "018": "пог. м",
  "055": "м²",
  "113": "м³",
  "796": "шт",
};

function normUnit(unit: any): string | undefined {
  if (!unit) return undefined;
  const code = unit.code as string | undefined;
  if (code && UNIT_BY_CODE[code]) return UNIT_BY_CODE[code];
  const name = unit.name as string | undefined;
  return name ? name.split(/[;,(]/)[0].trim() : undefined; // "Тонна; ..." → "Тонна"
}

function lawFromReportId(reportId: unknown): string | undefined {
  if (reportId === 1) return "223-ФЗ";
  if (reportId === 2) return "44-ФЗ";
  return undefined;
}

export class SeldonProvider implements TenderProvider {
  readonly name = "seldon";

  constructor(private cfg: SeldonConfig) {}

  private async post<T>(path: string, body: Record<string, unknown>, token?: string): Promise<T> {
    const base = this.cfg.baseUrl.replace(/\/$/, "");
    const url = token ? `${base}${path}?token=${encodeURIComponent(token)}` : `${base}${path}`;
    const resp = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body),
    });
    const data = (await resp.json().catch(() => ({}))) as Envelope<T>;
    const code = data.status?.code;
    if (!resp.ok || (code != null && code !== 200)) {
      throw new Error(`Seldon ${path} → HTTP ${resp.status}, status.code=${code} ${data.status?.descr ?? ""}`);
    }
    return data.result as T;
  }

  private async login(): Promise<string> {
    const res = await this.post<{ token: string }>("/User/Login", {
      name: this.cfg.login,
      password: this.cfg.password,
    });
    if (!res?.token) throw new Error("Seldon: пустой токен от /User/Login");
    return res.token;
  }

  private async getToken(): Promise<string> {
    if (this.cfg.kv) {
      const cached = await this.cfg.kv.get(TOKEN_KV_KEY);
      if (cached) return cached;
    }
    const token = await this.login();
    if (this.cfg.kv) {
      await this.cfg.kv.put(TOKEN_KV_KEY, token, { expirationTtl: TOKEN_TTL_SECONDS });
    }
    return token;
  }

  private async createOrder(token: string, query: TenderQuery, mode: "new" | "update"): Promise<string> {
    const lookback = Math.min(query.lookbackDays, 30); // ограничение Seldon: интервал ≤ 30 дней
    const dateTo = new Date().toISOString();
    const dateFrom = new Date(Date.now() - lookback * 86_400_000).toISOString();
    const path = mode === "update" ? "/Contracts/Update" : "/Contracts/New";
    const res = await this.post<{ taskId: string }>(
      path,
      { filterId: this.cfg.filterId, dateFrom, dateTo },
      token,
    );
    if (!res?.taskId) throw new Error(`Seldon: пустой taskId от ${path}`);
    return res.taskId;
  }

  private async waitReady(token: string, taskId: string): Promise<number> {
    const attempts = this.cfg.pollAttempts ?? 20;
    const delay = this.cfg.pollDelayMs ?? 1500;
    for (let i = 0; i < attempts; i++) {
      const res = await this.post<{ searchStatus?: { code?: number; descr?: string }; quantity?: number }>(
        "/Contracts/Status",
        { taskId },
        token,
      );
      const code = res?.searchStatus?.code;
      if (code === 3) return res.quantity ?? 0; // ready
      if (code === 4) throw new Error("Seldon: заказ завершился с ошибкой (status=error)");
      await new Promise((r) => setTimeout(r, delay)); // wait / in progress
    }
    throw new Error("Seldon: заказ не успел подготовиться за отведённое время");
  }

  private async fetchPages(token: string, taskId: string, quantity: number): Promise<any[]> {
    const perPage = 100;
    const totalPages = Math.max(1, Math.ceil(quantity / perPage));
    const maxPages = Math.min(totalPages, this.cfg.maxPages ?? 5);
    const out: any[] = [];
    for (let page = 1; page <= maxPages; page++) {
      const res = await this.post<{ contracts?: any[] }>(
        "/Contracts/Result",
        { taskId, pageIndex: page },
        token,
      );
      if (Array.isArray(res?.contracts)) out.push(...res.contracts);
    }
    return out;
  }

  private mapContract(c: any): Tender {
    // В документации id встречается и как seldonID, и как seldonId.
    const id = c.seldonID ?? c.seldonId;
    const purchase = c.purchase ?? {};
    const currency = c.currency?.code ?? purchase.currency?.code ?? "RUB";

    const money = (amount: unknown): Money | undefined => {
      const a = num(amount);
      return a == null ? undefined : { amount: a, currency };
    };

    // Победитель = поставщик контракта. По 223-ФЗ поставщик часто не раскрыт
    // (suppliers: []), по 44-ФЗ — заполнен.
    const supplier = Array.isArray(c.suppliers) ? c.suppliers[0] : undefined;

    // Объём: сумма quantity по позициям; единица — у первой позиции с единицей.
    const products: any[] = Array.isArray(c.productList) ? c.productList : [];
    const volume = products.reduce((s, p) => s + (num(p?.quantity) ?? 0), 0) || undefined;
    const volumeUnit = normUnit(products.find((p) => p?.unit)?.unit);
    // Первый непустой код ОКПД2 среди позиций.
    const okpd2 = products.map((p) => p?.classifier?.okpd2?.code).find(Boolean) ?? undefined;

    return {
      id: id != null ? String(id) : c.regNum ?? "",
      registryNumber: c.regNum,
      title: purchase.subject ?? c.contractSubject ?? "Контракт",
      okpd2,
      law: lawFromReportId(purchase.reportId),
      customer: c.customer?.name,
      customerInn: c.customer?.inn,
      winner: supplier?.name,
      winnerInn: supplier?.inn,
      // Главная цифра — цена заключённого контракта. НМЦК всей (часто
      // многолотовой) закупки не показываем, чтобы не вводить в заблуждение.
      finalPrice: money(c.contractPrice ?? c.contractPriceRur),
      volume,
      volumeUnit,
      platform: undefined, // в контрактах площадка отдельным полем не передаётся
      status: "completed",
      publishedAt: c.publishDate,
      resultDate: c.signDate ?? c.publishDate,
      url: c.href,
    };
  }

  async search(query: TenderQuery, options?: SearchOptions): Promise<Tender[]> {
    if (!this.cfg.login || !this.cfg.password || !this.cfg.filterId) {
      console.error("Seldon: не заданы SELDON_LOGIN / SELDON_PASSWORD / SELDON_FILTER_ID");
      return [];
    }
    const mode = options?.mode ?? "new";
    try {
      const token = await this.getToken();
      const taskId = await this.createOrder(token, query, mode);
      const quantity = await this.waitReady(token, taskId);
      console.log(`Seldon: mode=${mode} lookback=${Math.min(query.lookbackDays, 30)}d quantity=${quantity}`);
      if (quantity === 0) return [];
      const contracts = await this.fetchPages(token, taskId, quantity);

      let tenders = contracts.map((c) => this.mapContract(c));

      // Seldon уже отфильтровал ТБД своим сохранённым фильтром, поэтому нашу
      // эвристику ТБД не применяем. Победителя НЕ требуем: по 223-ФЗ поставщик
      // не раскрывается, но цена/объём/заказчик есть — это всё равно полезно.
      if (query.minPrice != null)
        tenders = tenders.filter((t) => (t.finalPrice?.amount ?? t.startPrice?.amount ?? 0) >= query.minPrice!);
      if (query.maxPrice != null)
        tenders = tenders.filter((t) => (t.finalPrice?.amount ?? t.startPrice?.amount ?? Infinity) <= query.maxPrice!);

      tenders.sort((a, b) => {
        const da = new Date(a.resultDate ?? a.publishedAt ?? 0).getTime();
        const db = new Date(b.resultDate ?? b.publishedAt ?? 0).getTime();
        return db - da;
      });
      return tenders.slice(0, query.limit);
    } catch (err) {
      console.error("Seldon search failed:", err);
      return [];
    }
  }
}
