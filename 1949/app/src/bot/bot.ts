import { Bot, type Context, type SessionFlavor, session } from "grammy";
import type { Env } from "../env";
import { initialSession, kvStorage, type SessionData } from "./session";
import {
  handleOnboardingCallback,
  handleOnboardingText,
  startOnboarding,
} from "./onboarding";
import {
  handleFoodCallback,
  handleFoodEditText,
  handlePhoto,
} from "./food";
import {
  handleManualText,
  handleToday,
  handleTodayCallback,
  startManualAdd,
} from "./diary";
import { handleCheckinCallback, offerCheckin } from "./checkin";
import { handleAdvice, handleAdviceMoreCallback, handleBasketCallback } from "./advice";
import { handleWeight, handleWeightText } from "./weight";
import { handleRemindersToggle } from "./reminders";
import { getUserByTgId } from "../db/repo";

/** Контекст бота с сессией (хранится в KV). */
export type BotContext = Context & SessionFlavor<SessionData>;

/**
 * Фабрика бота. На каждый запрос Worker создаёт новый экземпляр
 * (serverless — нет общего состояния между инвокациями).
 */
export function createBot(env: Env): Bot<BotContext> {
  const bot = new Bot<BotContext>(env.BOT_TOKEN);

  bot.use(
    session({
      initial: initialSession,
      // Ключ сессии — по пользователю (онбординг привязан к человеку).
      getSessionKey: (ctx) => (ctx.from ? `sess:${ctx.from.id}` : undefined),
      storage: kvStorage<SessionData>(env.SESSIONS),
    }),
  );

  registerHandlers(bot, env);
  return bot;
}

/** Список команд для синей кнопки «Меню» в Telegram. */
const BOT_COMMANDS = [
  { command: "today", description: "📊 Дневник за день" },
  { command: "advice", description: "🥗 Совет и корзина" },
  { command: "checkin", description: "😌 Отметить самочувствие" },
  { command: "add", description: "✍️ Добавить приём вручную" },
  { command: "weight", description: "⚖️ Записать вес" },
  { command: "goals", description: "🎯 Пересчитать цели" },
  { command: "reminders", description: "🔔 Напоминания вкл/выкл" },
  { command: "menu", description: "📋 Показать команды" },
];

function registerHandlers(bot: Bot<BotContext>, env: Env): void {
  bot.command("start", async (ctx) => {
    // Регистрируем список команд (синяя кнопка «Меню»). Идемпотентно.
    await ctx.api.setMyCommands(BOT_COMMANDS).catch(() => {});
    const user = ctx.from ? await getUserByTgId(env.DB, ctx.from.id) : null;
    if (user?.onboarded_at) {
      await ctx.reply(
        "С возвращением! 🥗 Пришли фото еды или нажми кнопку «Меню». Команды: /today /advice /checkin /add /goals",
      );
      return;
    }
    await startOnboarding(ctx, env);
  });

  bot.command("menu", async (ctx) => {
    await ctx.api.setMyCommands(BOT_COMMANDS).catch(() => {});
    await ctx.reply(
      [
        "📋 *Команды ЕДОНДОН:*",
        "",
        "📸 Пришли фото еды — посчитаю КБЖУ",
        "/today — дневник за день",
        "/advice — совет и корзина",
        "/checkin — отметить самочувствие",
        "/add — добавить приём вручную",
        "/goals — пересчитать цели",
        "",
        "_Список команд также доступен по кнопке «Меню» слева от поля ввода._",
      ].join("\n"),
      { parse_mode: "Markdown" },
    );
  });

  // Пересчёт целей вручную.
  bot.command("goals", async (ctx) => {
    await startOnboarding(ctx, env);
  });

  bot.command("today", async (ctx) => {
    await handleToday(ctx, env);
  });

  bot.command("add", async (ctx) => {
    await startManualAdd(ctx, env);
  });

  bot.command("checkin", async (ctx) => {
    await offerCheckin(ctx, env);
  });

  bot.command("weight", async (ctx) => {
    await handleWeight(ctx, env);
  });

  bot.command("reminders", async (ctx) => {
    await handleRemindersToggle(ctx, env);
  });

  bot.command("advice", async (ctx) => {
    await handleAdvice(ctx, env);
  });

  bot.command("ping", async (ctx) => {
    await ctx.reply("pong");
  });

  // Разовое принятие лицензии Meta для vision-модели (AiError 5016).
  // Соглашаясь, подтверждаешь, что не находишься в ЕС.
  bot.command("agreeai", async (ctx) => {
    try {
      await env.AI.run(
        "@cf/meta/llama-3.2-11b-vision-instruct" as keyof AiModels,
        { prompt: "agree" } as never,
      );
      await ctx.reply("Лицензия модели принята ✅ Теперь пришли фото еды.");
    } catch (e) {
      await ctx.reply(`Не удалось принять лицензию: ${String(e)}`);
    }
  });

  // Фото еды → распознавание.
  bot.on("message:photo", async (ctx) => {
    await handlePhoto(ctx, env);
  });

  // Inline-кнопки: онбординг → карточка еды → чек-ин → корзина.
  bot.on("callback_query:data", async (ctx, next) => {
    if (await handleOnboardingCallback(ctx, env)) return;
    if (await handleFoodCallback(ctx, env)) return;
    if (await handleCheckinCallback(ctx, env)) return;
    if (await handleBasketCallback(ctx, env)) return;
    if (await handleAdviceMoreCallback(ctx, env)) return;
    if (await handleTodayCallback(ctx, env)) return;
    await next();
  });

  // Текстовые сообщения: онбординг → правка карточки → ручной ввод → подсказка.
  bot.on("message:text", async (ctx) => {
    if (await handleOnboardingText(ctx)) return;
    if (await handleFoodEditText(ctx, env)) return;
    if (await handleManualText(ctx, env)) return;
    if (await handleWeightText(ctx, env)) return;
    await ctx.reply(
      [
        "Пришли фото еды — посчитаю КБЖУ.",
        "Команды:",
        "/today — дневник дня",
        "/add — ручной ввод",
        "/checkin — отметить самочувствие",
        "/advice — совет и корзина",
        "/goals — пересчитать цели",
      ].join("\n"),
    );
  });

  bot.catch((err) => {
    console.error("Bot error:", err.error);
  });
}
