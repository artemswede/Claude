# Бот ТБД-тендеров на Cloudflare Workers (TypeScript)

Версия бота для **Cloudflare Workers**. Главное преимущество: код выполняется на
серверах Cloudflare за пределами РФ, поэтому **доступ к Telegram работает без
прокси**, и не нужно держать включённый VPS. Бесплатного тарифа Workers хватает
для бота с нагрузкой до ~100 000 запросов в день.

Функциональность та же, что и у Python-версии: поиск проверенных (завершённых,
с победителем) закупок ТБД и автоподписка с рассылкой; по каждому тендеру —
цена, объём, победитель.

## Как это устроено

- **Webhook** вместо long-polling: Telegram сам присылает обновления на адрес
  воркера (`POST /webhook`), воркер отвечает и шлёт сообщения через Bot API.
- **KV** (`TENDER_KV`) хранит подписки и список уже отправленных тендеров (дедуп).
- **Cron Trigger** (каждые 30 мин) запускает `scheduled` → фоновую рассылку.
- Источник данных — `mock` (демо без ключа) или `aggregator` (платный REST-API);
  переключается переменной `PROVIDER`.

```
cf-worker/
├── src/
│   ├── index.ts          # fetch (webhook) + scheduled (cron)
│   ├── handlers.ts       # команды /start /help /search /subscribe /unsubscribe /status
│   ├── poll.ts           # фоновая рассылка подписчикам
│   ├── config.ts         # Env + сборка провайдера и запроса
│   ├── models.ts         # тип Tender и хелперы
│   ├── filters.ts        # фильтр ТБД + «проверенные»
│   ├── formatting.ts     # рендер сообщения Telegram
│   ├── storage.ts        # KV: подписки + дедуп
│   ├── telegram.ts       # вызовы Bot API
│   └── providers/        # mock + aggregator
├── wrangler.toml         # конфиг воркера (KV, cron, vars)
├── package.json
└── tsconfig.json
```

## Деплой — по шагам (macOS)

### 1. Установить Node.js
Скачайте LTS с https://nodejs.org (или `brew install node`). Проверка: `node -v`.

### 2. Установить зависимости проекта
```bash
cd cf-worker
npm install
```

### 3. Войти в Cloudflare
```bash
npx wrangler login
```
Откроется браузер — подтвердите доступ.

### 4. Создать KV-хранилище
```bash
npx wrangler kv namespace create TENDER_KV
```
Команда выведет строку с `id = "..."`. Скопируйте этот id и вставьте его в
`wrangler.toml` вместо `REPLACE_WITH_KV_ID`.

### 5. Задать секреты
```bash
npx wrangler secret put BOT_TOKEN
# вставьте токен от @BotFather, Enter

npx wrangler secret put WEBHOOK_SECRET
# придумайте любую длинную строку (например, наберите случайные символы) — Enter
```

### 6. Опубликовать воркер
```bash
npx wrangler deploy
```
В выводе будет адрес вида `https://tbd-tender-bot.<ваш-сабдомен>.workers.dev`.
Запомните его.

### 7. Подключить webhook Telegram
Подставьте свой токен, адрес воркера и тот же `WEBHOOK_SECRET`:
```bash
curl "https://api.telegram.org/bot<ТОКЕН>/setWebhook?url=https://tbd-tender-bot.<сабдомен>.workers.dev/webhook&secret_token=<WEBHOOK_SECRET>"
```
Ответ `{"ok":true,...}` — готово.

### 8. Проверить
Откройте бота в Telegram → `/start`, `/search`, `/subscribe`. Логи воркера:
```bash
npx wrangler tail
```

## Переход на реальный агрегатор (Seldon и т.п.)
В `wrangler.toml` поставьте `PROVIDER = "aggregator"` и заполните
`AGGREGATOR_BASE_URL` / `AGGREGATOR_SEARCH_PATH` / `AGGREGATOR_AUTH_*`, затем:
```bash
npx wrangler secret put AGGREGATOR_API_KEY
npx wrangler deploy
```
Маппинг полей ответа агрегатора — в `src/providers/aggregator.ts`
(`FIELD_MAP`, `PARAM_MAP`, `RESULTS_PATH`).

## Полезное
- Изменить частоту опроса — `crons` в `wrangler.toml` (формат cron), затем `deploy`.
- Снять webhook (вернуться к разработке): `curl "https://api.telegram.org/bot<ТОКЕН>/deleteWebhook"`.
- Посмотреть текущий webhook: `curl "https://api.telegram.org/bot<ТОКЕН>/getWebhookInfo"`.
