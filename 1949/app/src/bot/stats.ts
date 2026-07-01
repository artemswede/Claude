import type { BotContext } from "./bot";
import type { Env } from "../env";
import { getUserByTgId } from "../db/repo";
import { countEvents, getDailyKcal, getLoggedDates } from "../db/stats";
import { getWeightHistory } from "../db/weight";
import { localDate } from "../util/time";

/** /stats — личная статистика прямо в чате. */
export async function handleStats(ctx: BotContext, env: Env): Promise<void> {
  if (!ctx.from) return;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — /start 🙂");
    return;
  }

  const today = localDate(user.tz);
  const [dates, daily, weights, photos, baskets] = await Promise.all([
    getLoggedDates(env.DB, user.id, 60),
    getDailyKcal(env.DB, user.id, 7),
    getWeightHistory(env.DB, user.id, 30),
    countEvents(env.DB, user.id, "photo"),
    countEvents(env.DB, user.id, "basket_click"),
  ]);

  const streak = calcStreak(dates, today);
  const avgKcal =
    daily.length > 0 ? Math.round(daily.reduce((s, d) => s + d.kcal, 0) / daily.length) : 0;
  const goalK = user.goal_kcal ?? 0;
  const adherence = goalK && avgKcal ? Math.round((avgKcal / goalK) * 100) : 0;

  const lines = [
    "📈 *Твоя статистика*",
    "",
    `🔥 Активных дней подряд: *${streak}*`,
    `🗓 Всего дней с записями: *${dates.length}*`,
  ];
  if (avgKcal) {
    lines.push(`⚖️ Средние ккал (7 дней): *${avgKcal}*${goalK ? ` (цель ${goalK}, ${adherence}%)` : ""}`);
  }
  if (weights.length >= 2) {
    const delta = Math.round((weights[0].weight_kg - weights[weights.length - 1].weight_kg) * 10) / 10;
    const arrow = delta < 0 ? "🔻" : delta > 0 ? "🔺" : "➡️";
    lines.push(`📉 Вес: ${weights[0].weight_kg} кг (${arrow} ${Math.abs(delta)} кг за период)`);
  }
  lines.push("", `📸 Фото еды: *${photos}* · 🛒 Кликов по корзине: *${baskets}*`);

  await ctx.reply(lines.join("\n"), { parse_mode: "Markdown" });
}

/** Длина серии дней с записями подряд, заканчивающейся сегодня или вчера. */
function calcStreak(datesDesc: string[], today: string): number {
  if (datesDesc.length === 0) return 0;
  const set = new Set(datesDesc);
  const cursor = new Date(today + "T00:00:00Z");
  // Разрешаем старт со вчера, если сегодня ещё нет записей.
  if (!set.has(today)) cursor.setUTCDate(cursor.getUTCDate() - 1);

  let streak = 0;
  for (;;) {
    const key = cursor.toISOString().slice(0, 10);
    if (set.has(key)) {
      streak++;
      cursor.setUTCDate(cursor.getUTCDate() - 1);
    } else {
      break;
    }
  }
  return streak;
}
