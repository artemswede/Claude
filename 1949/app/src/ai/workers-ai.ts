import type { AIProvider } from "./provider";
import {
  FoodBreakdownSchema,
  MenuRecognitionSchema,
  Per100BatchSchema,
  extractJson,
  type FoodBreakdown,
  type MenuRecognition,
} from "./schema";
import type { Per100Macros } from "../nutrition/table";

/**
 * Распознавание на Workers AI.
 * Модель: llama-3.2-11b-vision-instruct — и vision, и текст (llama-3.1-8b снята).
 * Подход к точности: модель раскладывает блюдо на ИНГРЕДИЕНТЫ с граммами,
 * а КБЖУ считаем по базе (nutrition/compute), а не доверяем «на глаз» модели.
 */
export const AI_MODEL = "@cf/meta/llama-3.2-11b-vision-instruct";

const BREAKDOWN_FORMAT =
  '{"dish": строка по-русски, "portion_grams": число (общий вес порции), ' +
  '"ingredients": [{"name": строка по-русски, "grams": число}], "confidence": число от 0 до 1}';

const FOOD_PROMPT = [
  "Определи блюдо на фото и разложи его на основные ингредиенты с оценкой веса каждого в граммах.",
  "Например: «гречка с индейкой» → [{«гречка варёная», 150}, {«индейка», 80}].",
  "Ответь ТОЛЬКО JSON, без markdown и пояснений, строго в формате:",
  BREAKDOWN_FORMAT,
  "Если вес порции по фото неочевиден — снизь confidence. Названия ингредиентов — простые и обобщённые.",
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
    if (val != null) return JSON.stringify(val);
    return JSON.stringify(r);
  }
  return String(res ?? "");
}

export class WorkersAIProvider implements AIProvider {
  constructor(private readonly ai: Ai) {}

  private async runVision(imageBytes: Uint8Array, prompt: string): Promise<string> {
    // Формат `prompt` + image (формат `messages` + image даёт AiError 3030).
    const res = await this.ai.run(AI_MODEL as keyof AiModels, {
      image: [...imageBytes],
      prompt,
      max_tokens: 700,
    } as never);
    const text = responseToText(res).trim();
    if (!text) throw new Error("Empty vision response");
    return text;
  }

  private async runText(prompt: string): Promise<string> {
    const res = await this.ai.run(AI_MODEL as keyof AiModels, {
      prompt,
      max_tokens: 700,
    } as never);
    const text = responseToText(res).trim();
    if (!text) throw new Error("Empty text response");
    return text;
  }

  async recognizeFood(imageBytes: Uint8Array): Promise<FoodBreakdown> {
    const text = await this.runVision(imageBytes, FOOD_PROMPT);
    return FoodBreakdownSchema.parse(extractJson(text));
  }

  async breakdownFromText(dish: string, portionG: number): Promise<FoodBreakdown> {
    const prompt = [
      `Разложи блюдо "${dish}" (порция ${portionG} г) на основные ингредиенты с весом каждого в граммах.`,
      "Ответь ТОЛЬКО JSON, без markdown и пояснений, строго в формате:",
      BREAKDOWN_FORMAT,
      "Названия ингредиентов — простые и обобщённые.",
    ].join("\n");
    const text = await this.runText(prompt);
    return FoodBreakdownSchema.parse(extractJson(text));
  }

  async recognizeMenu(imageBytes: Uint8Array): Promise<MenuRecognition> {
    const text = await this.runVision(imageBytes, MENU_PROMPT);
    return MenuRecognitionSchema.parse(extractJson(text));
  }

  async estimatePer100(names: string[]): Promise<Record<string, Per100Macros>> {
    if (names.length === 0) return {};
    const prompt = [
      "Для каждого продукта укажи пищевую ценность на 100 г.",
      "Ответь ТОЛЬКО JSON, без markdown и пояснений, строго в формате:",
      '{"items": [{"name": строка, "kcal": число, "protein": число, "fat": число, "carb": число}]}',
      `Продукты: ${names.join(", ")}`,
    ].join("\n");
    const text = await this.runText(prompt);
    const parsed = Per100BatchSchema.parse(extractJson(text));
    const map: Record<string, Per100Macros> = {};
    for (const it of parsed.items) {
      map[it.name.toLowerCase().trim()] = {
        kcal: it.kcal,
        protein: it.protein,
        fat: it.fat,
        carb: it.carb,
      };
    }
    return map;
  }
}
