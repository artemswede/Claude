/**
 * Типы биндингов Cloudflare и секретов.
 * Значения приходят из wrangler.toml (биндинги) и `wrangler secret put` (секреты).
 */
export interface Env {
  // --- Секреты (wrangler secret put) ---
  /** Токен Telegram-бота от @BotFather. */
  BOT_TOKEN: string;
  /**
   * Секрет для проверки входящих webhook-запросов Telegram
   * (заголовок X-Telegram-Bot-Api-Secret-Token). Защищает endpoint от чужих вызовов.
   */
  WEBHOOK_SECRET: string;

  // --- Переменные (wrangler.toml [vars]) ---
  ENVIRONMENT: string;
  /** Название магазина-партнёра для кнопки заказа (опц.). */
  PARTNER_NAME?: string;
  /** Шаблон поисковой ссылки партнёра с плейсхолдером {q} (опц.). */
  PARTNER_SEARCH_URL?: string;

  // --- Биндинги ресурсов ---
  /** Workers AI — vision и текстовая генерация. */
  AI: Ai;
  /** D1 — основная БД. */
  DB: D1Database;
  /** KV — сессии диалога и кэш. */
  SESSIONS: KVNamespace;
}
