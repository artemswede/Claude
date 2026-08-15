# LinkedIn API: подключение и автопостинг

Рабочая интеграция с официальным LinkedIn API: OAuth 2.0 authorization code flow,
чтение профиля через OpenID Connect и публикация постов через versioned Posts API.

Только стандартная библиотека Python 3.9+. Устанавливать нечего.

```
linkedin/
├── linkedin_api.py          библиотека (OAuth, хранилище токена, HTTP, API)
├── cli.py                   командный интерфейс
├── test_linkedin_api.py     66 офлайн-тестов (localhost, без обращений к LinkedIn)
├── .env.example             шаблон конфигурации
└── README.md
```

---

## 1. Что можно и чего нельзя

| Задача | Продукт в Developer Portal | Как выдаётся |
|---|---|---|
| Имя, фото, email вашего аккаунта | Sign In with LinkedIn using OpenID Connect | сразу, self-serve |
| Публикация от вашего лица | Share on LinkedIn | сразу, self-serve |
| Публикация от лица страницы компании | Community Management API | по заявке, недели |
| Статистика страницы, подписчики | Community Management API | по заявке |
| Реклама, кампании | Advertising API | по заявке, нужен партнёрский статус |
| Лента, чужие профили, поиск людей, сообщения | **никак** | API не существует |

Последняя строка — принципиальная. LinkedIn закрыл доступ к ленте, чужим профилям
и поиску людей для всех, кроме единичных партнёров. Всё, что это обещает
(парсеры, «автоматизация коннектов», расширения) работает через скрейпинг, нарушает
User Agreement и заканчивается блокировкой аккаунта. Здесь этого нет и не будет.

---

## 2. Настройка приложения (один раз, ~15 минут)

### 2.1. Нужна страница компании

LinkedIn не создаст приложение без привязки к LinkedIn Page. Если своей компании
нет — создайте страницу на https://www.linkedin.com/company/setup/new/ (подойдёт
личный бренд/проект). Вы должны быть её администратором.

### 2.2. Создать приложение

1. https://www.linkedin.com/developers/apps → **Create app**.
2. App name — любое. LinkedIn Page — ваша страница. Загрузите логотип (обязателен).
3. Примите условия, создайте.

### 2.3. Подтвердить владение страницей

Вкладка **Settings** → **Verify** → кнопка генерирует ссылку. Откройте её от имени
администратора страницы и подтвердите. Без верификации продукты не подключатся.

### 2.4. Подключить продукты

Вкладка **Products**:

* **Sign In with LinkedIn using OpenID Connect** → Request access. Даёт scope
  `openid`, `profile`, `email`. Нужен, чтобы узнать ваш person URN — без него
  публиковать не от чего.
* **Share on LinkedIn** → Request access. Даёт scope `w_member_social`.

Оба self-serve: доступ появляется сразу, иногда с задержкой в несколько минут.
Проверить можно на вкладке **Auth** → OAuth 2.0 scopes: там должны быть все четыре.

### 2.5. Redirect URL и ключи

Вкладка **Auth**:

1. **Authorized redirect URLs for your app** → Add redirect URL →
   `http://localhost:8765/callback`. Строка должна совпасть побуквенно
   с `LINKEDIN_REDIRECT_URI` — лишний слэш на конце сломает обмен кода.
2. Скопируйте **Client ID** и **Client Secret** (Primary Client Secret).

### 2.6. Заполнить .env

```bash
cp linkedin/.env.example linkedin/.env
$EDITOR linkedin/.env          # вписать LINKEDIN_CLIENT_ID и LINKEDIN_CLIENT_SECRET
```

`linkedin/.env` в `.gitignore` — секрет в репозиторий не попадёт.

---

## 3. Вход

```bash
python3 linkedin/cli.py login
```

Скрипт печатает ссылку, открывает браузер, поднимает временный сервер на
127.0.0.1:8765, ловит редирект, обменивает код на токен и кладёт его в
`~/.config/linkedin-cli/token.json` с правами 0600.

На машине без браузера (сервер, SSH):

```bash
python3 linkedin/cli.py login --manual
```

Ссылку открываете где угодно, после подтверждения копируете адресную строку
целиком (`http://localhost:8765/callback?code=...&state=...`) и вставляете в терминал.

Проверка:

```bash
python3 linkedin/cli.py doctor      # конфиг, права на файл токена, scope, живой вызов API
python3 linkedin/cli.py whoami      # имя, email, person URN
```

---

## 4. Публикация

```bash
# из аргумента
python3 linkedin/cli.py post --text "Привет, LinkedIn"

# из файла или stdin
python3 linkedin/cli.py post --file post.md
cat post.md | python3 linkedin/cli.py post --file -

# посмотреть тело запроса, ничего не отправляя
python3 linkedin/cli.py post --file post.md --dry-run

# с картинкой
python3 linkedin/cli.py post --text "Итоги квартала" --image chart.png --alt "График выручки"

# только для контактов, без права репоста
python3 linkedin/cli.py post --text "..." --visibility CONNECTIONS --no-reshare

# от лица страницы компании (нужен одобренный Community Management API)
python3 linkedin/cli.py post --text "..." --author org:12345678

# без подтверждения — для cron и скриптов
python3 linkedin/cli.py post --file post.md --yes

# удалить
python3 linkedin/cli.py delete urn:li:share:7123456789
```

В интерактивном терминале перед отправкой показывается превью и запрашивается
подтверждение. В пайпе и в cron подтверждение не спрашивается.

Коды возврата: `0` успех, `1` ошибка выполнения, `2` ошибка конфигурации,
`3` нужен повторный `login`. В cron удобно ловить `3` и слать себе уведомление.

---

## 5. Как библиотека

```python
from linkedin import Config, LinkedInClient

config = Config.from_env()
client = LinkedInClient.from_store(config)

print(client.userinfo()["name"])

urn = client.create_post(
    "Текст поста (со скобками) — экранирование делается само",
    visibility="PUBLIC",
)
print(f"https://www.linkedin.com/feed/update/{urn}/")
```

Исключения: `ConfigError` (нет ключей), `AuthError` (нужен повторный вход),
`RateLimitError` (429, есть поле `retry_after`), `ApiError` (`status`, `body`),
`LinkedInError` (базовое, в том числе сетевые сбои).

---

## 6. Что важно знать заранее

**Токен живёт 60 дней, и продлить его нельзя.** `refresh_token` LinkedIn выдаёт
только приложениям, одобренным в Marketing Developer Platform. Обычному self-serve
приложению нужно раз в два месяца заново выполнять `login` руками. Код это
поддерживает: если `refresh_token` вдруг придёт — он сохранится и будет
использоваться автоматически. Поставьте напоминание; `doctor` предупреждает,
когда осталось меньше недели.

**Скобки в тексте.** В `commentary` versioned API действует формат little text:
символы `\ | { } @ [ ] ( ) < > # * _ ~` — разметка. Неэкранированная круглая
скобка приводит к тому, что пост молча обрезается по неё. `escape_little_text()`
экранирует их автоматически, вам ничего делать не нужно — но если будете писать
свои запросы, помните об этом.

**Лимит текста** — 3000 символов, проверяется до отправки. Считается длина
исходного текста, экранирующие слэши в лимит не входят.

**Публикация не повторяется автоматически.** `POST /rest/posts` неидемпотентен:
повтор после таймаута создаёт второй пост. Поэтому ретраи включены только для
безопасных запросов (GET, инициализация загрузки), а при сетевом сбое на публикации
вы получите явное сообщение «проверьте ленту, публикация могла пройти». Ретраятся
только 429/5xx на идемпотентных запросах, с учётом заголовка `Retry-After` и
экспоненциальной задержкой с джиттером.

**Дубликаты.** LinkedIn отклоняет повторную публикацию идентичного текста подряд
(HTTP 422). Это его защита, не ошибка интеграции.

**Лимиты частоты.** Порядок величин для Share on LinkedIn — около 125 публикаций
в сутки на пользователя и заметно больше на приложение; точные значения смотрите
в Developer Portal → Analytics, они меняются. При 429 CLI печатает, через сколько
секунд повторить.

**Версия API.** `LINKEDIN_API_VERSION` в формате `YYYYMM`, каждая версия живёт
около года. Если однажды получите ошибку про неподдерживаемую версию — поднимите
значение в `.env`.

**Откат на legacy.** Если `POST /rest/posts` вернёт 403/404 (у части self-serve
приложений versioned Posts API не открыт), для текстового поста от лица человека
автоматически используется старый `POST /v2/ugcPosts`. Отключается флагом
`--no-legacy-fallback`.

---

## 7. Разбор ошибок

| Что видно | Причина | Что делать |
|---|---|---|
| `invalid_redirect_uri` на экране согласия | строка в Auth не совпадает с `.env` | сверить побуквенно, включая протокол, порт и слэш |
| `invalid_grant` при обмене кода | код уже использован или истёк (10 минут), либо не тот redirect_uri | повторить `login` |
| `invalid_client` | перепутан Client Secret | скопировать заново из Auth |
| `unauthorized_scope_error` | продукт не подключён | Products → добавить нужный, дождаться активации |
| HTTP 401 на любом вызове | токен истёк или отозван | `login` заново |
| HTTP 403 на `/rest/posts` | нет `w_member_social` или продукта Share on LinkedIn | проверить Products; для страницы компании нужен Community Management API |
| HTTP 403 на `/v2/userinfo` | нет `openid`/`profile` | `login --force` после подключения OpenID Connect |
| HTTP 422 | дубликат текста | изменить текст |
| Пост опубликован обрезанным | неэкранированная скобка (если публикуете мимо этого кода) | использовать `escape_little_text()` |
| `не могу занять 127.0.0.1:8765` | порт занят | сменить порт в `.env` и в Auth, либо `login --manual` |

---

## 8. Безопасность

* `client_secret` уходит только в теле POST на `linkedin.com/oauth/v2/accessToken`,
  никогда в URL и никогда в лог.
* `state` — 32 байта из `secrets`, сверяется через `hmac.compare_digest`; при
  несовпадении вход отменяется.
* Токен пишется атомарно (временный файл + `os.replace`) с правами 0600,
  каталог 0700. `doctor` ругается, если права разъехались.
* Локальный сервер слушает только 127.0.0.1, отвечает на один нужный путь
  (запросы `/favicon.ico` от браузера не считаются ответом) и закрывается сразу.
* Токен привязан к `client_id`: если поменяете приложение, старый токен будет
  отвергнут с понятным сообщением, а не молча даст 401.
* Отозвать доступ полностью: LinkedIn → Settings → Data privacy →
  Permitted services. Локально: `python3 linkedin/cli.py logout`.

---

## 9. Тесты

```bash
python3 linkedin/test_linkedin_api.py
```

66 тестов, ни одного обращения к LinkedIn. Поднимают локальный мок и проверяют
весь путь: authorize URL → callback → обмен кода → userinfo → публикация,
включая проверку CSRF-state, откат на legacy API, поведение ретраев для
идемпотентных и неидемпотентных запросов, права на файл токена и экранирование.
