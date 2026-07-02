import { InlineKeyboard } from "grammy";
import type { BotContext } from "./bot";
import type { Env } from "../env";
import { ensureUser, getUserByTgId, logEvent } from "../db/repo";
import { addFoodEntry, deleteFoodEntryById, getDayEntries, getDayTotals } from "../db/food";
import { renderMacroStatus } from "../domain/macrobar";
import { handleAdvice } from "./advice";
import { localDate } from "../util/time";

/** /today — итоги дня, полосы КБЖУ, лента приёмов, удаление последнего. */
export async function handleToday(ctx: BotContext, env: Env): Promise<void> {
  if (!ctx.from) return;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — /start 🙂");
    return;
  }
  const date = localDate(user.tz);
  const [totals, entries] = await Promise.all([
    getDayTotals(env.DB, user.id, date),
    getDayEntries(env.DB, user.id, date),
  ]);

  const lines: string[] = [`📊 *Сегодня* (${date})`, ""];

  if (entries.length === 0) {
    lines.push("Пока пусто. Пришли фото еды или добавь вручную: /add");
    await ctx.reply(lines.join("\n"), { parse_mode: "Markdown" });
    return;
  }

  const consumed = { kcal: totals.kcal, prot: totals.prot, fat: totals.fat, carb: totals.carb };
  const target = {
    kcal: user.goal_kcal ?? 0,
    prot: user.goal_prot ?? 0,
    fat: user.goal_fat ?? 0,
    carb: user.goal_carb ?? 0,
  };
  lines.push(renderMacroStatus(consumed, target, null), "", "*Приёмы:* (🗑 — удалить)");
  for (const e of entries) {
    const portion = e.portion_g ? `, ${Math.round(e.portion_g)} г` : "";
    lines.push(`• ${e.dish_name}${portion} — ${Math.round(e.kcal)} ккал`);
  }

  const kb = new InlineKeyboard().text("🥗 Совет", "today:advice").row();
  // Кнопка удаления на каждый приём (до 10, чтобы не раздувать клавиатуру).
  for (const e of entries.slice(0, 10)) {
    const label = e.dish_name.length > 24 ? e.dish_name.slice(0, 23) + "…" : e.dish_name;
    kb.text(`🗑 ${label}`, `delitem:${e.id}`).row();
  }
  await ctx.reply(lines.join("\n"), { parse_mode: "Markdown", reply_markup: kb });
}

/** Кнопки под /today: удаление конкретного приёма и переход к совету. */
export async function handleTodayCallback(ctx: BotContext, env: Env): Promise<boolean> {
  const data = ctx.callbackQuery?.data;
  if (!data) return false;

  if (data.startsWith("delitem:")) {
    if (!ctx.from) return true;
    const id = parseInt(data.slice("delitem:".length), 10);
    const user = await getUserByTgId(env.DB, ctx.from.id);
    if (user && Number.isFinite(id)) {
      const removed = await deleteFoodEntryById(env.DB, id, user.id);
      await ctx.answerCallbackQuery(removed ? `Удалил: ${removed}` : "Уже удалено");
    } else {
      await ctx.answerCallbackQuery();
    }
    await ctx.editMessageReplyMarkup({ reply_markup: undefined }).catch(() => {});
    await handleToday(ctx, env); // перерисовываем актуальный день
    return true;
  }

  if (data === "today:advice") {
    await ctx.answerCallbackQuery();
    await handleAdvice(ctx, env);
    return true;
  }

  return false;
}

/** /add — старт ручного ввода приёма. */
export async function startManualAdd(ctx: BotContext, env: Env): Promise<void> {
  if (!ctx.from) return;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — /start 🙂");
    return;
  }
  ctx.session.manual = { step: "name" };
  await ctx.reply("Ручной ввод. Как называется блюдо?");
}

/** Текстовые шаги ручного ввода. Возвращает true, если ввод обработан. */
export async function handleManualText(ctx: BotContext, env: Env): Promise<boolean> {
  const d = ctx.session.manual;
  if (!d) return false;
  const text = ctx.message?.text?.trim() ?? "";

  if (d.step === "name") {
    if (text.length < 2 || text.length > 120) {
      await ctx.reply("Название от 2 до 120 символов.");
      return true;
    }
    d.name = text;
    d.step = "kcal";
    await ctx.reply("Сколько калорий? (число)");
    return true;
  }

  const num = parseFloat(text.replace(",", "."));
  const isNum = Number.isFinite(num) && num >= 0;

  if (d.step === "kcal") {
    if (!isNum || num > 10000) {
      await ctx.reply("Введи калории числом (0–10000).");
      return true;
    }
    d.kcal = Math.round(num);
    d.step = "protein";
    await ctx.reply("Белки, г? (число, или 0 если не знаешь)");
    return true;
  }
  if (d.step === "protein") {
    if (!isNum || num > 1000) {
      await ctx.reply("Введи белки числом (0–1000).");
      return true;
    }
    d.protein = Math.round(num);
    d.step = "fat";
    await ctx.reply("Жиры, г?");
    return true;
  }
  if (d.step === "fat") {
    if (!isNum || num > 1000) {
      await ctx.reply("Введи жиры числом (0–1000).");
      return true;
    }
    d.fat = Math.round(num);
    d.step = "carb";
    await ctx.reply("Углеводы, г?");
    return true;
  }
  if (d.step === "carb") {
    if (!isNum || num > 2000) {
      await ctx.reply("Введи углеводы числом (0–2000).");
      return true;
    }
    d.carb = Math.round(num);
    await finishManual(ctx, env);
    return true;
  }

  return false;
}

async function finishManual(ctx: BotContext, env: Env): Promise<void> {
  const d = ctx.session.manual;
  if (!ctx.from || !d || !d.name || d.kcal == null) {
    ctx.session.manual = undefined;
    return;
  }
  const user = await ensureUser(env.DB, ctx.from.id);
  const date = localDate(user.tz);

  await addFoodEntry(env.DB, {
    userId: user.id,
    localDate: date,
    dishName: d.name,
    kcal: d.kcal,
    prot: d.protein ?? 0,
    fat: d.fat ?? 0,
    carb: d.carb ?? 0,
    portionG: null,
    confidence: null,
    source: "manual",
  });
  await logEvent(env.DB, user.id, "manual_add", { dish: d.name });
  ctx.session.manual = undefined;

  const totals = await getDayTotals(env.DB, user.id, date);
  const goalK = user.goal_kcal ?? 0;
  const left = goalK ? Math.max(0, goalK - Math.round(totals.kcal)) : 0;
  const lines = [
    `✅ Добавил: *${d.name}* — ${d.kcal} ккал`,
    "",
    `Сегодня: ${Math.round(totals.kcal)}${goalK ? ` / ${goalK}` : ""} ккал`,
  ];
  if (goalK) lines.push(`Осталось: *${left}* ккал`);
  await ctx.reply(lines.join("\n"), { parse_mode: "Markdown" });
}
