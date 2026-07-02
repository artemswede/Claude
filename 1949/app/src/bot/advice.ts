import { InlineKeyboard } from "grammy";
import type { BotContext } from "./bot";
import type { Env } from "../env";
import { getUserByTgId, logEvent } from "../db/repo";
import { getDayEntries, getDayTotals } from "../db/food";
import { getLatestMood } from "../db/state";
import { addRecommendation, getRecommendation } from "../db/recs";
import { generateAdvice } from "../ai/advise";
import { buildPartnerLink } from "../domain/partner";
import { renderMacroStatus } from "../domain/macrobar";
import { OPTION_LABELS, type AdviceContext, type OptionKey } from "../domain/advice";
import { dayPart, localDate, localHour } from "../util/time";

const STATUS_EMOJI: Record<string, string> = {
  success: "🟢",
  warning: "🟡",
  danger: "🔴",
};

/** Строит поисковый запрос по названию блюда (без граммовок и скобок). */
function cleanQuery(dish: string): string {
  return dish.replace(/\(.*?\)/g, "").replace(/\d+\s*г/g, "").trim();
}

interface StoredOption {
  key: OptionKey;
  dish: string;
  query: string;
}

/** /advice — контекст, совет с вариантами, кнопки заказа каждого. */
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
    dislikes: user.dislikes,
  };

  const thinking = await ctx.reply("Подбираю варианты… 🤔");
  const advice = await generateAdvice(env.AI, adviceCtx);
  await ctx.api.deleteMessage(thinking.chat.id, thinking.message_id).catch(() => {});

  const primary = advice.options[0];
  const recMacros = { kcal: primary.kcal, prot: primary.protein, fat: primary.fat, carb: primary.carb };
  const statusBlock = renderMacroStatus(consumed, target, recMacros);

  const stored: StoredOption[] = advice.options.map((o) => ({
    key: o.key,
    dish: o.dish,
    query: cleanQuery(o.dish),
  }));
  const recId = await addRecommendation(
    env.DB,
    user.id,
    advice.status,
    advice.adviceText,
    JSON.stringify({ options: stored }),
  );
  await logEvent(env.DB, user.id, "rec_shown", { status: advice.status, options: stored.length });

  const lines = [
    `${STATUS_EMOJI[advice.status] ?? ""} *${advice.headerStatus}*`,
    "",
    advice.adviceText,
    "",
    "*📊 Твой день по КБЖУ:*",
    statusBlock,
    "",
    "*🍽 Варианты:*",
  ];
  for (const o of advice.options) {
    lines.push(`${OPTION_LABELS[o.key]}: ${o.dish} — ${o.kcal} ккал (Б${o.protein}/Ж${o.fat}/У${o.carb})`);
  }

  const kb = new InlineKeyboard();
  advice.options.forEach((o) => kb.text(`🛒 ${OPTION_LABELS[o.key]}`, `opt:${recId}:${o.key}`));
  kb.row().text("🔄 Другие варианты", "advice:more");

  await ctx.reply(lines.join("\n"), { parse_mode: "Markdown", reply_markup: kb });
}

/** Кнопка «Другие варианты». */
export async function handleAdviceMoreCallback(ctx: BotContext, env: Env): Promise<boolean> {
  if (ctx.callbackQuery?.data !== "advice:more") return false;
  await ctx.answerCallbackQuery();
  await handleAdvice(ctx, env);
  return true;
}

/** Клик по заказу конкретного варианта: KPI + диплинк партнёра. */
export async function handleBasketCallback(ctx: BotContext, env: Env): Promise<boolean> {
  const data = ctx.callbackQuery?.data;
  if (!data || !data.startsWith("opt:")) return false;

  const [, recIdStr, key] = data.split(":");
  const recId = parseInt(recIdStr, 10);
  const rec = Number.isFinite(recId) ? await getRecommendation(env.DB, recId) : null;
  if (!rec || !rec.basket_json) {
    await ctx.answerCallbackQuery("Совет устарел");
    return true;
  }

  const parsed = JSON.parse(rec.basket_json) as { options: StoredOption[] };
  const opt = parsed.options.find((o) => o.key === key) ?? parsed.options[0];
  const link = buildPartnerLink(env, opt.query);

  await logEvent(env.DB, rec.user_id, "basket_click", { recId, key, partner: link.name });
  await ctx.answerCallbackQuery();

  const kb = new InlineKeyboard().url(`Открыть ${link.name}`, link.url);
  await ctx.reply(`Готово! Открой корзину для «${opt.dish}» в ${link.name} 👇`, { reply_markup: kb });
  return true;
}
