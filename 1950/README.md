# Project 1950 — Q-Scope (бриф-бот квалификации квантовых задач)

Cloudflare Workers · TypeScript. Self-service анкета → детерминированный движок → вердикт (4 цвета) →
бриф для экспертов. Без реального запуска кванта, без внешних интеграций.

> Спецификация-ведомый процесс: база заморожена в [`spec-frozen/`](spec-frozen/FREEZE.md).
> Статус — [`docs/STATUS.md`](docs/STATUS.md). MVP реализован (Шаг 7).

## Структура
```
1950/
├─ public/              # SPA: лендинг → анкета → результат → бриф → лид+CSAT
│  ├─ index.html · styles.css · app.js
├─ src/
│  ├─ index.ts          # Worker + API (/api/evaluate, /api/event, /api/lead, /api/metrics)
│  ├─ types.ts
│  ├─ engine/index.ts   # движок: gates + scoring + verdict + brief (по spec-frozen/01f)
│  ├─ data/scenarios.ts # библиотека «подтип → референс» (зашита)
│  └─ storage/kv.ts     # KV: анонимные счётчики + лиды (согласие, 90 дней)
├─ data/                # scenarios.csv + verify_engine.py (вердикт 16/16)
├─ docs/                # 01..03 процесс
└─ spec-frozen/         # замороженная база v1.0 (read-only)
```

## Запуск
```bash
npm install
npm run typecheck                 # tsc --noEmit (strict) — должно быть 0 ошибок
npm run dev                       # локальный wrangler dev (KV работает локально)
```
Для публикации (нужен аккаунт Cloudflare и `npx wrangler login`):
```bash
npx wrangler kv namespace create METRICS   # вписать id в wrangler.jsonc
npx wrangler kv namespace create LEADS     # вписать id в wrangler.jsonc
npm run deploy                             # → ссылка вида https://project-1950.<...>.workers.dev
```

## Логика вердикта (кратко)
4 цвета: 🟢 «Можно начинать пилот» · 🟡 «Перспективно — вернёмся позже» · 🔵 «Есть классическое решение»
· 🔴 «Квант здесь не нужен». Полная таблица истинности — `spec-frozen/01f-engine-spec.md §6`
(проверена на `data/scenarios.csv`: 16/16).

## Что НЕ входит в MVP
Реальный квант, внешние интеграции, обработка бизнес-данных, AI-слой (→ v1.1), ветки «риски»/«прогноз».
