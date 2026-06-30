import { webhookCallback } from "grammy";
import { createBot } from "./bot/bot";
import type { Env } from "./env";

/**
 * Точка входа Worker. Telegram шлёт апдейты на POST /webhook.
 * Long polling на Workers невозможен — работаем только через webhook.
 */
export default {
  async fetch(request: Request, env: Env, _ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    // Health-check для мониторинга и проверки деплоя.
    if (request.method === "GET" && url.pathname === "/") {
      return new Response("ЕДОНДОН bot is running", { status: 200 });
    }

    if (request.method === "POST" && url.pathname === "/webhook") {
      const bot = createBot(env);
      // webhookCallback сам проверяет секретный токен Telegram.
      const handle = webhookCallback(bot, "cloudflare-mod", {
        secretToken: env.WEBHOOK_SECRET,
      });
      return handle(request);
    }

    return new Response("Not found", { status: 404 });
  },
};
