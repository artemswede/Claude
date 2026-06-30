import { Bot, type Context } from "grammy";
import type { Env } from "../env";

/**
 * Тип контекста бота. По мере роста сюда добавятся flavor'ы
 * (сессии, i18n и т.п.). Пока — базовый Context.
 */
export type BotContext = Context;

/**
 * Фабрика бота. На каждый запрос Worker создаёт новый экземпляр
 * (serverless — нет общего состояния между инвокациями).
 *
 * `botInfo` передаётся явно, чтобы grammY не дёргал getMe на каждый
 * холодный старт (экономит latency и вызовы Telegram API).
 */
export function createBot(env: Env): Bot<BotContext> {
  const bot = new Bot<BotContext>(env.BOT_TOKEN);

  registerHandlers(bot, env);

  return bot;
}

function registerHandlers(bot: Bot<BotContext>, _env: Env): void {
  bot.command("start", async (ctx) => {
    await ctx.reply(
      [
        "Привет! Я ЕДОНДОН — твой ИИ-нутрициолог. 🥗",
        "",
        "Скоро я научусь считать КБЖУ по фото еды, вести дневник и подсказывать, что съесть.",
        "Пока идёт сборка — это первый рабочий каркас.",
      ].join("\n"),
    );
  });

  bot.command("ping", async (ctx) => {
    await ctx.reply("pong");
  });

  // Временный эхо-обработчик для проверки доставки апдейтов.
  bot.on("message:text", async (ctx) => {
    await ctx.reply(`Вы написали: ${ctx.message.text}`);
  });

  bot.catch((err) => {
    console.error("Bot error:", err.error);
  });
}
