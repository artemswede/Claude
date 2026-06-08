/**
 * Фоновый опрос (Cron Trigger): найти новые проверенные тендеры и разослать
 * подписчикам, не повторяясь.
 */

import { Env, buildBaseQuery, buildProvider } from "./config";
import { formatResults } from "./formatting";
import { Storage } from "./storage";
import { sendMessages } from "./telegram";

export async function runPoll(env: Env): Promise<void> {
  const storage = new Storage(env.TENDER_KV);
  const subscribers = await storage.listSubscribers();
  if (subscribers.length === 0) return;

  const baseQuery = buildBaseQuery(env);
  const provider = buildProvider(env);
  const tenders = await provider.search(baseQuery);
  if (tenders.length === 0) return;

  const byId = new Map(tenders.map((t) => [t.id, t]));
  const allIds = Array.from(byId.keys());

  for (const chatId of subscribers) {
    const unseen = await storage.filterUnseen(chatId, allIds);
    if (unseen.length === 0) continue;
    const fresh = unseen.map((id) => byId.get(id)!).filter(Boolean);
    const messages = formatResults(
      fresh,
      `🆕 Новые проверенные тендеры: ${fresh.length}`,
    );
    try {
      await sendMessages(env.BOT_TOKEN, chatId, messages);
      await storage.markSeen(chatId, unseen);
    } catch (err) {
      console.error(`Доставка в чат ${chatId} не удалась`, err);
    }
  }
}
