# Project 1950

Cloudflare Workers · TypeScript.

> ⚠️ **Спецификация-ведомый процесс.** Разработка не начинается, пока не пройдены шаги 1–6 из
> [`docs/WORKFLOW.md`](docs/WORKFLOW.md). Текущий этап — см. [`docs/STATUS.md`](docs/STATUS.md).
> Сейчас идёт **Шаг 1 (бизнес-требования)**; продукт ещё не определён.

## Каркас
```
1950/
├─ src/index.ts        # пустой Worker (fetch-обработчик)
├─ wrangler.jsonc      # конфигурация Cloudflare Worker
├─ tsconfig.json       # TypeScript (strict, workers-types)
├─ package.json
└─ docs/
   ├─ WORKFLOW.md                 # порядок работы (7 шагов) + правила
   ├─ STATUS.md                   # текущий статус
   └─ 01-business-requirements.md # выход Шага 1 (заполняется)
```

## Локальный запуск (после `npm install`)
```bash
npm install        # установить wrangler/typescript/workers-types
npm run dev        # локальный запуск Worker (wrangler dev)
npm run typecheck  # проверка типов
npm run deploy     # публикация в Cloudflare (нужен аккаунт + wrangler login)
```

## Правила процесса
- Переход к следующему шагу — **только по явному запросу владельца продукта**.
- Замороженные документы (Шаг 4) и тесты (Шаг 5) не редактируются без прямого разрешения.
- Архитектура — модульная, из независимых переиспользуемых блоков (анти-монолит).
