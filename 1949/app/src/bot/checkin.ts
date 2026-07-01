import { InlineKeyboard } from "grammy";
import type { BotContext } from "./bot";
import type { Env } from "../env";
import { getUserByTgId, logEvent } from "../db/repo";
import { getDayTotals } from "../db/food";
import { addMoodCheckin } from "../db/state";
import { moodById, moodsFor } from "../domain/states";
import { handleAdvice } from "./advice";
import { localDate } from "../util/time";

/** Предлагает быстрый чек-ин состояния. Набор зависит от того, ел ли сегодня. */
export async function offerCheckin(ctx: BotContext, env: Env): Promise<void> {
  if (!ctx.from) return;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — /start 🙂");
    return;
  }
  const date = localDate(user.tz);
  const totals = await getDayTotals(env.DB, user.id, date);
  const moods = moodsFor(totals.count > 0);

  const kb = new InlineKeyboard();
  moods.forEach((m, i) => {
    kb.text(`${m.emoji} ${m.label}`, `mood:${m.id}`);
    if (i % 2 === 1) kb.row();
  });

  await ctx.reply("Как самочувствие сейчас?", { reply_markup: kb });
}

/** Обработка выбора состояния. Возвращает true, если событие наше. */
export async function handleCheckinCallback(ctx: BotContext, env: Env): Promise<boolean> {
  const data = ctx.callbackQuery?.data;
  if (!data || !data.startsWith("mood:")) return false;

  const id = data.slice("mood:".length);
  const mood = moodById(id);
  if (!mood || !ctx.from) {
    await ctx.answerCallbackQuery();
    return true;
  }

  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (user) {
    const date = localDate(user.tz);
    await addMoodCheckin(env.DB, user.id, date, id);
    await logEvent(env.DB, user.id, "state_checkin", { mood: id });
  }

  await ctx.answerCallbackQuery("Записал!");
  await ctx.editMessageReplyMarkup({ reply_markup: undefined }).catch(() => {});
  await ctx.reply(`Отметил: ${mood.emoji} ${mood.label}. Вот совет с учётом самочувствия:`);
  // Сразу показываем совет и корзину — без лишнего шага.
  await handleAdvice(ctx, env);
  return true;
}
