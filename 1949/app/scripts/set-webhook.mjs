/**
 * Устанавливает Telegram webhook на URL задеплоенного воркера.
 *
 * Использование:
 *   BOT_TOKEN=xxx WEBHOOK_SECRET=yyy WORKER_URL=https://edondon-bot.<sub>.workers.dev \
 *     node scripts/set-webhook.mjs
 *
 * Удалить webhook:  node scripts/set-webhook.mjs --delete
 */

const token = process.env.BOT_TOKEN;
const secret = process.env.WEBHOOK_SECRET;
const workerUrl = process.env.WORKER_URL;
const isDelete = process.argv.includes("--delete");

if (!token) {
  console.error("BOT_TOKEN не задан");
  process.exit(1);
}

const api = (method) => `https://api.telegram.org/bot${token}/${method}`;

async function main() {
  if (isDelete) {
    const res = await fetch(api("deleteWebhook"), { method: "POST" });
    console.log(await res.json());
    return;
  }

  if (!workerUrl) {
    console.error("WORKER_URL не задан");
    process.exit(1);
  }

  const body = {
    url: `${workerUrl.replace(/\/$/, "")}/webhook`,
    secret_token: secret,
    allowed_updates: ["message", "callback_query"],
    drop_pending_updates: true,
  };

  const res = await fetch(api("setWebhook"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  console.log(await res.json());
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
