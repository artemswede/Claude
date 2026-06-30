/**
 * Генерация совета нутрициолога через Workers AI с гарантированным
 * откатом на детерминированный движок (domain/advice).
 */
import { AdviceSchema, extractJson } from "./schema";
import {
  deterministicAdvice,
  type AdviceContext,
  type AdviceResult,
} from "../domain/advice";
import { moodById } from "../domain/states";

const TEXT_MODEL = "@cf/meta/llama-3.1-8b-instruct";

const DAYPART_RU: Record<string, string> = {
  morning: "утро",
  day: "день",
  evening: "вечер",
  night: "ночь",
};

function buildPrompt(ctx: AdviceContext): string {
  const moodLabel = ctx.mood ? (moodById(ctx.mood)?.label ?? ctx.mood) : "не указано";
  const { consumed: c, target: t } = ctx;
  return [
    "Ты — ИИ-нутрициолог. Дай короткую рекомендацию на следующий приём пищи.",
    "Учитывай время суток, самочувствие и баланс КБЖУ.",
    "Ответь ТОЛЬКО JSON-объектом, без лишнего текста, в формате:",
    '{"status":"success|warning|danger","headerStatus":string,"adviceText":string,"recommendedProduct":string}',
    "Все тексты — на русском, дружелюбно и конкретно. recommendedProduct — продукт/блюдо с порцией.",
    "",
    `Время суток: ${DAYPART_RU[ctx.dayPart] ?? ctx.dayPart}`,
    `Самочувствие: ${moodLabel}`,
    `КБЖУ (съедено / цель): ккал ${Math.round(c.kcal)}/${t.kcal}, белки ${Math.round(c.prot)}/${t.prot}, жиры ${Math.round(c.fat)}/${t.fat}, углеводы ${Math.round(c.carb)}/${t.carb}`,
  ].join("\n");
}

interface TextGenResponse {
  response?: string;
}

/** Совет от ИИ; при любой ошибке/невалидном ответе — детерминированный fallback. */
export async function generateAdvice(ai: Ai, ctx: AdviceContext): Promise<AdviceResult> {
  try {
    const res = (await ai.run(TEXT_MODEL as keyof AiModels, {
      messages: [
        { role: "system", content: "Отвечай только валидным JSON." },
        { role: "user", content: buildPrompt(ctx) },
      ],
      max_tokens: 400,
    } as never)) as TextGenResponse;

    const text = res.response ?? "";
    const parsed = AdviceSchema.parse(extractJson(text));
    return parsed;
  } catch (e) {
    console.error("generateAdvice fell back to deterministic:", e);
    return deterministicAdvice(ctx);
  }
}
