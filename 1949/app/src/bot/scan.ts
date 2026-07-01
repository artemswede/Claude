import { InlineKeyboard } from "grammy";
import type { BotContext } from "./bot";
import type { Env } from "../env";
import type { PendingFood } from "./session";
import { WorkersAIProvider } from "../ai/workers-ai";
import { downloadTelegramFile } from "../util/telegram-file";
import { ensureUser, getUserByTgId, logEvent } from "../db/repo";
import { addFoodEntry, getDayTotals } from "../db/food";
import { lookupBarcode } from "../nutrition/openfoodfacts";
import { sendFoodCard } from "./food";
import { offerCheckin } from "./checkin";
import { localDate } from "../util/time";

/** /scan — выбрать, что на следующем фото. */
export async function handleScanCommand(ctx: BotContext): Promise<void> {
  const kb = new InlineKeyboard()
    .text("🍽 Блюдо", "scan:food")
    .text("📋 Меню", "scan:menu")
    .text("🏷 Этикетка", "scan:label");
  await ctx.reply(
    "Что будешь сканировать? Выбери режим, потом пришли фото.\n(Штрих-код — просто пришли номер цифрами.)",
    { reply_markup: kb },
  );
}

/** Кнопки выбора режима скана. */
export async function handleScanCallback(ctx: BotContext): Promise<boolean> {
  const data = ctx.callbackQuery?.data;
  if (!data || !data.startsWith("scan:")) return false;
  const mode = data.slice("scan:".length);

  if (mode === "menu") ctx.session.scanMode = "menu";
  else if (mode === "label") ctx.session.scanMode = "label";
  else ctx.session.scanMode = undefined; // food

  await ctx.answerCallbackQuery();
  const hint =
    mode === "menu"
      ? "Пришли фото меню — покажу блюда с КБЖУ на выбор."
      : mode === "label"
        ? "Пришли фото этикетки — считаю КБЖУ."
        : "Пришли фото блюда — посчитаю КБЖУ.";
  await ctx.reply(hint);
  return true;
}

/** Обработка фото меню: список блюд с выбором. */
export async function handleMenuPhoto(ctx: BotContext, env: Env, fileId: string): Promise<void> {
  const thinking = await ctx.reply("Читаю меню… 📋");
  try {
    const bytes = await downloadTelegramFile(env.BOT_TOKEN, fileId);
    const ai = new WorkersAIProvider(env.AI);
    const r = await ai.recognizeMenu(bytes);

    ctx.session.menuItems = r.items.map((i) => ({
      dish: i.dish,
      kcal: Math.round(i.kcal),
      protein: Math.round(i.protein),
      fat: Math.round(i.fat),
      carb: Math.round(i.carb),
      portionG: Math.round(i.portion_grams),
    }));
    ctx.session.scanMode = undefined;

    const kb = new InlineKeyboard();
    ctx.session.menuItems.forEach((it, idx) => {
      kb.text(`${it.dish} — ${it.kcal} ккал`, `menupick:${idx}`).row();
    });
    await ctx.api.deleteMessage(thinking.chat.id, thinking.message_id).catch(() => {});
    await ctx.reply("Выбери блюдо из меню:", { reply_markup: kb });
  } catch (e) {
    console.error("recognizeMenu failed:", e);
    await ctx.api.deleteMessage(thinking.chat.id, thinking.message_id).catch(() => {});
    await ctx.reply("Не удалось прочитать меню 😕 Сделай фото чётче.");
  }
}

/** Выбор блюда из меню → запись в дневник. */
export async function handleMenuPick(ctx: BotContext, env: Env): Promise<boolean> {
  const data = ctx.callbackQuery?.data;
  if (!data || !data.startsWith("menupick:")) return false;
  const idx = parseInt(data.slice("menupick:".length), 10);
  const item = ctx.session.menuItems?.[idx];
  if (!item || !ctx.from) {
    await ctx.answerCallbackQuery("Список устарел");
    return true;
  }
  const user = await ensureUser(env.DB, ctx.from.id);
  const date = localDate(user.tz);
  await addFoodEntry(env.DB, {
    userId: user.id,
    localDate: date,
    dishName: item.dish,
    kcal: item.kcal,
    prot: item.protein,
    fat: item.fat,
    carb: item.carb,
    portionG: item.portionG || null,
    confidence: null,
    source: "menu",
  });
  await logEvent(env.DB, user.id, "confirm", { dish: item.dish, source: "menu" });
  ctx.session.menuItems = undefined;

  await ctx.answerCallbackQuery("Записал!");
  await ctx.editMessageReplyMarkup({ reply_markup: undefined }).catch(() => {});
  const totals = await getDayTotals(env.DB, user.id, date);
  const goalK = user.goal_kcal ?? 0;
  await ctx.reply(
    `✅ Записал: *${item.dish}* — ${item.kcal} ккал\nСегодня: ${Math.round(totals.kcal)}${goalK ? ` / ${goalK}` : ""} ккал`,
    { parse_mode: "Markdown" },
  );
  await offerCheckin(ctx, env);
  return true;
}

/** Обработка фото этикетки: КБЖУ → карточка подтверждения. */
export async function handleLabelPhoto(ctx: BotContext, env: Env, fileId: string): Promise<void> {
  const thinking = await ctx.reply("Читаю этикетку… 🏷");
  try {
    const bytes = await downloadTelegramFile(env.BOT_TOKEN, fileId);
    const ai = new WorkersAIProvider(env.AI);
    const r = await ai.recognizeLabel(bytes);
    ctx.session.scanMode = undefined;

    const pending: PendingFood = {
      dish: r.dish,
      portionG: Math.round(r.portion_grams) || 100,
      kcal: Math.round(r.kcal),
      protein: Math.round(r.protein),
      fat: Math.round(r.fat),
      carb: Math.round(r.carb),
      confidence: r.confidence,
      source: "label",
    };
    ctx.session.pendingFood = pending;
    await ctx.api.deleteMessage(thinking.chat.id, thinking.message_id).catch(() => {});
    await sendFoodCard(ctx, pending);
  } catch (e) {
    console.error("recognizeLabel failed:", e);
    await ctx.api.deleteMessage(thinking.chat.id, thinking.message_id).catch(() => {});
    await ctx.reply("Не удалось прочитать этикетку 😕 Сделай фото таблицы КБЖУ чётче.");
  }
}

/** Ввод штрих-кода цифрами → поиск в Open Food Facts. Возвращает true, если это был штрих-код. */
export async function handleBarcodeText(ctx: BotContext, env: Env): Promise<boolean> {
  const text = ctx.message?.text?.trim() ?? "";
  if (!/^\d{8,13}$/.test(text)) return false;
  if (!ctx.from) return true;

  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) return false; // не мешаем онбордингу/другому вводу

  const thinking = await ctx.reply("Ищу по штрих-коду… 🔎");
  const per100 = await lookupBarcode(text);
  await ctx.api.deleteMessage(thinking.chat.id, thinking.message_id).catch(() => {});

  if (!per100) {
    await ctx.reply("Не нашёл продукт по этому штрих-коду 😕 Попробуй фото этикетки: /scan");
    return true;
  }

  // Штрих-код даёт КБЖУ на 100 г — предлагаем порцию 100 г, пользователь поправит.
  const pending: PendingFood = {
    dish: per100.productName || "Продукт",
    portionG: 100,
    kcal: Math.round(per100.kcal),
    protein: Math.round(per100.protein),
    fat: Math.round(per100.fat),
    carb: Math.round(per100.carb),
    confidence: 0.85,
    source: "label",
  };
  ctx.session.pendingFood = pending;
  await sendFoodCard(ctx, pending);
  return true;
}
