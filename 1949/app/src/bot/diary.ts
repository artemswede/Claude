import type { BotContext } from "./bot";
import type { Env } from "../env";
import { ensureUser, getUserByTgId, logEvent } from "../db/repo";
import { addFoodEntry, getDayEntries, getDayTotals } from "../db/food";
import { localDate } from "../util/time";

/** /today — итоги дня, лента приёмов, остаток до цели. */
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

  const goalK = user.goal_kcal ?? 0;
  const lines: string[] = [`📊 *Сегодня* (${date})`, ""];

  if (entries.length === 0) {
    lines.push("Пока пусто. Пришли фото еды или добавь вручную: /add");
  } else {
    lines.push(
      `🔥 ${Math.round(totals.kcal)}${goalK ? ` / ${goalK}` : ""} ккал`,
      `🥩 ${Math.round(totals.prot)} б · 🥑 ${Math.round(totals.fat)} ж · 🍚 ${Math.round(totals.carb)} у`,
    );
    if (goalK) {
      const left = Math.max(0, goalK - Math.round(totals.kcal));
      lines.push("", `Осталось: *${left}* ккал`);
    }
    lines.push("", "*Приёмы:*");
    for (const e of entries) {
      const portion = e.portion_g ? `, ${Math.round(e.portion_g)} г` : "";
      lines.push(`• ${e.dish_name}${portion} — ${Math.round(e.kcal)} ккал`);
    }
  }

  await ctx.reply(lines.join("\n"), { parse_mode: "Markdown" });
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
