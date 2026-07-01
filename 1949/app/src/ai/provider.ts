import type { FoodRecognition, MenuRecognition } from "./schema";

/**
 * Абстракция над ИИ. Бизнес-логика зависит только от этого интерфейса,
 * поэтому Workers AI можно заменить на внешний провайдер без правок
 * хэндлеров (см. решение в АРХИТЕКТУРА.md §3).
 */
export interface AIProvider {
  /** Распознать готовое блюдо по фото → КБЖУ. */
  recognizeFood(imageBytes: Uint8Array): Promise<FoodRecognition>;
  /** Распознать меню ресторана по фото → список блюд. */
  recognizeMenu(imageBytes: Uint8Array): Promise<MenuRecognition>;
  /** Распознать этикетку/состав продукта → КБЖУ. */
  recognizeLabel(imageBytes: Uint8Array): Promise<FoodRecognition>;
  /** Оценить КБЖУ по названию блюда и весу порции (когда пользователь исправил блюдо вручную). */
  estimateFromText(dish: string, portionG: number): Promise<FoodRecognition>;
}
