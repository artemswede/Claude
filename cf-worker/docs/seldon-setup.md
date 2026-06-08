# Подключение Seldon.API

Провайдер: `src/providers/seldon.ts`. Работает по асинхронной схеме Seldon
(login → /Contracts/New → /Contracts/Status → /Contracts/Result) и маппит
контракты в наш `Tender`.

## Что нужно сделать в личном кабинете Seldon 1.7 (один раз)

Фильтр ТБД настраивается **на стороне Seldon**, не в коде. В веб-интерфейсе
Seldon 1.7 создайте сохранённый поисковый фильтр в разделе **«Контракты»**:
- ОКПД2: `24.20` (стальные трубы);
- ключевые слова в наименовании: «труба большого диаметра», «ТБД», диаметры
  (530, 720, 820, 1020, 1220, 1420);
- при желании — ограничение по цене/региону.

Затем узнайте **id этого фильтра**: вызовите `POST /User/Filters?token=...`
(или возьмите из ответа метода) — нужен `filterId`, у которого `reportId = 4`
(раздел «Контракты»).

> Ограничение Seldon: победители/цены контрактов доступны **только по 44-ФЗ**.
> Крупные закупки ТБД магистральных труб часто идут по 223-ФЗ — для их охвата
> понадобится отдельный фильтр/раздел (уточняется у Seldon).

## Настройка воркера

В `wrangler.toml`:
```toml
[vars]
PROVIDER = "seldon"
SELDON_BASE_URL = "https://apitorgi.myseldon.com"
SELDON_FILTER_ID = "ВАШ_FILTER_ID"
```

Секреты (логин/пароль от учётной записи Seldon API):
```bash
npx wrangler secret put SELDON_LOGIN
npx wrangler secret put SELDON_PASSWORD
npx wrangler deploy
```

Токен авторизации (24 ч) бот получает сам и кэширует в KV (`seldon:token`).

## Особенности модели Seldon

- Режим `New` отдаёт только ранее **не передававшиеся** объекты и тарифицируется
  (≈8 ₽ за тендер: извещение + контракт). Поэтому Seldon-провайдер ориентирован
  на **фоновый опрос (cron)**: периодически забирает новые выигранные контракты
  и рассылает подписчикам.
- Команда `/search` тоже дергает Seldon вживую — но т.к. `New` уже мог отдать
  новые объекты в cron, повторный `/search` может вернуть пусто. На этапе теста
  с ключом решим, переключить ли `/search` на чтение кэша последних результатов.

## Поля контракта → Tender

| Tender              | Seldon (contracts)                          |
|---------------------|---------------------------------------------|
| winner / winnerInn  | `suppliers[0].name` / `.inn`                |
| finalPrice          | `contractPrice` (валюта `currency.code`)    |
| startPrice (НМЦК)   | `purchase.price`                            |
| volume / unit       | Σ `productList[].quantity` / `unit.name`    |
| okpd2               | `productList[0].classifier.okpd2.code`      |
| title               | `purchase.subject` / `contractSubject`      |
| registryNumber      | `regNum`                                    |
| url                 | `href`                                      |
| resultDate          | `signDate` (или `publishDate`)              |
| law                 | 44-ФЗ                                       |
