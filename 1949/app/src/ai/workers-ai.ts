import type { AIProvider } from "./provider";
import {
  FoodRecognitionSchema,
  MenuRecognitionSchema,
  extractJson,
  type FoodRecognition,
  type MenuRecognition,
} from "./schema";

/**
 * Распознавание на Workers AI.
 * Модель: llama-3.2-11b-vision-instruct — мультимодальная, инструкции и JSON
 * держит заметно лучше устаревшей LLaVA. Ответ валидируется Zod;
 * парсер extractJson устойчив к битому экранированию.
 * Провайдера легко заменить (см. интерфейс AIProvider).
 */
// Одна проверенная модель и для vision, и для текста (llama-3.1-8b снята с 2026-05-30).
export const AI_MODEL = "@cf/meta/llama-3.2-11b-vision-instruct";

// Общая подсказка о реалистичности макросов (частая ошибка модели — занижение углеводов гарниров).
const MACRO_GUIDANCE =
  "Оценивай КБЖУ реалистично. Помни: гарниры из круп (гречка, рис, овсянка), " +
  "макарон, картофеля, хлеба содержат МНОГО углеводов — обычно 15–30 г на 100 г готового блюда, " +
  "не занижай их. Калорийность должна примерно сходиться: kcal ≈ protein*4 + fat*9 + carb*4.";

const FOOD_PROMPT = [
  "Определи, что за еда на фото, и оцени пищевую ценность съеденной порции.",
  MACRO_GUIDANCE,
  "Ответь ТОЛЬКО JSON, без markdown и пояснений, строго в формате:",
  '{"dish": строка по-русски, "portion_grams": число, "kcal": число, "protein": число, "fat": число, "carb": число, "confidence": число от 0 до 1, "assumptions": строка по-русски}',
  "Если вес порции по фото неочевиден — снизь confidence.",
].join("\n");

const LABEL_PROMPT = [
  "Прочитай этикетку/состав продукта на фото и оцени пищевую ценность.",
  "Ответь ТОЛЬКО JSON, без markdown и пояснений, строго в формате:",
  '{"dish": строка по-русски, "portion_grams": число, "kcal": число, "protein": число, "fat": число, "carb": число, "confidence": число от 0 до 1, "assumptions": строка по-русски}',
  "Используй значения на 100 г или на порцию, указанные на этикетке.",
].join("\n");

const MENU_PROMPT = [
  "Прочитай меню ресторана на фото и оцени пищевую ценность блюд.",
  "Ответь ТОЛЬКО JSON, без markdown и пояснений, строго в формате:",
  '{"items": [{"dish": строка по-русски, "portion_grams": число, "kcal": число, "protein": число, "fat": число, "carb": число}]}',
  "Не более 10 основных блюд, которые удаётся прочитать.",
].join("\n");

/** Достаёт текст из ответа Workers AI, каким бы ни был его формат. */
export function responseToText(res: unknown): string {
  if (typeof res === "string") return res;
  if (res && typeof res === "object") {
    const r = res as Record<string, unknown>;
    const val = r.response ?? r.description ?? r.result ?? r.text;
    if (typeof val === "string") return val;
    if (val != null) return JSON.stringify(val); // модель вернула объект — сериализуем
    return JSON.stringify(r);
  }
  return String(res ?? "");
}

export class WorkersAIProvider implements AIProvider {
  constructor(private readonly ai: Ai) {}

  private async runVision(imageBytes: Uint8Array, prompt: string): Promise<string> {
    // Vision-модель принимает изображение вместе с полем `prompt`
    // (формат `messages` + image даёт AiError 3030).
    const res = await this.ai.run(AI_MODEL as keyof AiModels, {
      image: [...imageBytes],
      prompt,
      max_tokens: 512,
    } as never);
    const text = responseToText(res).trim();
    if (!text) throw new Error("Empty vision response");
    return text;
  }

  /** Текстовая генерация той же моделью (без изображения), формат `prompt`. */
  private async runText(prompt: string): Promise<string> {
    const res = await this.ai.run(AI_MODEL as keyof AiModels, {
      prompt,
      max_tokens: 500,
    } as never);
    const text = responseToText(res).trim();
    if (!text) throw new Error("Empty text response");
    return text;
  }

  async recognizeFood(imageBytes: Uint8Array): Promise<FoodRecognition> {
    const text = await this.runVision(imageBytes, FOOD_PROMPT);
    return FoodRecognitionSchema.parse(extractJson(text));
  }

  async recognizeLabel(imageBytes: Uint8Array): Promise<FoodRecognition> {
    const text = await this.runVision(imageBytes, LABEL_PROMPT);
    return FoodRecognitionSchema.parse(extractJson(text));
  }

  async recognizeMenu(imageBytes: Uint8Array): Promise<MenuRecognition> {
    const text = await this.runVision(imageBytes, MENU_PROMPT);
    return MenuRecognitionSchema.parse(extractJson(text));
  }

  async estimateFromText(dish: string, portionG: number): Promise<FoodRecognition> {
    const prompt = [
      `Оцени пищевую ценность блюда "${dish}" для порции ${portionG} г.`,
      MACRO_GUIDANCE,
      "Ответь ТОЛЬКО JSON, без markdown и пояснений, строго в формате:",
      '{"dish": строка по-русски, "portion_grams": число, "kcal": число, "protein": число, "fat": число, "carb": число, "confidence": число от 0 до 1, "assumptions": строка по-русски}',
    ].join("\n");
    const text = await this.runText(prompt);
    return FoodRecognitionSchema.parse(extractJson(text));
  }
}
