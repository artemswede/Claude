# ЕДОНДОН — Telegram-бот (Cloudflare Workers)

ИИ-нутрициолог в Telegram: фото еды → КБЖУ → дневник → советы → корзина.
Стек: **TypeScript + Cloudflare Workers + grammY + Workers AI + D1/KV**.

> Документация продукта и архитектуры — в каталоге `../` (БИЗНЕС-ТРЕБОВАНИЯ, АРХИТЕКТУРА, ПЛАН-РЕАЛИЗАЦИИ).

## Статус
Этап 0 — каркас: бот поднимается на Workers, отвечает на `/start`, `/ping`, эхо.

## Структура
```
src/
  index.ts        — точка входа Worker (fetch → webhook)
  env.ts          — типы биндингов и секретов
  bot/bot.ts      — фабрика бота и хэндлеры
scripts/
  set-webhook.mjs — установка/удаление Telegram webhook
migrations/       — миграции D1 (с этапа 1)
```

## Первый запуск (локально)
```bash
npm install
cp .dev.vars.example .dev.vars   # вписать BOT_TOKEN и WEBHOOK_SECRET
npm run typecheck
npm run dev                      # wrangler dev — локальный воркер
```
Для приёма апдейтов локально нужен публичный URL (туннель) и `set-webhook`.

## Деплой
```bash
# 1. Создать ресурсы (один раз) и вписать id в wrangler.toml:
wrangler d1 create edondon
wrangler kv namespace create SESSIONS

# 2. Секреты:
wrangler secret put BOT_TOKEN
wrangler secret put WEBHOOK_SECRET

# 3. Деплой и webhook:
npm run deploy
BOT_TOKEN=... WEBHOOK_SECRET=... WORKER_URL=https://edondon-bot.<sub>.workers.dev \
  node scripts/set-webhook.mjs
```

## Что нужно от владельца
- Токен dev-бота от @BotFather.
- Аккаунт Cloudflare (для `wrangler login` и деплоя).
- Первая партнёрская ссылка/диплинк для корзины (этап 6).
