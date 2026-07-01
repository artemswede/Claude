#!/usr/bin/env bash
#
# Разворачивает ЕДОНДОН в твой аккаунт Cloudflare одной командой.
# Запускать ЛОКАЛЬНО на своей машине (не в облаке ассистента).
#
# Требования: Node.js 18+, npm. Всё остальное поставит npm.
# Токены вводятся интерактивно и хранятся зашифрованными в Cloudflare.
#
# Использование:
#   cd 1949/app
#   bash scripts/deploy.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> 1/8 Установка зависимостей"
npm install

WR="npx wrangler"

echo
echo "==> 2/8 Вход в Cloudflare (откроется браузер)"
$WR login

echo
echo "==> 3/8 Создание базы D1 'edondon'"
D1_OUT="$($WR d1 create edondon 2>&1 || true)"
echo "$D1_OUT"
# Достаём database_id из вывода (36-символьный UUID).
D1_ID="$(echo "$D1_OUT" | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -n1 || true)"
if [ -z "$D1_ID" ]; then
  echo "Не удалось автоматически получить database_id."
  echo "Если база уже существует — открой её в дашборде и скопируй ID."
  read -r -p "Вставь database_id вручную: " D1_ID
fi
echo "D1 database_id = $D1_ID"

echo
echo "==> 4/8 Создание KV namespace 'SESSIONS'"
KV_OUT="$($WR kv namespace create SESSIONS 2>&1 || true)"
echo "$KV_OUT"
KV_ID="$(echo "$KV_OUT" | grep -oE '[0-9a-f]{32}' | head -n1 || true)"
if [ -z "$KV_ID" ]; then
  read -r -p "Вставь KV namespace id вручную: " KV_ID
fi
echo "KV id = $KV_ID"

echo
echo "==> 5/8 Прописываю id ресурсов в wrangler.toml"
# Кроссплатформенный sed (Linux/macOS).
sed -i.bak "s|REPLACE_WITH_D1_DATABASE_ID|$D1_ID|g; s|REPLACE_WITH_KV_NAMESPACE_ID|$KV_ID|g" wrangler.toml
rm -f wrangler.toml.bak
echo "Готово."

echo
echo "==> 6/8 Применяю миграции БД (создаю таблицы)"
$WR d1 migrations apply edondon --remote

echo
echo "==> 7/8 Секреты (ввод скрыт, значения шифруются в Cloudflare)"
echo "Вставь токен бота от @BotFather:"
$WR secret put BOT_TOKEN
# Генерируем случайный секрет вебхука автоматически.
WEBHOOK_SECRET="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
echo "$WEBHOOK_SECRET" | $WR secret put WEBHOOK_SECRET
echo "WEBHOOK_SECRET сгенерирован и сохранён."

echo
echo "==> 8/8 Деплой"
DEPLOY_OUT="$($WR deploy 2>&1)"
echo "$DEPLOY_OUT"
WORKER_URL="$(echo "$DEPLOY_OUT" | grep -oE 'https://[a-zA-Z0-9.-]+\.workers\.dev' | head -n1 || true)"
if [ -z "$WORKER_URL" ]; then
  read -r -p "Вставь URL воркера (https://...workers.dev): " WORKER_URL
fi

echo
echo "==> Устанавливаю Telegram webhook"
# Токен читаем повторно из ввода (в секретах он уже есть, но локально нам нужен для setWebhook).
read -r -p "Ещё раз вставь токен бота (для установки webhook): " BOT_TOKEN
BOT_TOKEN="$BOT_TOKEN" WEBHOOK_SECRET="$WEBHOOK_SECRET" WORKER_URL="$WORKER_URL" \
  node scripts/set-webhook.mjs

echo
echo "======================================================"
echo " Готово! Бот развёрнут: $WORKER_URL"
echo " Открой своего бота в Telegram и напиши /start"
echo "======================================================"
