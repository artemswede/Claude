import type { AIProvider } from "./provider";
import {
  FoodRecognitionSchema,
  MenuRecognitionSchema,
  extractJson,
  type FoodRecognition,
  type MenuRecognition,
} from "./schema";

/**
 * Распознавание на Workers AI по ДВУХСТУПЕНЧАТОЙ схеме:
 *  1) vision-модель (LLaVA) описывает изображение обычным текстом — это она умеет надёжно;
 *  2) текстовая модель (Llama 3.1) превращает описание в строгий КБЖУ-JSON — а это надёжно умеет она.
 * Так мы обходим главную проблему: LLaVA плохо держит формат JSON.
 */
const VISION_MODEL = "@cf/llava-hf/llava-1.5-7b-hf";
const TEXT_MODEL = "@cf/meta/llama-3.1-8b-instruct";

const DESCRIBE_FOOD =
  "Look at this food photo. In 1-2 sentences name the dish, list the main visible ingredients, " +
  "and estimate the total portion weight in grams. Be concise and factual.";

const DESCRIBE_LABEL =
  "Read this product's nutrition label. State the product name, serving size, and the numbers " +
  "for calories, protein, fat and carbs (per 100g or per serving). Be concise.";

const DESCRIBE_MENU =
  "List the dishes you can read in this restaurant menu photo, one per line, with any prices or descriptions.";

const STRUCT_FOOD =
  'На основе описания еды оцени пищевую ценность съеденной порции. Верни ТОЛЬКО JSON без пояснений, ' +
  'строго в формате: {"dish": строка по-русски, "portion_grams": число, "kcal": число, ' +
  '"protein": число, "fat": число, "carb": число, "confidence": число от 0 до 1, "assumptions": строка по-русски}. ' +
  "Если из описания непонятен вес порции — снизь confidence. Описание:";

const STRUCT_MENU =
  'На основе описания меню оцени пищевую ценность блюд. Верни ТОЛЬКО JSON без пояснений, строго в формате: ' +
  '{"items": [{"dish": строка по-русски, "portion_grams": число, "kcal": число, "protein": число, "fat": число, "carb": число}]}. ' +
  "Не более 10 основных блюд. Описание:";

interface LlavaResponse {
  description?: string;
}
interface TextGenResponse {
  response?: string;
}

export class WorkersAIProvider implements AIProvider {
  constructor(private readonly ai: Ai) {}

  /** Шаг 1: описание изображения текстом. */
  private async describe(imageBytes: Uint8Array, instruction: string): Promise<string> {
    const res = (await this.ai.run(VISION_MODEL as keyof AiModels, {
      image: [...imageBytes],
      prompt: instruction,
      max_tokens: 300,
    } as never)) as LlavaResponse;
    const text = (res.description ?? "").trim();
    if (!text) throw new Error("Empty vision description");
    return text;
  }

  /** Шаг 2: описание → строгий JSON. */
  private async structure(instruction: string, description: string): Promise<string> {
    const res = (await this.ai.run(TEXT_MODEL as keyof AiModels, {
      messages: [
        { role: "system", content: "Ты отвечаешь только валидным JSON, без markdown и пояснений." },
        { role: "user", content: `${instruction}\n${description}` },
      ],
      max_tokens: 500,
    } as never)) as TextGenResponse;
    return res.response ?? "";
  }

  async recognizeFood(imageBytes: Uint8Array): Promise<FoodRecognition> {
    const desc = await this.describe(imageBytes, DESCRIBE_FOOD);
    const json = await this.structure(STRUCT_FOOD, desc);
    return FoodRecognitionSchema.parse(extractJson(json));
  }

  async recognizeLabel(imageBytes: Uint8Array): Promise<FoodRecognition> {
    const desc = await this.describe(imageBytes, DESCRIBE_LABEL);
    const json = await this.structure(STRUCT_FOOD, desc);
    return FoodRecognitionSchema.parse(extractJson(json));
  }

  async recognizeMenu(imageBytes: Uint8Array): Promise<MenuRecognition> {
    const desc = await this.describe(imageBytes, DESCRIBE_MENU);
    const json = await this.structure(STRUCT_MENU, desc);
    return MenuRecognitionSchema.parse(extractJson(json));
  }
}
