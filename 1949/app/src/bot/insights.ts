import type { BotContext } from "./bot";
import type { Env } from "../env";
import { getUserByTgId } from "../db/repo";
import { getFoodsRecent, getStatesRecent } from "../db/stats";
import { computeEnergyInsights } from "../domain/insights";

/** /insights — что предшествует упадку энергии/сонливости. */
export async function handleInsights(ctx: BotContext, env: Env): Promise<void> {
  if (!ctx.from) return;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — /start 🙂");
    return;
  }

  const [states, foods] = await Promise.all([
    getStatesRecent(env.DB, user.id, 100),
    getFoodsRecent(env.DB, user.id, 200),
  ]);

  const r = computeEnergyInsights(states, foods);
  if (!r.enough) {
    await ctx.reply(
      "🔬 *Анализ еда × состояние*\n\nПока мало данных для выводов. Отмечай приёмы пищи и самочувствие (/checkin) несколько дней — и я найду закономерности: после каких блюд у тебя чаще упадок энергии.",
      { parse_mode: "Markdown" },
    );
    return;
  }

  const lines = [
    "🔬 *Анализ еда × состояние*",
    "",
    `На основе ${r.dips} отметок упадка энергии/сонливости.`,
    "",
    "*Чаще всего перед спадом ты ел:*",
    ...r.topDishes.map((d) => `• ${d.name} (${d.count}×)`),
  ];
  if (r.avgCarbBefore != null) {
    lines.push("", `Средние углеводы таких приёмов: *${r.avgCarbBefore} г*.`);
    if (r.avgCarbBefore > 40) {
      lines.push("Похоже на «углеводные качели» — попробуй добавлять белок и клетчатку, чтобы держать энергию ровнее.");
    }
  }
  lines.push("", "_Это наблюдения по твоим данным, не диагноз._");
  await ctx.reply(lines.join("\n"), { parse_mode: "Markdown" });
}
