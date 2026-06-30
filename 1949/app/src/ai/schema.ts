import { z } from "zod";

/**
 * Строгий контракт распознавания еды. Модель обязана вернуть JSON
 * этой формы; всё валидируется через Zod, прежде чем попасть в дневник.
 */
export const FoodRecognitionSchema = z.object({
  dish: z.string().min(1).max(120),
  portion_grams: z.number().min(0).max(5000),
  kcal: z.number().min(0).max(10000),
  protein: z.number().min(0).max(1000),
  fat: z.number().min(0).max(1000),
  carb: z.number().min(0).max(2000),
  confidence: z.number().min(0).max(1),
  assumptions: z.string().max(300).optional().default(""),
});

export type FoodRecognition = z.infer<typeof FoodRecognitionSchema>;

/** Одна позиция из распознанного меню/этикетки. */
export const FoodItemSchema = z.object({
  dish: z.string().min(1).max(120),
  portion_grams: z.number().min(0).max(5000),
  kcal: z.number().min(0).max(10000),
  protein: z.number().min(0).max(1000),
  fat: z.number().min(0).max(1000),
  carb: z.number().min(0).max(2000),
});

export const MenuRecognitionSchema = z.object({
  items: z.array(FoodItemSchema).min(1).max(30),
});

export type MenuRecognition = z.infer<typeof MenuRecognitionSchema>;

/** Контракт текстового совета нутрициолога. */
export const AdviceSchema = z.object({
  status: z.enum(["success", "warning", "danger"]),
  headerStatus: z.string().min(1).max(120),
  adviceText: z.string().min(1).max(600),
  recommendedProduct: z.string().min(1).max(200),
});

export type AdviceData = z.infer<typeof AdviceSchema>;

/**
 * Достаёт первый JSON-объект из текста модели (модели любят
 * оборачивать ответ в ```json ... ``` или добавлять болтовню).
 */
export function extractJson(text: string): unknown {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/i);
  const candidate = fenced ? fenced[1] : text;
  const start = candidate.indexOf("{");
  const end = candidate.lastIndexOf("}");
  if (start === -1 || end === -1 || end < start) {
    throw new Error("No JSON object found in model output");
  }
  return JSON.parse(candidate.slice(start, end + 1));
}
