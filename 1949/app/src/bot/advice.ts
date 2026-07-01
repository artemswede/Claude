import { InlineKeyboard } from "grammy";
import type { BotContext } from "./bot";
import type { Env } from "../env";
import { getUserByTgId, logEvent } from "../db/repo";
import { getDayEntries, getDayTotals } from "../db/food";
import { getLatestMood } from "../db/state";
import { addRecommendation, getRecommendation } from "../db/recs";
import { generateAdvice } from "../ai/advise";
import { buildBasket, type Basket } from "../domain/basket";
import { buildPartnerLink } from "../domain/partner";
import type { AdviceContext } from "../domain/advice";
import { dayPart, localDate, localHour } from "../util/time";

const STATUS_EMOJI: Record<string, string> = {
  success: "🟢",
  warning: "🟡",
  danger: "🔴",
};

/** /advice — собрать контекст, выдать совет и корзину с кнопкой заказа. */
export async function handleAdvice(ctx: BotContext, env: Env): Promise<void> {
  if (!ctx.from) return;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — /start 🙂");
    return;
  }

  const date = localDate(user.tz);
  const [totals, mood, entries] = await Promise.all([
    getDayTotals(env.DB, user.id, date),
    getLatestMood(env.DB, user.id, date),
    getDayEntries(env.DB, user.id, date),
  ]);

  const consumed = { kcal: totals.kcal, prot: totals.prot, fat: totals.fat, carb: totals.carb };
  const target = {
    kcal: user.goal_kcal ?? 0,
    prot: user.goal_prot ?? 0,
    fat: user.goal_fat ?? 0,
    carb: user.goal_carb ?? 0,
  };

  const adviceCtx: AdviceContext = {
    dayPart: dayPart(localHour(user.tz)),
    mood,
    consumed,
    target,
    goal: user.goal,
    eaten: entries.map((e) => e.dish_name),
  };

  const advice = await generateAdvice(env.AI, adviceCtx);
  const basket = buildBasket(consumed, target, advice);
  const recId = await addRecommendation(
    env.DB,
    user.id,
    advice.status,
    advice.adviceText,
    JSON.stringify(basket),
  );
  await logEvent(env.DB, user.id, "rec_shown", { status: advice.status });

  const lines = [
    `${STATUS_EMOJI[advice.status] ?? ""} *${advice.headerStatus}*`,
    "",
    advice.adviceText,
    "",
    "*🛒 Корзина:*",
    ...basket.items.map((i) => `• ${i}`),
    "",
    `_${basket.reason}_`,
  ];

  const kb = new InlineKeyboard()
    .text("🛒 Заказать корзину", `basket:${recId}`)
    .row()
    .text("🔄 Другой вариант", "advice:more");
  await ctx.reply(lines.join("\n"), { parse_mode: "Markdown", reply_markup: kb });
}

/** Кнопка «Другой вариант» — генерируем ещё одну рекомендацию. */
export async function handleAdviceMoreCallback(ctx: BotContext, env: Env): Promise<boolean> {
  const data = ctx.callbackQuery?.data;
  if (data !== "advice:more") return false;
  await ctx.answerCallbackQuery();
  await handleAdvice(ctx, env);
  return true;
}

/** Клик по «Заказать корзину»: фиксируем KPI и отдаём диплинк партнёра. */
export async function handleBasketCallback(ctx: BotContext, env: Env): Promise<boolean> {
  const data = ctx.callbackQuery?.data;
  if (!data || !data.startsWith("basket:")) return false;

  const recId = parseInt(data.slice("basket:".length), 10);
  const rec = Number.isFinite(recId) ? await getRecommendation(env.DB, recId) : null;
  if (!rec || !rec.basket_json) {
    await ctx.answerCallbackQuery("Корзина устарела");
    return true;
  }

  const basket = JSON.parse(rec.basket_json) as Basket;
  const link = buildPartnerLink(env, basket.query);

  await logEvent(env.DB, rec.user_id, "basket_click", { recId, partner: link.name });
  await ctx.answerCallbackQuery();

  const kb = new InlineKeyboard().url(`Открыть ${link.name}`, link.url);
  await ctx.reply(
    `Готово! Открой корзину в ${link.name} и оформи заказ 👇`,
    { reply_markup: kb },
  );
  return true;
}
