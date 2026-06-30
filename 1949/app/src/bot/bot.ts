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

function registerHandlers(bot: Bot<BotContext>, env: Env): void {
  bot.command("start", async (ctx) => {
    const user = ctx.from ? await getUserByTgId(env.DB, ctx.from.id) : null;
    if (user?.onboarded_at) {
      await ctx.reply(
        "С возвращением! 🥗 Пришли фото еды или загляни в меню. Чтобы пересчитать цели — /goals.",
      );
      return;
    }
    await startOnboarding(ctx, env);
  });

  // Пересчёт целей вручную.
  bot.command("goals", async (ctx) => {
    await startOnboarding(ctx, env);
  });

  bot.command("ping", async (ctx) => {
    await ctx.reply("pong");
  });

  // Фото еды → распознавание.
  bot.on("message:photo", async (ctx) => {
    await handlePhoto(ctx, env);
  });

  // Inline-кнопки: сначала онбординг, затем карточка еды.
  bot.on("callback_query:data", async (ctx, next) => {
    if (await handleOnboardingCallback(ctx, env)) return;
    if (await handleFoodCallback(ctx, env)) return;
    await next();
  });

  // Текстовые сообщения: онбординг → правка карточки еды → подсказка.
  bot.on("message:text", async (ctx) => {
    if (await handleOnboardingText(ctx)) return;
    if (await handleFoodEditText(ctx)) return;
    await ctx.reply(
      "Пришли фото еды — посчитаю КБЖУ. Команды: /goals — цели, /add — ручной ввод (скоро).",
    );
  });

  bot.catch((err) => {
    console.error("Bot error:", err.error);
  });
}
