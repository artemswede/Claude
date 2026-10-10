# Alice Gemini Skill

Навык Яндекс Алисы с Gemini AI и поиском через Serper.

## Архитектура

```
Алиса -> Яндекс Диалоги -> Vercel (этот сервер) -> Gemini
                                                  -> Serper (при необходимости)
```

## Деплой на Vercel

### 1. Установи Vercel CLI

```bash
npm i -g vercel
```

### 2. Залогинься

```bash
vercel login
```

### 3. Задеплой проект

```bash
cd alice-gemini-skill
vercel --prod
```

### 4. Добавь переменные окружения в Vercel

Зайди в Vercel Dashboard -> твой проект -> Settings -> Environment Variables:

| Key | Value | Environment |
|-----|-------|-------------|
| `GEMINI_API_KEY` | Ключ из Google AI Studio | Production |
| `SERPER_API_KEY` | Ключ из Serper.dev | Production |

После добавления переменных сделай повторный деплой:

```bash
vercel --prod
```

### 5. Настрой Яндекс Диалоги

1. Открой https://dialogs.yandex.ru/developer/
2. Создай навык -> Навык в Алисе
3. В настройках Backend выбери Webhook
4. Вставь URL: `https://твой-проект.vercel.app/api`
5. Включи "Использовать хранилище данных в навыке"
6. Заполни обязательные поля и отправь на модерацию

## Получение API-ключей

- **Gemini**: https://aistudio.google.com/app/apikey
- **Serper**: https://serper.dev/

## Возможности

- Ответы на любые вопросы через Gemini
- Автоматический поиск в интернете для актуальных вопросов
- Сохранение контекста разговора в рамках сессии
- Короткие ответы, адаптированные для голосового ассистента
