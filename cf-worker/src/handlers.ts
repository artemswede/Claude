/**
 * Обработка входящих сообщений Telegram (webhook): маршрутизация команд.
 */

import { Env, buildBaseQuery, buildProvider } from "./config";
import { withFreeText } from "./filters";
import { formatResults } from "./formatting";
import { Storage } from "./storage";
import { sendMessage, sendMessages } from "./telegram";
import { simulatedMessage } from "./simulation";

const HELP_TEXT =
  "<b>Бот мониторинга тендеров на трубы большого диаметра (ТБД)</b>\n\n" +
  "Я нахожу <b>проверенные</b> (завершённые, с определённым победителем) " +
  "закупки ТБД со всех площадок и показываю <b>цену, объём и победителя</b>.\n\n" +
  "<b>Команды:</b>\n" +
  "/search [фраза] — найти свежие проверенные тендеры. Доп. фраза сужает поиск, " +
  "напр. <code>/search 1420</code> или <code>/search Газпром</code>.\n" +
  "/subscribe — подписаться на автоуведомления о новых тендерах.\n" +
  "/unsubscribe — отписаться.\n" +
  "/status — текущие настройки фильтра и статус подписки.\n" +
  "/help — эта справка.\n\n" +
  "<b>Имитация (временно, для предпросмотра формата):</b>\n" +
  "/sim — прислать пример уведомления прямо сейчас.\n" +
  "/sim_on — включить периодическую имитацию (по будням в рабочее время).\n" +
  "/sim_off — выключить имитацию.";

interface TgChat {
  id: number;
}
interface TgMessage {
  chat: TgChat;
  text?: string;
}
export interface TgUpdate {
  message?: TgMessage;
  edited_message?: TgMessage;
}

function parseCommand(text: string): { cmd: string; args: string } {
  const trimmed = text.trim();
  const spaceIdx = trimmed.search(/\s/);
  let head = spaceIdx === -1 ? trimmed : trimmed.slice(0, spaceIdx);
  const args = spaceIdx === -1 ? "" : trimmed.slice(spaceIdx + 1).trim();
  // отбрасываем упоминание бота: /search@MyBot -> /search
  head = head.split("@")[0].toLowerCase();
  return { cmd: head, args };
}

export async function handleUpdate(update: TgUpdate, env: Env): Promise<void> {
  const msg = update.message ?? update.edited_message;
  if (!msg || !msg.text) return;

  const chatId = msg.chat.id;
  const token = env.BOT_TOKEN;
  const { cmd, args } = parseCommand(msg.text);

  const storage = new Storage(env.TENDER_KV);

  switch (cmd) {
    case "/start":
    case "/help":
      await sendMessage(token, chatId, HELP_TEXT);
      return;

    case "/search": {
      const baseQuery = buildBaseQuery(env);
      const provider = buildProvider(env);
      const query = withFreeText(baseQuery, args);
      await sendMessage(token, chatId, "🔎 Ищу проверенные тендеры на ТБД…");
      // Для Seldon /search использует режим "update" (повторная выдача уже
      // найденных контрактов; тратит суточный лимит, а не основной).
      const tenders = await provider.search(query, { mode: "update" });
      const messages = formatResults(
        tenders,
        `Найдено проверенных тендеров: ${tenders.length}`,
      );
      await sendMessages(token, chatId, messages);
      return;
    }

    case "/subscribe": {
      const created = await storage.subscribe(chatId);
      await sendMessage(
        token,
        chatId,
        created
          ? "✅ Вы подписаны. Буду присылать новые проверенные тендеры на ТБД по мере появления."
          : "ℹ️ Вы уже подписаны.",
      );
      return;
    }

    case "/unsubscribe": {
      const removed = await storage.unsubscribe(chatId);
      await sendMessage(
        token,
        chatId,
        removed ? "🛑 Подписка отменена." : "ℹ️ У вас не было активной подписки.",
      );
      return;
    }

    case "/status": {
      const baseQuery = buildBaseQuery(env);
      const provider = buildProvider(env);
      const subscribed = await storage.isSubscribed(chatId);
      const priceParts: string[] = [];
      if (baseQuery.minPrice != null) priceParts.push(`от ${baseQuery.minPrice}`);
      if (baseQuery.maxPrice != null) priceParts.push(`до ${baseQuery.maxPrice}`);
      const priceStr = priceParts.length
        ? priceParts.join(" ") + " ₽"
        : "без ограничений";
      const text =
        "<b>Текущие настройки</b>\n" +
        `Источник данных: <code>${provider.name}</code>\n` +
        `ОКПД2: <code>${baseQuery.okpd2Prefixes.join(", ")}</code>\n` +
        `Ключевые слова: <code>${baseQuery.keywords.join(", ")}</code>\n` +
        `Только с победителем: ${baseQuery.onlyWithWinner ? "да" : "нет"}\n` +
        `Цена: ${priceStr}\n` +
        `Окно поиска: ${baseQuery.lookbackDays} дн.\n\n` +
        `Подписка на уведомления: ${subscribed ? "активна ✅" : "не активна"}`;
      await sendMessage(token, chatId, text);
      return;
    }

    case "/sim": {
      await sendMessage(token, chatId, simulatedMessage());
      return;
    }

    case "/sim_on": {
      const created = await storage.enableSim(chatId);
      await sendMessage(
        token,
        chatId,
        created
          ? "🧪 Имитация включена. Буду присылать примеры выигранных тендеров по будням в рабочее время (≈раз в 30–60 мин). /sim_off — выключить, /sim — пример сейчас."
          : "🧪 Имитация уже включена. /sim_off — выключить.",
      );
      return;
    }

    case "/sim_off": {
      const removed = await storage.disableSim(chatId);
      await sendMessage(
        token,
        chatId,
        removed ? "🧪 Имитация выключена." : "🧪 Имитация не была включена.",
      );
      return;
    }

    default:
      await sendMessage(
        token,
        chatId,
        "Не понимаю команду. Наберите /help для списка команд.",
      );
  }
}
