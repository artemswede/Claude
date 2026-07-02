/**
 * Генерация совета нутрициолога через Workers AI с гарантированным
 * откатом на детерминированный движок (domain/advice).
 */
import { AdviceSchema, extractJson } from "./schema";
import { AI_MODEL, responseToText } from "./workers-ai";
import {
  deterministicAdvice,
  type AdviceContext,
  type AdviceResult,
} from "../domain/advice";
import { moodById } from "../domain/states";
import { GOAL_LABELS } from "../domain/goals";

const DAYPART_RU: Record<string, string> = {
  morning: "утро",
  day: "день",
  evening: "вечер",
  night: "ночь",
};

const GOAL_HINT: Record<string, string> = {
  lose: "цель — снизить вес: советуй сытное при низких калориях (белок, овощи, клетчатка), избегай пустых калорий",
  maintain: "цель — поддержание: держи баланс БЖУ",
  gain: "цель — набор массы: можно калорийнее и больше белка/сложных углеводов",
};

function buildPrompt(ctx: AdviceContext): string {
  const moodLabel = ctx.mood ? (moodById(ctx.mood)?.label ?? ctx.mood) : "не указано";
  const { consumed: c, target: t } = ctx;
  const leftK = Math.max(0, Math.round(t.kcal - c.kcal));
  const leftP = Math.max(0, Math.round(t.prot - c.prot));
  const leftF = Math.max(0, Math.round(t.fat - c.fat));
  const leftC = Math.max(0, Math.round(t.carb - c.carb));
  const goalLabel = ctx.goal ? GOAL_LABELS[ctx.goal] : "не указана";
  const goalHint = ctx.goal ? GOAL_HINT[ctx.goal] : "";
  const eaten = ctx.eaten.length ? ctx.eaten.join(", ") : "пока ничего";

  const dislikes = ctx.dislikes?.trim();

  return [
    "Ты — опытный ИИ-нутрициолог. Предложи ТРИ варианта следующего приёма пищи этому человеку.",
    "Каждый вариант должен укладываться в остаток калорий/макросов, подходить под цель, время суток и самочувствие.",
    "Три варианта с разным характером:",
    "- budget — бюджетный (простые доступные продукты),",
    "- filling — сытный (плотный, максимально закрывает дефицит),",
    "- quick — быстрый (минимум готовки, перекус).",
    "Не повторяй уже съеденное сегодня.",
    dislikes ? `СТРОГО избегай (аллергии/нелюбимое): ${dislikes}.` : "",
    "",
    "Ответь ТОЛЬКО JSON-объектом, без лишнего текста, в формате:",
    '{"status":"success|warning|danger","headerStatus":string,"adviceText":string,"options":[{"key":"budget|filling|quick","dish":строка с порцией в граммах,"kcal":число,"protein":число,"fat":число,"carb":число}]}',
    "adviceText — 1-2 предложения объяснения с опорой на цифры. options — ровно 3 варианта.",
    "",
    `Цель: ${goalLabel}${goalHint ? ` (${goalHint})` : ""}`,
    `Время суток: ${DAYPART_RU[ctx.dayPart] ?? ctx.dayPart}`,
    `Самочувствие: ${moodLabel}`,
    `Съедено сегодня: ${eaten}`,
    `КБЖУ съедено/цель: ккал ${Math.round(c.kcal)}/${t.kcal}, белки ${Math.round(c.prot)}/${t.prot}, жиры ${Math.round(c.fat)}/${t.fat}, углеводы ${Math.round(c.carb)}/${t.carb}`,
    `ОСТАЛОСЬ до цели: ${leftK} ккал, белки ${leftP} г, жиры ${leftF} г, углеводы ${leftC} г`,
  ]
    .filter(Boolean)
    .join("\n");
}

/** Совет от ИИ; при любой ошибке/невалидном ответе — детерминированный fallback. */
export async function generateAdvice(ai: Ai, ctx: AdviceContext): Promise<AdviceResult> {
  try {
    const res = await ai.run(AI_MODEL as keyof AiModels, {
      prompt: buildPrompt(ctx),
      max_tokens: 600,
    } as never);
    const text = responseToText(res);
    const parsed = AdviceSchema.parse(extractJson(text));
    return {
      status: parsed.status,
      headerStatus: parsed.headerStatus,
      adviceText: parsed.adviceText,
      options: parsed.options,
    };
  } catch (e) {
    console.error("generateAdvice fell back to deterministic:", e);
    return deterministicAdvice(ctx);
  }
}
