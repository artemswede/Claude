/**
 * Визуализация КБЖУ: полоса прогресса по каждому макросу с зонами
 * (🟢 в норме / 🟡 близко к цели / 🔴 перебор) и прогнозом, как ляжет
 * рекомендованный приём, если его съесть.
 *
 * Легенда полосы: x — съедено, o — добавит рекомендация, · — ещё осталось.
 */
import type { Macros } from "./advice";
import { lookupTable } from "../nutrition/table";

const WIDTH = 10;

/** Вес порции из строки продукта («... (150 г)») — иначе разумный дефолт. */
function parseGrams(text: string): number {
  const m = text.match(/(\d{2,4})\s*г/);
  return m ? parseInt(m[1], 10) : 150;
}

/** Оценка КБЖУ рекомендованного продукта по справочнику (для прогноза). null — если неизвестен. */
export function estimateRecMacros(product: string): Macros | null {
  const per100 = lookupTable(product);
  if (!per100) return null;
  const f = parseGrams(product) / 100;
  return {
    kcal: Math.round(per100.kcal * f),
    prot: Math.round(per100.protein * f),
    fat: Math.round(per100.fat * f),
    carb: Math.round(per100.carb * f),
  };
}

function zone(projected: number, target: number): string {
  if (target <= 0) return "⚪";
  const r = projected / target;
  if (r <= 1.0) return "🟢";
  if (r <= 1.15) return "🟡";
  return "🔴";
}

function bar(consumed: number, add: number, target: number): string {
  if (target <= 0) return "—";
  const cSeg = Math.min(WIDTH, Math.round((consumed / target) * WIDTH));
  const aSeg = Math.min(WIDTH - cSeg, Math.round((add / target) * WIDTH));
  const rem = Math.max(0, WIDTH - cSeg - aSeg);
  return "x".repeat(cSeg) + "o".repeat(aSeg) + "·".repeat(rem);
}

function line(icon: string, unit: string, consumed: number, add: number, target: number): string {
  const proj = consumed + add;
  const projStr = add > 0 ? `${Math.round(consumed)}→${Math.round(proj)}` : `${Math.round(consumed)}`;
  return `${zone(proj, target)} ${icon} [${bar(consumed, add, target)}] ${projStr}/${target}${unit}`;
}

/**
 * Блок статуса КБЖУ. rec — макросы рекомендованного приёма (или null,
 * тогда прогноз не показываем, только текущее наполнение).
 */
export function renderMacroStatus(consumed: Macros, target: Macros, rec: Macros | null): string {
  const r = rec ?? { kcal: 0, prot: 0, fat: 0, carb: 0 };
  const lines = [
    "```",
    line("🔥", "", consumed.kcal, r.kcal, target.kcal),
    line("🥩", "г", consumed.prot, r.prot, target.prot),
    line("🥑", "г", consumed.fat, r.fat, target.fat),
    line("🍚", "г", consumed.carb, r.carb, target.carb),
    "```",
  ];
  if (rec) lines.push("_x — съедено · o — добавит рекомендация · · — осталось_");
  return lines.join("\n");
}
