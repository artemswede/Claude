import type { BotContext } from "./bot";
import type { Env } from "../env";
import { WorkersAIProvider } from "../ai/workers-ai";
import { getUserByTgId } from "../db/repo";
import { getDayEntries, getDayTotals } from "../db/food";
import { getLatestMood } from "../db/state";
import { moodById } from "../domain/states";
import { GOAL_LABELS } from "../domain/goals";
import { localDate } from "../util/time";

const MAX_HISTORY = 6; // сообщений (3 пары)

/**
 * Свободный диалог с нутрициологом. Любой текст, не попавший в другие
 * сценарии, приходит сюда. Контекст: профиль, день, самочувствие + короткая история.
 */
export async function handleChat(ctx: BotContext, env: Env): Promise<void> {
  if (!ctx.from) return;
  const message = ctx.message?.text?.trim();
  if (!message) return;

  const user = await getUserByTgId(env.DB, ctx.from.id);
  if (!user?.onboarded_at) {
    await ctx.reply("Сначала настроим цели — /start 🙂");
    return;
  }

  const date = localDate(user.tz);
  const [totals, mood, entries] = await Promise.all([
    getDayTotals(env.DB, user.id, date),
    getLatestMood(env.DB, user.id, date),
    getDayEntries(env.DB, user.id, date),
  ]);

  const history = ctx.session.chat ?? [];
  const prompt = buildChatPrompt({
    goal: user.goal ? GOAL_LABELS[user.goal] : "не указана",
    target: { kcal: user.goal_kcal ?? 0, prot: user.goal_prot ?? 0, fat: user.goal_fat ?? 0, carb: user.goal_carb ?? 0 },
    consumed: totals,
    mood: mood ? (moodById(mood)?.label ?? mood) : "не отмечено",
    eaten: entries.map((e) => e.dish_name),
    history,
    message,
  });

  const thinking = await ctx.reply("Думаю… 💬");
  let reply: string;
  try {
    const ai = new WorkersAIProvider(env.AI);
    reply = (await ai.complete(prompt)).slice(0, 2000);
  } catch (e) {
    console.error("chat failed:", e);
    reply = "Извини, не смог ответить сейчас. Попробуй переформулировать 🙏";
  }
  await ctx.api.deleteMessage(thinking.chat.id, thinking.message_id).catch(() => {});

  // Обновляем короткую историю.
  const newHistory = [...history, { role: "user" as const, content: message }, { role: "assistant" as const, content: reply }];
  ctx.session.chat = newHistory.slice(-MAX_HISTORY);

  await ctx.reply(reply);
}

interface ChatCtx {
  goal: string;
  target: { kcal: number; prot: number; fat: number; carb: number };
  consumed: { kcal: number; prot: number; fat: number; carb: number };
  mood: string;
  eaten: string[];
  history: { role: string; content: string }[];
  message: string;
}

function buildChatPrompt(c: ChatCtx): string {
  const lines = [
    "Ты — дружелюбный ИИ-нутрициолог по имени ЕДОНДОН. Отвечай кратко и практично, по-русски.",
    "Не ставь медицинских диагнозов; при необходимости советуй обратиться к врачу.",
    "Опирайся на данные пользователя ниже, если вопрос про его питание.",
    "",
    `Цель: ${c.goal}`,
    `Дневные цели КБЖУ: ккал ${c.target.kcal}, Б ${c.target.prot}, Ж ${c.target.fat}, У ${c.target.carb}`,
    `Съедено сегодня: ккал ${Math.round(c.consumed.kcal)}, Б ${Math.round(c.consumed.prot)}, Ж ${Math.round(c.consumed.fat)}, У ${Math.round(c.consumed.carb)}`,
    `Самочувствие: ${c.mood}`,
    `Блюда сегодня: ${c.eaten.length ? c.eaten.join(", ") : "пока ничего"}`,
    "",
  ];
  if (c.history.length) {
    lines.push("История диалога:");
    for (const h of c.history) {
      lines.push(`${h.role === "user" ? "Пользователь" : "Нутрициолог"}: ${h.content}`);
    }
    lines.push("");
  }
  lines.push(`Вопрос пользователя: ${c.message}`, "Ответь как нутрициолог (без префикса, просто текст):");
  return lines.join("\n");
}
