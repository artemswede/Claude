import type { BotContext } from "./bot";
import type { Env } from "../env";
import { getUserByTgId, setDislikes } from "../db/repo";

/**
 * /avoid — управление нелюбимыми продуктами/аллергиями.
 *   /avoid грибы, кинза, молоко — задать список
 *   /avoid              — показать текущий
 *   /avoid -            — очистить
 */
export async function handleAvoid(ctx: BotContext, env: Env): Promise<void> {
  if (!ctx.from) return;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — /start 🙂");
    return;
  }

  const arg = (ctx.match as string | undefined)?.trim() ?? "";

  if (!arg) {
    const cur = user.dislikes?.trim();
    await ctx.reply(
      cur
        ? `🚫 Сейчас избегаем: *${cur}*\n\nИзменить: /avoid грибы, молоко\nОчистить: /avoid -`
        : "Нелюбимые продукты не заданы. Задать: /avoid грибы, кинза, молоко",
      { parse_mode: "Markdown" },
    );
    return;
  }

  if (arg === "-") {
    await setDislikes(env.DB, ctx.from.id, null);
    await ctx.reply("Очистил список нелюбимых продуктов. Буду советовать без ограничений.");
    return;
  }

  const cleaned = arg.slice(0, 300);
  await setDislikes(env.DB, ctx.from.id, cleaned);
  await ctx.reply(`Готово! Буду избегать в советах: *${cleaned}* 🚫`, { parse_mode: "Markdown" });
}
