/**
 * Доменная модель тендера (нормализованный вид, независимый от источника).
 */

export type TenderStatus =
  | "published"
  | "bidding"
  | "completed"
  | "cancelled"
  | "unknown";

export interface Money {
  amount: number;
  currency: string;
}

export interface Tender {
  id: string;
  registryNumber?: string;
  title: string;
  okpd2?: string;
  law?: string;
  customer?: string;
  customerInn?: string;
  winner?: string;
  winnerInn?: string;
  startPrice?: Money; // НМЦК
  finalPrice?: Money; // итоговая цена контракта
  volume?: number; // объём
  volumeUnit?: string; // "т", "м", "шт"
  platform?: string; // ЭТП / площадка
  status: TenderStatus;
  publishedAt?: string; // ISO-дата
  resultDate?: string; // ISO-дата подведения итогов
  url?: string;
}

const STATUS_MAP: Record<string, TenderStatus> = {
  completed: "completed",
  complete: "completed",
  finished: "completed",
  result: "completed",
  results: "completed",
  итоги: "completed",
  завершен: "completed",
  завершена: "completed",
  завершено: "completed",
  "подведены итоги": "completed",
  published: "published",
  "подача заявок": "published",
  bidding: "bidding",
  торги: "bidding",
  cancelled: "cancelled",
  canceled: "cancelled",
  отменен: "cancelled",
  "не состоялась": "cancelled",
};

export function statusFromRaw(value?: string | null): TenderStatus {
  if (!value) return "unknown";
  return STATUS_MAP[String(value).trim().toLowerCase()] ?? "unknown";
}

export function isVerified(t: Tender): boolean {
  return t.status === "completed" && Boolean(t.winner);
}

export function bestPrice(t: Tender): Money | undefined {
  return t.finalPrice ?? t.startPrice;
}

export function formatMoney(m?: Money): string {
  if (!m) return "—";
  const formatted = m.amount
    .toFixed(2)
    .replace(/\B(?=(\d{3})+(?!\d))/g, " ");
  return `${formatted} ${m.currency}`;
}

export function formatDate(iso?: string): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "—";
  const dd = String(d.getUTCDate()).padStart(2, "0");
  const mm = String(d.getUTCMonth() + 1).padStart(2, "0");
  return `${dd}.${mm}.${d.getUTCFullYear()}`;
}
