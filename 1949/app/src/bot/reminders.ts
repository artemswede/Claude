import type { BotContext } from "./bot";
import type { Env } from "../env";
import { getUserByTgId, getUsersForReminder, markReminded, setReminders } from "../db/repo";
import { sendMessage } from "../util/telegram-api";
import { localDate, localHour } from "../util/time";

const EVENING_TEXT =
  "🌙 Как прошёл день? Загляни в дневник и отметь ужин и самочувствие — подскажу, что съесть. /today\n\nОтключить напоминания: /reminders";

/**
 * Проходит по пользователям и шлёт вечернее напоминание тем, у кого
 * сейчас местный вечер и сегодня ещё не напоминали. Вызывается по cron.
 */
export async function runReminders(env: Env): Promise<void> {
  const users = await getUsersForReminder(env.DB);
  for (const u of users) {
    try {
      const hour = localHour(u.tz);
      const today = localDate(u.tz);
      if (u.last_reminder_date === today) continue;
      // Вечернее окно 19:00–21:00 по местному времени.
      if (hour >= 19 && hour <= 21) {
        await sendMessage(env.BOT_TOKEN, u.tg_id, EVENING_TEXT);
        await markReminded(env.DB, u.id, today);
      }
    } catch (e) {
      console.error("reminder failed for user", u.id, e);
    }
  }
}

/** /reminders — переключить напоминания. */
export async function handleRemindersToggle(ctx: BotContext, env: Env): Promise<void> {
  if (!ctx.from) return;
  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — /start 🙂");
    return;
  }
  const newState = user.reminders_enabled ? false : true;
  await setReminders(env.DB, ctx.from.id, newState);
  await ctx.reply(
    newState
      ? "🔔 Напоминания включены. Буду вечером напоминать отметить день."
      : "🔕 Напоминания выключены. Включить обратно: /reminders",
  );
}
