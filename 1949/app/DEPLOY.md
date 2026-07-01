# Развёртывание ЕДОНДОН — пошагово

> Делается **на твоём компьютере**. Токен бота и доступ к Cloudflare остаются у тебя.
> Ассистент задеплоить не может: его окружение не имеет доступа к Cloudflare и Telegram.

## Что понадобится
1. **Node.js 18+** — https://nodejs.org (если не установлен).
2. **Токен Telegram-бота:** в Telegram → **@BotFather** → `/newbot` → имя → username (…`bot`) → скопируй токен `123456:AA...`.
3. **Аккаунт Cloudflare** (бесплатный) — https://dash.cloudflare.com/sign-up.

## Автоматический способ (рекомендуется)
```bash
# 1. Скачай проект к себе (клонируй репозиторий), затем:
cd 1949/app

# 2. Запусти скрипт — он сделает всё сам:
bash scripts/deploy.sh
```
Скрипт по шагам:
1. установит зависимости;
2. откроет вход в Cloudflare (браузер);
3. создаст базу D1 и KV-хранилище;
4. пропишет их id в конфиг;
5. создаст таблицы (миграции);
6. попросит вставить токен бота (ввод скрыт, шифруется в Cloudflare);
7. задеплоит воркер;
8. поставит Telegram webhook.

В конце откроешь бота в Telegram и напишешь `/start`.

## Ручной способ (если скрипт не сработал)
```bash
cd 1949/app
npm install
npx wrangler login

# Создать ресурсы и ВПИСАТЬ выданные id в wrangler.toml
# (заменить REPLACE_WITH_D1_DATABASE_ID и REPLACE_WITH_KV_NAMESPACE_ID):
npx wrangler d1 create edondon
npx wrangler kv namespace create SESSIONS

# Создать таблицы:
npx wrangler d1 migrations apply edondon --remote

# Секреты:
npx wrangler secret put BOT_TOKEN        # вставить токен от BotFather
npx wrangler secret put WEBHOOK_SECRET   # любую длинную случайную строку

# Деплой:
npx wrangler deploy                      # запомнить выданный https://...workers.dev

# Webhook (подставь свои значения):
BOT_TOKEN=... WEBHOOK_SECRET=... WORKER_URL=https://edondon-bot.<...>.workers.dev \
  node scripts/set-webhook.mjs
```

## Проверка
- Напиши боту `/start` — должен запуститься онбординг.
- Пройди онбординг → пришли фото еды → проверь распознавание.
- `/today`, `/checkin`, `/advice` — остальные функции.

## Если что-то не так
- **Бот молчит:** проверь webhook — `node scripts/set-webhook.mjs` без аргументов покажет ошибку; повтори установку с правильным `WORKER_URL`.
- **Ошибка Workers AI / лимиты:** на бесплатном плане есть суточный лимит; распознавание может временно не работать.
- **Логи в реальном времени:** `npx wrangler tail`.

## Безопасность
- Токен бота и WEBHOOK_SECRET хранятся зашифрованными в Cloudflare (`wrangler secret`), не в коде.
- Файл `.dev.vars` (для локального запуска) — в `.gitignore`, не коммить.
- Если токен утёк — в @BotFather команда `/revoke`.
