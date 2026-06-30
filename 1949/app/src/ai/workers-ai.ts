import type { AIProvider } from "./provider";
import {
  FoodRecognitionSchema,
  MenuRecognitionSchema,
  extractJson,
  type FoodRecognition,
  type MenuRecognition,
} from "./schema";

/**
 * Vision-модель Workers AI. LLaVA имеет стабильный контракт
 * { image: number[], prompt, max_tokens } → { description }.
 * Если качество окажется слабым — меняем модель здесь же, не трогая хэндлеры.
 */
const VISION_MODEL = "@cf/llava-hf/llava-1.5-7b-hf";

const FOOD_PROMPT = [
  "You are a nutrition assistant. Look at the food photo and estimate nutrition.",
  "Respond with ONLY a JSON object, no extra text, in this exact shape:",
  '{"dish": string (in Russian), "portion_grams": number, "kcal": number, "protein": number, "fat": number, "carb": number, "confidence": number 0..1, "assumptions": string (in Russian)}',
  "Estimate per the visible portion. If unsure about portion size, lower the confidence.",
].join("\n");

const LABEL_PROMPT = [
  "You are a nutrition assistant. Read the product label/nutrition facts in the photo.",
  "Respond with ONLY a JSON object, no extra text, in this exact shape:",
  '{"dish": string (in Russian), "portion_grams": number, "kcal": number, "protein": number, "fat": number, "carb": number, "confidence": number 0..1, "assumptions": string (in Russian)}',
  "Use the per-100g or per-serving values shown on the label.",
].join("\n");

const MENU_PROMPT = [
  "You are a nutrition assistant. Read the restaurant menu in the photo.",
  "Respond with ONLY a JSON object, no extra text, in this exact shape:",
  '{"items": [{"dish": string (in Russian), "portion_grams": number, "kcal": number, "protein": number, "fat": number, "carb": number}]}',
  "List up to 10 main dishes you can read. Estimate typical nutrition for each.",
].join("\n");

interface LlavaResponse {
  description?: string;
}

export class WorkersAIProvider implements AIProvider {
  constructor(private readonly ai: Ai) {}

  private async runVision(imageBytes: Uint8Array, prompt: string): Promise<string> {
    // Workers AI vision принимает массив байтов изображения.
    const res = (await this.ai.run(VISION_MODEL as keyof AiModels, {
      image: [...imageBytes],
      prompt,
      max_tokens: 512,
    } as never)) as LlavaResponse;
    return res.description ?? "";
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
