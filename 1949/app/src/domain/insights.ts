/**
 * Корреляция «еда × состояние»: какие блюда чаще предшествуют упадку
 * энергии/сонливости. Простая эвристика по временным окнам — не статистика,
 * но даёт пользователю осмысленные наблюдения на его данных.
 */
import type { StateRow, FoodTsRow } from "../db/stats";

// Состояния «спада», которые интересно объяснить едой.
const DIP_MOODS = new Set(["dull", "sleepy", "low_energy", "lacking"]);
const WINDOW_MS = 4 * 60 * 60 * 1000; // приёмы за 4 часа до чек-ина

export interface InsightsResult {
  enough: boolean;
  dips: number; // сколько «спадов» учтено
  topDishes: { name: string; count: number }[];
  avgCarbBefore: number | null; // средние углеводы предшествующих приёмов
}

export function computeEnergyInsights(states: StateRow[], foods: FoodTsRow[]): InsightsResult {
  const dips = states.filter((s) => DIP_MOODS.has(s.mood_tag));
  const foodTimes = foods.map((f) => ({ ...f, t: Date.parse(f.ts) }));

  const dishCounts = new Map<string, number>();
  let carbSum = 0;
  let carbN = 0;
  let matched = 0;

  for (const dip of dips) {
    const dipT = Date.parse(dip.ts);
    if (!Number.isFinite(dipT)) continue;
    const preceding = foodTimes.filter((f) => f.t <= dipT && dipT - f.t <= WINDOW_MS);
    if (preceding.length === 0) continue;
    matched++;
    for (const f of preceding) {
      const key = f.dish_name.toLowerCase().trim();
      dishCounts.set(key, (dishCounts.get(key) ?? 0) + 1);
      carbSum += f.carb;
      carbN += 1;
    }
  }

  const topDishes = [...dishCounts.entries()]
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 3);

  return {
    enough: matched >= 3 && topDishes.length > 0,
    dips: matched,
    topDishes,
    avgCarbBefore: carbN > 0 ? Math.round(carbSum / carbN) : null,
  };
}
