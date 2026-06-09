/**
 * Обработка входящих апдейтов Telegram (webhook): команды и нажатия кнопок.
 */

import { Env, buildBaseQuery, buildProvider } from "./config";
import { TenderQuery, withFreeText } from "./filters";
import { formatResults } from "./formatting";
import { Storage } from "./storage";
import { sendMessage, sendMessages, answerCallbackQuery, InlineKeyboard } from "./telegram";

// Инлайн-кнопка под сообщениями.
const MONTH_KB: InlineKeyboard = {
  inline_keyboard: [[{ text: "📅 За последний месяц", callback_data: "month" }]],
};

const HELP_TEXT =
  "<b>Бот мониторинга тендеров на трубы большого диаметра (ТБД)</b>\n\n" +
  "Я нахожу завершённые закупки ТБД и показываю <b>цену, объём, заказчика и победителя</b> " +
  "(по 223-ФЗ поставщик часто не раскрывается).\n\n" +
  "<b>Команды:</b>\n" +
  "/search [фраза] — найти ТБД-контракты. Доп. фраза сужает поиск, " +
  "напр. <code>/search 1420</code> или <code>/search Газпром</code>.\n" +
  "/subscribe — подписаться на автоуведомления о новых контрактах.\n" +
  "/unsubscribe — отписаться.\n" +
  "/status — текущие настройки и статус подписки.\n" +
  "/help — эта справка.\n\n" +
  "Кнопка ниже покажет контракты за последний месяц.";

interface TgChat {
  id: number;
}
interface TgMessage {
  chat: TgChat;
  text?: string;
}
interface TgCallbackQuery {
  id: string;
  data?: string;
  message?: TgMessage;
}
export interface TgUpdate {
  message?: TgMessage;
  edited_message?: TgMessage;
  callback_query?: TgCallbackQuery;
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

/** Отправить список результатов; кнопку прикрепляем к первому сообщению. */
async function sendResults(
  token: string,
  chatId: number,
  messages: string[],
): Promise<void> {
  for (let i = 0; i < messages.length; i++) {
    await sendMessage(token, chatId, messages[i], i === 0 ? MONTH_KB : undefined);
  }
}

/** Поиск контрактов и отправка результата (используется /search и кнопкой). */
async function runSearchAndReply(
  env: Env,
  chatId: number,
  query: TenderQuery,
  header: string,
): Promise<void> {
  const token = env.BOT_TOKEN;
  await sendMessage(token, chatId, "🔎 Ищу ТБД-контракты…");
  // Режим "new": Seldon отдаёт контракты, ранее не передававшиеся этому аккаунту.
  // Самый надёжный способ получить реальные данные (режим "update" у этого
  // аккаунта/фильтра ничего не возвращал).
  const tenders = await buildProvider(env).search(query, { mode: "new" });
  await sendResults(token, chatId, formatResults(tenders, `${header}: ${tenders.length}`));
}

async function handleCallback(cq: TgCallbackQuery, env: Env): Promise<void> {
  const chatId = cq.message?.chat?.id;
  await answerCallbackQuery(env.BOT_TOKEN, cq.id); // убрать «часики» на кнопке
  if (chatId == null) return;

  if (cq.data === "month") {
    const query: TenderQuery = { ...buildBaseQuery(env), lookbackDays: 30 };
    await runSearchAndReply(env, chatId, query, "За последний месяц");
  }
}

export async function handleUpdate(update: TgUpdate, env: Env): Promise<void> {
  if (update.callback_query) {
    await handleCallback(update.callback_query, env);
    return;
  }

  const msg = update.message ?? update.edited_message;
  if (!msg || !msg.text) return;

  const chatId = msg.chat.id;
  const token = env.BOT_TOKEN;
  const { cmd, args } = parseCommand(msg.text);

  const storage = new Storage(env.TENDER_KV);

  switch (cmd) {
    case "/start":
    case "/help":
      await sendMessage(token, chatId, HELP_TEXT, MONTH_KB);
      return;

    case "/search": {
      const query = withFreeText(buildBaseQuery(env), args);
      await runSearchAndReply(env, chatId, query, "Найдено ТБД-контрактов");
      return;
    }

    case "/subscribe": {
      const created = await storage.subscribe(chatId);
      await sendMessage(
        token,
        chatId,
        created
          ? "✅ Вы подписаны. Буду присылать новые ТБД-контракты по мере появления."
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
      const text =
        "<b>Текущие настройки</b>\n" +
        `Источник данных: <code>${provider.name}</code>\n` +
        `ОКПД2: <code>${baseQuery.okpd2Prefixes.join(", ")}</code>\n` +
        `Ключевые слова: <code>${baseQuery.keywords.join(", ")}</code>\n` +
        `Окно поиска: ${baseQuery.lookbackDays} дн.\n\n` +
        `Подписка на уведомления: ${subscribed ? "активна ✅" : "не активна"}`;
      await sendMessage(token, chatId, text, MONTH_KB);
      return;
    }

    default:
      await sendMessage(
        token,
        chatId,
        "Не понимаю команду. Наберите /help для списка команд.",
        MONTH_KB,
      );
  }
}
