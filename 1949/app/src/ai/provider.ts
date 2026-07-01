import type { FoodBreakdown, MenuRecognition } from "./schema";
import type { Per100Macros } from "../nutrition/table";

/**
 * Абстракция над ИИ. Бизнес-логика зависит только от этого интерфейса,
 * поэтому Workers AI можно заменить на внешний провайдер без правок
 * хэндлеров (см. решение в АРХИТЕКТУРА.md §3).
 */
export interface AIProvider {
  /** Распознать блюдо по фото → название + разбор на ингредиенты с граммами. */
  recognizeFood(imageBytes: Uint8Array): Promise<FoodBreakdown>;
  /** Разложить блюдо на ингредиенты по его названию (ручная правка). */
  breakdownFromText(dish: string, portionG: number): Promise<FoodBreakdown>;
  /** Распознать меню ресторана по фото → список блюд. */
  recognizeMenu(imageBytes: Uint8Array): Promise<MenuRecognition>;
  /** Оценить КБЖУ на 100 г для списка продуктов (когда их нет в базе). */
  estimatePer100(names: string[]): Promise<Record<string, Per100Macros>>;
}
