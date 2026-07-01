/**
 * Расчёт КБЖУ блюда по разбору на ингредиенты.
 * Для каждого ингредиента источник КБЖУ (на 100 г) выбирается по приоритету:
 *   1) локальный справочник (table) — быстро и надёжно для частых продуктов;
 *   2) Open Food Facts — для упакованных/прочих;
 *   3) оценка модели по ингредиенту — крайний случай (точнее, чем по всему блюду).
 */
import type { AIProvider } from "../ai/provider";
import type { Ingredient } from "../ai/schema";
import { lookupTable, type Per100Macros } from "./table";
import { searchByName } from "./openfoodfacts";

export type NutrSource = "table" | "off" | "model" | "unknown";

export interface ResolvedItem {
  name: string;
  grams: number;
  source: NutrSource;
  kcal: number;
  protein: number;
  fat: number;
  carb: number;
}

export interface ComputedNutrition {
  kcal: number;
  protein: number;
  fat: number;
  carb: number;
  items: ResolvedItem[];
}

function scale(per100: Per100Macros, grams: number) {
  const f = grams / 100;
  return {
    kcal: per100.kcal * f,
    protein: per100.protein * f,
    fat: per100.fat * f,
    carb: per100.carb * f,
  };
}

export async function computeFromIngredients(
  ingredients: Ingredient[],
  provider: AIProvider,
): Promise<ComputedNutrition> {
  const items: ResolvedItem[] = ingredients.map((i) => ({
    name: i.name,
    grams: i.grams,
    source: "unknown" as NutrSource,
    kcal: 0,
    protein: 0,
    fat: 0,
    carb: 0,
  }));

  // 1) Локальный справочник.
  const needOff: number[] = [];
  items.forEach((it, idx) => {
    const t = lookupTable(it.name);
    if (t) applyPer100(it, t, "table");
    else needOff.push(idx);
  });

  // 2) Open Food Facts для оставшихся (параллельно).
  const offResults = await Promise.all(
    needOff.map((idx) => searchByName(items[idx].name).catch(() => null)),
  );
  const needModel: number[] = [];
  needOff.forEach((idx, k) => {
    const off = offResults[k];
    if (off) applyPer100(items[idx], off, "off");
    else needModel.push(idx);
  });

  // 3) Оценка модели по ингредиентам (один батч-запрос).
  if (needModel.length > 0) {
    try {
      const map = await provider.estimatePer100(needModel.map((idx) => items[idx].name));
      for (const idx of needModel) {
        const per100 = matchModel(map, items[idx].name);
        if (per100) applyPer100(items[idx], per100, "model");
      }
    } catch {
      // оставляем unknown (нули) — лучше недооценить, чем упасть
    }
  }

  const total = items.reduce(
    (acc, it) => {
      acc.kcal += it.kcal;
      acc.protein += it.protein;
      acc.fat += it.fat;
      acc.carb += it.carb;
      return acc;
    },
    { kcal: 0, protein: 0, fat: 0, carb: 0 },
  );

  return {
    kcal: Math.round(total.kcal),
    protein: Math.round(total.protein),
    fat: Math.round(total.fat),
    carb: Math.round(total.carb),
    items,
  };
}

function applyPer100(it: ResolvedItem, per100: Per100Macros, source: NutrSource): void {
  const s = scale(per100, it.grams);
  it.kcal = s.kcal;
  it.protein = s.protein;
  it.fat = s.fat;
  it.carb = s.carb;
  it.source = source;
}

/** Сопоставляет ответ модели с названием ингредиента (по вхождению подстроки). */
function matchModel(
  map: Record<string, Per100Macros>,
  name: string,
): Per100Macros | null {
  const n = name.toLowerCase().trim();
  if (map[n]) return map[n];
  for (const key of Object.keys(map)) {
    if (n.includes(key) || key.includes(n)) return map[key];
  }
  return null;
}
