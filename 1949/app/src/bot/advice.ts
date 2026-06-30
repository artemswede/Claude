import type { BotContext } from "./bot";
import type { Env } from "../env";
import { getUserByTgId, logEvent } from "../db/repo";
import { getDayTotals } from "../db/food";
import { getLatestMood } from "../db/state";
import { addRecommendation } from "../db/recs";
import { generateAdvice } from "../ai/advise";
import type { AdviceContext, AdviceResult } from "../domain/advice";
import { dayPart, localDate, localHour } from "../util/time";

const STATUS_EMOJI: Record<string, string> = {
  success: "🟢",
  warning: "🟡",
  danger: "🔴",
};

/** Собирает контекст и выдаёт совет. Возвращает совет + id рекомендации
 *  (нужен этапу 6 для привязки корзины). */
export async function produceAdvice(
  ctx: BotContext,
  env: Env,
): Promise<{ advice: AdviceResult; recId: number } | null> {
  if (!ctx.from) return null;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — /start 🙂");
    return null;
  }

  const date = localDate(user.tz);
  const [totals, mood] = await Promise.all([
    getDayTotals(env.DB, user.id, date),
    getLatestMood(env.DB, user.id, date),
  ]);

  const adviceCtx: AdviceContext = {
    dayPart: dayPart(localHour(user.tz)),
    mood,
    consumed: { kcal: totals.kcal, prot: totals.prot, fat: totals.fat, carb: totals.carb },
    target: {
      kcal: user.goal_kcal ?? 0,
      prot: user.goal_prot ?? 0,
      fat: user.goal_fat ?? 0,
      carb: user.goal_carb ?? 0,
    },
  };

  const advice = await generateAdvice(env.AI, adviceCtx);
  const recId = await addRecommendation(env.DB, user.id, advice.status, advice.adviceText, null);
  await logEvent(env.DB, user.id, "rec_shown", { status: advice.status });

  return { advice, recId };
}

/** /advice — показать совет (без корзины; корзину добавит этап 6). */
export async function handleAdvice(ctx: BotContext, env: Env): Promise<void> {
  const result = await produceAdvice(ctx, env);
  if (!result) return;
  const { advice } = result;

  const text = [
    `${STATUS_EMOJI[advice.status] ?? ""} *${advice.headerStatus}*`,
    "",
    advice.adviceText,
    "",
    `🛒 Рекомендую: ${advice.recommendedProduct}`,
  ].join("\n");

  await ctx.reply(text, { parse_mode: "Markdown" });
}
