import type { Update } from "grammy/types";
import { createBot } from "./bot/bot";
import { runReminders } from "./bot/reminders";
import type { Env } from "./env";

/**
 * Точка входа Worker. Telegram шлёт апдейты на POST /webhook.
 *
 * ВАЖНО: отвечаем Telegram мгновенно (200 OK), а обработку апдейта
 * выполняем в фоне через ctx.waitUntil. Иначе долгие вызовы (Workers AI,
 * Open Food Facts) задерживают ответ, Telegram считает доставку неудачной
 * и повторяет апдейт — получается «шторм» повторов.
 */
export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/") {
      return new Response("ЕДОНДОН bot is running", { status: 200 });
    }

    if (request.method === "POST" && url.pathname === "/webhook") {
      // Проверяем секретный токен Telegram.
      if (request.headers.get("x-telegram-bot-api-secret-token") !== env.WEBHOOK_SECRET) {
        return new Response("unauthorized", { status: 401 });
      }

      let update: Update;
      try {
        update = (await request.json()) as Update;
      } catch {
        return new Response("bad request", { status: 400 });
      }

      const bot = createBot(env);
      // Вся обработка (включая init) — в фоне, Telegram получает 200 мгновенно.
      ctx.waitUntil(
        (async () => {
          await bot.init(); // нужно для handleUpdate (botInfo)
          await bot.handleUpdate(update);
        })().catch((e) => console.error("handleUpdate error:", e)),
      );
      return new Response("ok", { status: 200 });
    }

    return new Response("Not found", { status: 404 });
  },

  /** Cron-триггер: рассылка напоминаний. */
  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(runReminders(env).catch((e) => console.error("runReminders error:", e)));
  },
};
