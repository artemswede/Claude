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
const VISION_MODEL = "@cf/meta/llama-3.2-11b-vision-instruct";

const FOOD_PROMPT = [
  "Определи, что за еда на фото, и оцени пищевую ценность съеденной порции.",
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

interface VisionResponse {
  response?: string;
  description?: string;
}

export class WorkersAIProvider implements AIProvider {
  constructor(private readonly ai: Ai) {}

  private async runVision(imageBytes: Uint8Array, prompt: string): Promise<string> {
    const res = (await this.ai.run(VISION_MODEL as keyof AiModels, {
      messages: [{ role: "user", content: prompt }],
      image: [...imageBytes],
      max_tokens: 512,
    } as never)) as VisionResponse;
    const text = (res.response ?? res.description ?? "").trim();
    if (!text) throw new Error("Empty vision response");
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
}
