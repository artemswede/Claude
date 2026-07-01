import { InlineKeyboard } from "grammy";
import type { BotContext } from "./bot";
import type { Env } from "../env";
import type { PendingFood } from "./session";
import { WorkersAIProvider } from "../ai/workers-ai";
import { downloadTelegramFile } from "../util/telegram-file";
import { ensureUser, getUserByTgId, logEvent } from "../db/repo";
import { addFoodEntry, getDayTotals } from "../db/food";
import { offerCheckin } from "./checkin";
import { computeFromIngredients } from "../nutrition/compute";
import { localDate } from "../util/time";

const LOW_CONFIDENCE = 0.5;

/** Приём фото еды. Тяжёлую работу (скачивание + распознавание) откладываем
 *  до ответа, но Telegram уже получил 200 OK на уровне webhookCallback. */
export async function handlePhoto(ctx: BotContext, env: Env): Promise<void> {
  if (!ctx.from) return;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — отправь /start 🙂");
    return;
  }

  const photos = ctx.message?.photo;
  if (!photos || photos.length === 0) return;
  // Берём самое большое превью (последнее в массиве).
  const fileId = photos[photos.length - 1].file_id;

  await logEvent(env.DB, user.id, "photo");
  const thinking = await ctx.reply("Анализирую фото… 🍽️");

  try {
    const bytes = await downloadTelegramFile(env.BOT_TOKEN, fileId);
    const ai = new WorkersAIProvider(env.AI);
    // Подпись к фото используем как подсказку (ускоряет и уточняет распознавание).
    const hint = ctx.message?.caption?.trim() || undefined;
    const r = await ai.recognizeFood(bytes, hint);

    // Считаем КБЖУ по разбору на ингредиенты (справочник → OFF → модель).
    const nutr = await computeFromIngredients(r.ingredients, ai);
    const portionG =
      Math.round(r.portion_grams) ||
      r.ingredients.reduce((s, i) => s + i.grams, 0);

    const pending: PendingFood = {
      dish: r.dish,
      portionG,
      kcal: nutr.kcal,
      protein: nutr.protein,
      fat: nutr.fat,
      carb: nutr.carb,
      confidence: r.confidence,
      source: "photo",
      ingredients: nutr.items.map((i) => ({ name: i.name, grams: Math.round(i.grams), source: i.source })),
    };
    ctx.session.pendingFood = pending;

    await ctx.api.deleteMessage(thinking.chat.id, thinking.message_id).catch(() => {});
    await sendFoodCard(ctx, pending);
  } catch (e) {
    console.error("recognizeFood failed:", e);
    await ctx.api.deleteMessage(thinking.chat.id, thinking.message_id).catch(() => {});
    // В dev-режиме показываем причину прямо в чате — упрощает отладку.
    const reason = env.ENVIRONMENT === "dev" ? `\n\n🐞 ${String(e).slice(0, 300)}` : "";
    await ctx.reply(
      `Не удалось распознать фото 😕 Попробуй сделать снимок чётче или введи блюдо вручную: /add${reason}`,
    );
  }
}

/** Карточка распознанного блюда с кнопками подтверждения/правки. */
async function sendFoodCard(ctx: BotContext, p: PendingFood): Promise<void> {
  const lowConf = p.confidence < LOW_CONFIDENCE;
  const lines = [
    `🍽️ *${p.dish}*`,
    `Порция: ~${p.portionG} г`,
    "",
    `🔥 ${p.kcal} ккал · 🥩 ${p.protein} б · 🥑 ${p.fat} ж · 🍚 ${p.carb} у`,
  ];
  if (p.ingredients && p.ingredients.length > 0) {
    lines.push("", "*Состав:*");
    for (const it of p.ingredients) {
      // Помечаем ингредиенты, посчитанные грубой оценкой модели.
      const mark = it.source === "model" || it.source === "unknown" ? " ~" : "";
      lines.push(`• ${it.name} — ${it.grams} г${mark}`);
    }
  }
  lines.push("", "_КБЖУ по базе продуктов и оценке модели. «~» — приблизительно._");
  if (lowConf) {
    lines.push("", "⚠️ Не уверен в весе — лучше уточни порцию.");
  }

  const kb = new InlineKeyboard()
    .text("✅ Верно", "food:confirm")
    .text("✏️ Порция", "food:edit_portion")
    .row()
    .text("🍲 Другое блюдо", "food:edit_dish")
    .text("❌ Отмена", "food:cancel");

  await ctx.reply(lines.join("\n"), { parse_mode: "Markdown", reply_markup: kb });
}

/** Обработка inline-кнопок карточки еды. Возвращает true, если событие наше. */
export async function handleFoodCallback(ctx: BotContext, env: Env): Promise<boolean> {
  const data = ctx.callbackQuery?.data;
  if (!data || !data.startsWith("food:")) return false;

  const p = ctx.session.pendingFood;
  const action = data.slice("food:".length);

  if (!p) {
    await ctx.answerCallbackQuery("Карточка устарела");
    return true;
  }

  if (action === "confirm") {
    await confirmFood(ctx, env, p);
  } else if (action === "edit_portion") {
    p.editing = "portion";
    await ctx.answerCallbackQuery();
    await ctx.reply("Введи вес порции в граммах (например, 250):");
  } else if (action === "edit_dish") {
    p.editing = "dish";
    await ctx.answerCallbackQuery();
    await ctx.reply("Напиши, что это за блюдо:");
  } else if (action === "cancel") {
    ctx.session.pendingFood = undefined;
    await ctx.answerCallbackQuery("Отменено");
    await ctx.editMessageReplyMarkup({ reply_markup: undefined }).catch(() => {});
  } else {
    await ctx.answerCallbackQuery();
  }
  return true;
}

/** Текстовый ввод при правке порции/блюда. Возвращает true, если ввод обработан. */
export async function handleFoodEditText(ctx: BotContext, env: Env): Promise<boolean> {
  const p = ctx.session.pendingFood;
  if (!p || !p.editing) return false;
  const text = ctx.message?.text?.trim() ?? "";

  if (p.editing === "portion") {
    const newG = parseFloat(text.replace(",", "."));
    if (!Number.isFinite(newG) || newG <= 0 || newG > 5000) {
      await ctx.reply("Введи вес в граммах от 1 до 5000.");
      return true;
    }
    // Пересчёт пропорционально новой порции — и итоги, и граммовки ингредиентов.
    if (p.portionG > 0) {
      const k = newG / p.portionG;
      p.kcal = Math.round(p.kcal * k);
      p.protein = Math.round(p.protein * k);
      p.fat = Math.round(p.fat * k);
      p.carb = Math.round(p.carb * k);
      if (p.ingredients) {
        p.ingredients = p.ingredients.map((it) => ({ ...it, grams: Math.round(it.grams * k) }));
      }
    }
    p.portionG = Math.round(newG);
    p.confidence = Math.max(p.confidence, LOW_CONFIDENCE); // вес уточнён вручную
    p.editing = undefined;
    await sendFoodCard(ctx, p);
    return true;
  }

  if (p.editing === "dish") {
    if (text.length < 2 || text.length > 120) {
      await ctx.reply("Название должно быть от 2 до 120 символов.");
      return true;
    }
    p.dish = text;
    p.editing = undefined;
    // Разбираем исправлённое блюдо на ингредиенты и считаем КБЖУ по базе.
    const wait = await ctx.reply("Пересчитываю КБЖУ… 🔄");
    let estimateErr: unknown = null;
    try {
      const ai = new WorkersAIProvider(env.AI);
      const b = await ai.breakdownFromText(text, p.portionG);
      const nutr = await computeFromIngredients(b.ingredients, ai);
      p.dish = b.dish || text;
      p.portionG = Math.round(b.portion_grams) || b.ingredients.reduce((s, i) => s + i.grams, 0) || p.portionG;
      p.kcal = nutr.kcal;
      p.protein = nutr.protein;
      p.fat = nutr.fat;
      p.carb = nutr.carb;
      p.confidence = b.confidence;
      p.ingredients = nutr.items.map((i) => ({ name: i.name, grams: Math.round(i.grams), source: i.source }));
    } catch (e) {
      estimateErr = e;
      console.error("breakdownFromText failed:", e);
    }
    await ctx.api.deleteMessage(wait.chat.id, wait.message_id).catch(() => {});
    if (estimateErr && env.ENVIRONMENT === "dev") {
      await ctx.reply(`⚠️ Не удалось пересчитать КБЖУ (оставил прежние).\n🐞 ${String(estimateErr).slice(0, 300)}`);
    }
    await sendFoodCard(ctx, p);
    return true;
  }

  return false;
}

async function confirmFood(ctx: BotContext, env: Env, p: PendingFood): Promise<void> {
  if (!ctx.from) return;
  const user = await ensureUser(env.DB, ctx.from.id);
  const date = localDate(user.tz);

  await addFoodEntry(env.DB, {
    userId: user.id,
    localDate: date,
    dishName: p.dish,
    kcal: p.kcal,
    prot: p.protein,
    fat: p.fat,
    carb: p.carb,
    portionG: p.portionG,
    confidence: p.confidence,
    source: p.source,
  });
  await logEvent(env.DB, user.id, "confirm", { dish: p.dish, source: p.source });

  ctx.session.pendingFood = undefined;
  await ctx.answerCallbackQuery("Записал!");
  await ctx.editMessageReplyMarkup({ reply_markup: undefined }).catch(() => {});

  const totals = await getDayTotals(env.DB, user.id, date);
  const goalK = user.goal_kcal ?? 0;
  const leftK = goalK ? Math.max(0, goalK - Math.round(totals.kcal)) : 0;

  const lines = [
    `✅ Записал: *${p.dish}*`,
    "",
    `Сегодня: ${Math.round(totals.kcal)} ккал` + (goalK ? ` из ${goalK}` : ""),
    `🥩 ${Math.round(totals.prot)} б · 🥑 ${Math.round(totals.fat)} ж · 🍚 ${Math.round(totals.carb)} у`,
  ];
  if (goalK) lines.push("", `Осталось на сегодня: *${leftK}* ккал`);

  await ctx.reply(lines.join("\n"), { parse_mode: "Markdown" });

  // Лёгкий чек-ин состояния сразу после приёма пищи.
  await offerCheckin(ctx, env);
}
