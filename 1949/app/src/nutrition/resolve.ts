/**
 * Определение КБЖУ приёма пищи: сначала пробуем реальную базу
 * Open Food Facts по названию, при неудаче — остаётся оценка модели.
 */
import { searchByName, type Per100 } from "./openfoodfacts";

export interface ResolvedNutrition {
  kcal: number;
  protein: number;
  fat: number;
  carb: number;
  source: "off";
  productName: string;
}

/** Пытается получить КБЖУ из OFF под указанную порцию. null — если совпадения нет. */
export async function resolveByName(
  name: string,
  portionG: number,
): Promise<ResolvedNutrition | null> {
  const per100: Per100 | null = await searchByName(name);
  if (!per100) return null;

  const factor = portionG > 0 ? portionG / 100 : 1;
  return {
    kcal: Math.round(per100.kcal * factor),
    protein: Math.round(per100.protein * factor),
    fat: Math.round(per100.fat * factor),
    carb: Math.round(per100.carb * factor),
    source: "off",
    productName: per100.productName,
  };
}
