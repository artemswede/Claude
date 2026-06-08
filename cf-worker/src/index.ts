/**
 * Точка входа Cloudflare Worker.
 *
 *  fetch     — приём webhook-обновлений от Telegram (POST /webhook).
 *  scheduled — Cron Trigger: фоновая рассылка новых тендеров подписчикам.
 */

import { Env } from "./config";
import { handleUpdate, TgUpdate } from "./handlers";
import { runPoll } from "./poll";
// Симуляция отключена (код сохранён в ./simulation для будущего использования).

export default {
  async fetch(
    request: Request,
    env: Env,
    ctx: ExecutionContext,
  ): Promise<Response> {
    const url = new URL(request.url);

    // Health-check / корневая страница
    if (request.method === "GET" && url.pathname === "/") {
      return new Response("TBD tender bot is running.", {
        headers: { "content-type": "text/plain; charset=utf-8" },
      });
    }

    if (request.method === "POST" && url.pathname === "/webhook") {
      // Проверка секретного токена от Telegram (если задан WEBHOOK_SECRET).
      if (env.WEBHOOK_SECRET) {
        const got = request.headers.get("X-Telegram-Bot-Api-Secret-Token");
        if (got !== env.WEBHOOK_SECRET) {
          return new Response("forbidden", { status: 403 });
        }
      }

      let update: TgUpdate;
      try {
        update = (await request.json()) as TgUpdate;
      } catch {
        return new Response("bad request", { status: 400 });
      }

      // Отвечаем Telegram сразу (200), обработку делаем в фоне.
      ctx.waitUntil(handleUpdate(update, env).catch((e) => console.error(e)));
      return new Response("ok");
    }

    return new Response("not found", { status: 404 });
  },

  async scheduled(
    _event: ScheduledEvent,
    env: Env,
    ctx: ExecutionContext,
  ): Promise<void> {
    ctx.waitUntil(runPoll(env).catch((e) => console.error("runPoll", e)));
  },
};
