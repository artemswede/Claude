/**
 * Тонкая обёртка над Telegram Bot API (через fetch).
 */

const API = "https://api.telegram.org";

export async function callTelegram(
  token: string,
  method: string,
  payload: Record<string, unknown>,
): Promise<any> {
  const resp = await fetch(`${API}/bot${token}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok || (data as any).ok === false) {
    console.error(`Telegram ${method} failed:`, JSON.stringify(data));
  }
  return data;
}

export async function sendMessage(
  token: string,
  chatId: number,
  text: string,
): Promise<any> {
  return callTelegram(token, "sendMessage", {
    chat_id: chatId,
    text,
    parse_mode: "HTML",
    disable_web_page_preview: true,
  });
}

/** Отправить несколько сообщений по очереди (для длинных списков). */
export async function sendMessages(
  token: string,
  chatId: number,
  messages: string[],
): Promise<void> {
  for (const text of messages) {
    await sendMessage(token, chatId, text);
  }
}
