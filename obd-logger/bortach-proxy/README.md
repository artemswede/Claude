# bortach-proxy — ИИ-чат Бортача без VPN

Магнитола → этот сервер на Vercel (вне РФ) → Gemini (или DeepSeek / OpenRouter).
Ключ ИИ хранится на сервере, на магнитоле — только адрес и токен.

## Развернуть (один раз, ~5 минут)

1. Ключ Gemini: https://aistudio.google.com/app/apikey → **Create API key** (из РФ может понадобиться VPN — только на этот шаг).
2. Vercel: https://vercel.com → **Add New → Project** → импорт репозитория `artemswede/Claude`.
   - **Root Directory**: `obd-logger/bortach-proxy`
   - Framework Preset: **Other**, остальное по умолчанию.
3. Перед **Deploy** (или потом: Settings → Environment Variables) добавить:
   - `PROXY_TOKEN` — придумайте длинный пароль (20+ символов);
   - `GEMINI_API_KEY` — ключ из шага 1;
   - необязательно `GEMINI_MODEL` — конкретная модель; без неё сервер сам берёт новейшую Gemini Flash.
4. **Deploy**. Адрес проекта — в Vercel → Domains, например `bortach-xyz.vercel.app`.
5. Проверка в браузере не нужна (без токена сервер ответит 401). В Бортаче:
   **Настройки → ИИ-чат → Свой сервер (без VPN)** → адрес и токен → «Сохранить» — Бортач сам проверит связь и модель.

Если `*.vercel.app` с магнитолы не открывается без VPN — привяжите к проекту свой домен (Vercel → Domains).

## Как устроено

- `GET /api/chat` — проверка: провайдер, модель, регион.
- `POST /api/chat` — тело как у OpenAI `chat/completions` (`messages`, `thinking`), Бортач сжимает его gzip
  (заголовок `X-Bortach-Gzip: 1`), чтобы большой контекст поездок проходил в лимит Vercel 4,5 МБ.
- Gemini вызывается через его OpenAI-совместимый адрес; глубокий режим — `reasoning_effort: high`.
- Функция живёт до 5 минут (`vercel.json`), этого хватает на долгое рассуждение.
- Другие провайдеры: `DEEPSEEK_API_KEY` или `OPENROUTER_API_KEY` (+ `OPENROUTER_MODEL`), выбор — `PROVIDER`.

Тесты: `npm test` (Node 20+, без зависимостей).
