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

/** Ингредиент блюда с оценкой веса. */
export const IngredientSchema = z.object({
  name: z.string().min(1).max(80),
  grams: z.coerce.number().min(0).max(3000),
});
export type Ingredient = z.infer<typeof IngredientSchema>;

/** Разбор блюда на ингредиенты (для точного расчёта КБЖУ по базе). */
export const FoodBreakdownSchema = z.object({
  dish: z.string().min(1).max(200),
  portion_grams: z.coerce.number().min(0).max(5000).catch(0),
  ingredients: z.array(IngredientSchema).min(1).max(20),
  confidence: z.coerce.number().min(0).max(1).catch(0.5),
});
export type FoodBreakdown = z.infer<typeof FoodBreakdownSchema>;

/** Оценка КБЖУ ингредиентов на 100 г от модели (fallback, когда нет в базе). */
export const Per100BatchSchema = z.object({
  items: z
    .array(
      z.object({
        name: z.string().min(1).max(80),
        kcal: z.coerce.number().min(0).max(1000),
        protein: z.coerce.number().min(0).max(100),
        fat: z.coerce.number().min(0).max(100),
        carb: z.coerce.number().min(0).max(100),
      }),
    )
    .max(20),
});

/** Один вариант рекомендованного приёма. */
export const AdviceOptionSchema = z.object({
  key: z.enum(["budget", "filling", "quick"]).catch("filling"),
  dish: z.string().min(1).max(200),
  kcal: z.coerce.number().min(0).max(5000),
  protein: z.coerce.number().min(0).max(500),
  fat: z.coerce.number().min(0).max(500),
  carb: z.coerce.number().min(0).max(500),
});

/** Контракт текстового совета нутрициолога с несколькими вариантами. */
export const AdviceSchema = z.object({
  status: z.enum(["success", "warning", "danger"]).catch("success"),
  headerStatus: z.string().min(1).max(120),
  adviceText: z.string().min(1).max(600),
  options: z.array(AdviceOptionSchema).min(1).max(3),
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
 * Чинит оборванный JSON (когда вывод модели обрезан по лимиту токенов):
 * отбрасывает недописанный «хвост», закрывает строку и все открытые скобки.
 */
function closeTruncatedJson(s: string): string {
  let inStr = false;
  let esc = false;
  const stack: string[] = [];
  let lastSafe = -1; // индекс последней «целой» границы (после запятой/закрытия на верхнем уровне массива)
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (inStr) {
      if (esc) esc = false;
      else if (ch === "\\") esc = true;
      else if (ch === '"') inStr = false;
    } else if (ch === '"') {
      inStr = true;
    } else if (ch === "{" || ch === "[") {
      stack.push(ch);
    } else if (ch === "}" || ch === "]") {
      stack.pop();
      if (stack.length >= 1) lastSafe = i; // закрыт вложенный объект/элемент
    }
  }
  // Берём до последнего целого элемента и закрываем оставшиеся скобки.
  let out = lastSafe > 0 ? s.slice(0, lastSafe + 1) : s;
  // Пересчитываем незакрытые скобки для обрезанной строки.
  const st: string[] = [];
  let inS = false;
  let es = false;
  for (const ch of out) {
    if (inS) {
      if (es) es = false;
      else if (ch === "\\") es = true;
      else if (ch === '"') inS = false;
    } else if (ch === '"') inS = true;
    else if (ch === "{" || ch === "[") st.push(ch);
    else if (ch === "}" || ch === "]") st.pop();
  }
  if (inS) out += '"';
  while (st.length) out += st.pop() === "{" ? "}" : "]";
  return out;
}

/**
 * Достаёт первый JSON-объект из текста модели (модели любят
 * оборачивать ответ в ```json ... ``` или добавлять болтовню).
 * Устойчив к нескольким объектам, битому экранированию, управляющим символам
 * и обрезанному по лимиту токенов выводу.
 */
export function extractJson(text: string): unknown {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/i);
  const candidate = fenced ? fenced[1] : text;
  const raw = firstJsonObject(candidate);
  const cleaned = stripControlChars(raw).replace(/\\(?!["\\/bfnrtu])/g, "");
  try {
    return JSON.parse(cleaned);
  } catch {
    // Возможно, вывод обрезан — пробуем закрыть структуру.
    return JSON.parse(closeTruncatedJson(cleaned));
  }
}
