import type { BotContext } from "./bot";
import type { Env } from "../env";
import { getUserByTgId, logEvent } from "../db/repo";
import { addWeight, getWeightHistory } from "../db/weight";
import { localDate } from "../util/time";

/** /weight [значение] — записать вес или показать тренд/запросить ввод. */
export async function handleWeight(ctx: BotContext, env: Env): Promise<void> {
  if (!ctx.from) return;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — /start 🙂");
    return;
  }

  // Аргумент после команды, например «/weight 72.5».
  const arg = (ctx.match as string | undefined)?.trim();
  if (arg) {
    await saveWeight(ctx, env, arg);
    return;
  }

  // Иначе — показываем тренд и просим ввести новое значение.
  await sendWeightTrend(ctx, env, user.id);
  ctx.session.awaitingWeight = true;
  await ctx.reply("Введи текущий вес в кг (например, 72.4):");
}

/** Текстовый ввод веса (после запроса). Возвращает true, если обработан. */
export async function handleWeightText(ctx: BotContext, env: Env): Promise<boolean> {
  if (!ctx.session.awaitingWeight) return false;
  await saveWeight(ctx, env, ctx.message?.text ?? "");
  return true;
}

async function saveWeight(ctx: BotContext, env: Env, raw: string): Promise<void> {
  const w = parseFloat(raw.replace(",", "."));
  if (!Number.isFinite(w) || w < 30 || w > 300) {
    await ctx.reply("Введи вес в кг от 30 до 300 (например, 72.4).");
    return;
  }
  ctx.session.awaitingWeight = false;
  if (!ctx.from) return;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user) return;

  const date = localDate(user.tz);
  await addWeight(env.DB, user.id, date, Math.round(w * 10) / 10);
  await logEvent(env.DB, user.id, "weight_log", { kg: w });

  await ctx.reply(`Записал вес: *${Math.round(w * 10) / 10} кг* ✅`, { parse_mode: "Markdown" });
  await sendWeightTrend(ctx, env, user.id);
}

async function sendWeightTrend(ctx: BotContext, env: Env, userId: number): Promise<void> {
  const history = await getWeightHistory(env.DB, userId, 8);
  if (history.length === 0) return;

  const latest = history[0].weight_kg;
  const oldest = history[history.length - 1].weight_kg;
  const delta = Math.round((latest - oldest) * 10) / 10;
  const arrow = delta < 0 ? "🔻" : delta > 0 ? "🔺" : "➡️";
  const deltaStr = delta === 0 ? "без изменений" : `${arrow} ${Math.abs(delta)} кг`;

  const lines = [`📉 *Динамика веса*`, ""];
  // Показываем от старых к свежим.
  for (const row of [...history].reverse()) {
    lines.push(`${row.local_date}: ${row.weight_kg} кг`);
  }
  if (history.length > 1) {
    lines.push("", `За период: ${deltaStr}`);
  }
  await ctx.reply(lines.join("\n"), { parse_mode: "Markdown" });
}
