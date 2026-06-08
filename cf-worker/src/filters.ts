/**
 * Логика отбора «проверенных тендеров на ТБД». Используется и для /search,
 * и для фоновой рассылки — правила в одном месте.
 */

import { Tender, isVerified, bestPrice } from "./models";

const TBD_MIN_DIAMETER = 530; // мм — нижняя граница «большого диаметра»

export interface TenderQuery {
  keywords: string[];
  okpd2Prefixes: string[];
  minPrice?: number;
  maxPrice?: number;
  onlyWithWinner: boolean;
  lookbackDays: number;
  limit: number;
  freeText?: string;
}

export function withFreeText(q: TenderQuery, text?: string): TenderQuery {
  const cleaned = (text ?? "").trim();
  return { ...q, freeText: cleaned || undefined };
}

function matchesKeywords(text: string, keywords: string[]): boolean {
  const lowered = text.toLowerCase();
  return keywords.some((k) => k && lowered.includes(k.toLowerCase()));
}

function matchesOkpd2(code: string | undefined, prefixes: string[]): boolean {
  if (!code) return false;
  return prefixes.some((p) => p && code.startsWith(p));
}

export function looksLikeLargeDiameter(text: string): boolean {
  const lowered = text.toLowerCase();
  if (lowered.includes("большого диаметра") || lowered.includes("тбд")) {
    return true;
  }
  // Любое 3–4-значное число в наименовании, похожее на диаметр >= 530 мм.
  const matches = lowered.match(/\d{3,4}/g);
  if (matches) {
    for (const m of matches) {
      const value = parseInt(m, 10);
      if (value >= TBD_MIN_DIAMETER) return true;
    }
  }
  return false;
}

export function isPipeTender(t: Tender, q: TenderQuery): boolean {
  const title = t.title ?? "";
  const byOkpd2 = matchesOkpd2(t.okpd2, q.okpd2Prefixes);
  const byKeyword = matchesKeywords(title, q.keywords);
  if (!byOkpd2 && !byKeyword) return false;
  return looksLikeLargeDiameter(title) || title.toLowerCase().includes("тбд");
}

export function matches(t: Tender, q: TenderQuery): boolean {
  if (!isPipeTender(t, q)) return false;
  if (q.onlyWithWinner && !isVerified(t)) return false;

  if (q.freeText) {
    const haystack = `${t.title} ${t.customer ?? ""} ${t.winner ?? ""}`;
    if (!matchesKeywords(haystack, [q.freeText])) return false;
  }

  const price = bestPrice(t);
  if (q.minPrice != null && (!price || price.amount < q.minPrice)) return false;
  if (q.maxPrice != null && (!price || price.amount > q.maxPrice)) return false;

  return true;
}

function sortKey(t: Tender): number {
  const iso = t.resultDate ?? t.publishedAt;
  const d = iso ? new Date(iso).getTime() : 0;
  return isNaN(d) ? 0 : d;
}

export function filterTenders(list: Tender[], q: TenderQuery): Tender[] {
  return list
    .filter((t) => matches(t, q))
    .sort((a, b) => sortKey(b) - sortKey(a)) // новые сверху
    .slice(0, q.limit);
}
