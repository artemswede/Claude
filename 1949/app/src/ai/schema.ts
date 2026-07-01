import { z } from "zod";

/**
 * Строгий контракт распознавания еды. Модель обязана вернуть JSON
 * этой формы; всё валидируется через Zod, прежде чем попасть в дневник.
 */
export const FoodRecognitionSchema = z.object({
  dish: z.string().min(1).max(200),
  portion_grams: z.coerce.number().min(0).max(5000),
  kcal: z.coerce.number().min(0).max(10000),
  protein: z.coerce.number().min(0).max(1000),
  fat: z.coerce.number().min(0).max(1000),
  carb: z.coerce.number().min(0).max(2000),
  confidence: z.coerce.number().min(0).max(1).catch(0.5),
  assumptions: z.string().max(4000).optional().default("").catch(""),
});

export type FoodRecognition = z.infer<typeof FoodRecognitionSchema>;

/** Одна позиция из распознанного меню/этикетки. */
export const FoodItemSchema = z.object({
  dish: z.string().min(1).max(200),
  portion_grams: z.coerce.number().min(0).max(5000),
  kcal: z.coerce.number().min(0).max(10000),
  protein: z.coerce.number().min(0).max(1000),
  fat: z.coerce.number().min(0).max(1000),
  carb: z.coerce.number().min(0).max(2000),
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

/** Убирает управляющие символы (U+0000–U+001F) — частая причина невалидного JSON. */
function stripControlChars(s: string): string {
  let out = "";
  for (const ch of s) {
    out += ch.codePointAt(0)! < 0x20 ? " " : ch;
  }
  return out;
}

/**
 * Вырезает ПЕРВЫЙ сбалансированный JSON-объект (учёт скобок и строк).
 * Нужно, потому что модель иногда возвращает несколько объектов подряд
 * ({...}{...}) — брать «от первой { до последней }» нельзя.
 */
function firstJsonObject(s: string): string {
  const start = s.indexOf("{");
  if (start === -1) throw new Error("No JSON object found in model output");
  let depth = 0;
  let inStr = false;
  let esc = false;
  for (let i = start; i < s.length; i++) {
    const ch = s[i];
    if (inStr) {
      if (esc) esc = false;
      else if (ch === "\\") esc = true;
      else if (ch === '"') inStr = false;
    } else if (ch === '"') {
      inStr = true;
    } else if (ch === "{") {
      depth++;
    } else if (ch === "}") {
      depth--;
      if (depth === 0) return s.slice(start, i + 1);
    }
  }
  return s.slice(start); // несбалансировано — пусть попробует JSON.parse/починка
}

/**
 * Достаёт первый JSON-объект из текста модели (модели любят
 * оборачивать ответ в ```json ... ``` или добавлять болтовню).
 * Устойчив к нескольким объектам, битому экранированию и управляющим символам.
 */
export function extractJson(text: string): unknown {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/i);
  const candidate = fenced ? fenced[1] : text;
  const raw = firstJsonObject(candidate);
  try {
    return JSON.parse(raw);
  } catch {
    // Чиним частые дефекты: управляющие символы и одиночные обратные слэши.
    const fixed = stripControlChars(raw).replace(/\\(?!["\\/bfnrtu])/g, "");
    return JSON.parse(fixed);
  }
}
