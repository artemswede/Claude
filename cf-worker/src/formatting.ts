/**
 * Рендер тендера в HTML-сообщение Telegram.
 */

import { Tender, bestPrice, formatMoney, formatDate } from "./models";

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function formatVolume(t: Tender): string {
  if (t.volume == null) return "—";
  const value = Math.round(t.volume)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, " ");
  return `${value} ${t.volumeUnit ?? ""}`.trim();
}

export function formatTender(t: Tender): string {
  const lines: string[] = [`<b>📦 ${escapeHtml(t.title || "Без названия")}</b>`];

  if (t.registryNumber) {
    lines.push(`🔢 Реестр. №: <code>${escapeHtml(t.registryNumber)}</code>`);
  }

  lines.push(`💰 Цена: <b>${escapeHtml(formatMoney(bestPrice(t)))}</b>`);
  if (
    t.finalPrice &&
    t.startPrice &&
    t.finalPrice.amount !== t.startPrice.amount
  ) {
    lines.push(`   ↳ НМЦК: ${escapeHtml(formatMoney(t.startPrice))}`);
  }

  lines.push(`📐 Объём: ${escapeHtml(formatVolume(t))}`);

  if (t.winner) {
    const inn = t.winnerInn ? ` (ИНН ${escapeHtml(t.winnerInn)})` : "";
    lines.push(`🏆 Победитель: <b>${escapeHtml(t.winner)}</b>${inn}`);
  } else if (t.law === "223-ФЗ") {
    lines.push("🏆 Поставщик: <i>не раскрыт (223-ФЗ)</i>");
  } else {
    lines.push("🏆 Победитель: <i>не определён</i>");
  }

  if (t.customer) lines.push(`🏢 Заказчик: ${escapeHtml(t.customer)}`);
  if (t.platform) lines.push(`🌐 Площадка: ${escapeHtml(t.platform)}`);
  if (t.okpd2) lines.push(`🏷 ОКПД2: <code>${escapeHtml(t.okpd2)}</code>`);
  if (t.law) lines.push(`⚖️ Закон: ${escapeHtml(t.law)}`);

  lines.push(`📅 Дата итогов: ${escapeHtml(formatDate(t.resultDate ?? t.publishedAt))}`);

  if (t.url) {
    lines.push(`🔗 <a href="${escapeHtml(t.url)}">Открыть в ЕИС</a>`);
  }

  return lines.join("\n");
}

/**
 * Рендер списка тендеров в одно или несколько сообщений (лимит Telegram 4096).
 */
export function formatResults(tenders: Tender[], header?: string): string[] {
  if (tenders.length === 0) {
    return ["По заданному фильтру проверенных тендеров на ТБД не найдено."];
  }

  const cards = tenders.map(formatTender);
  const messages: string[] = [];
  let buffer = header ? `<b>${escapeHtml(header)}</b>\n` : "";
  const sep = "\n\n" + "—".repeat(12) + "\n\n";

  for (const card of cards) {
    const block = card + sep;
    if (buffer.length + block.length > 3800 && buffer) {
      messages.push(buffer.trimEnd());
      buffer = "";
    }
    buffer += block;
  }
  if (buffer) messages.push(buffer.trimEnd());
  return messages;
}
