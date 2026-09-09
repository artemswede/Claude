# Публикация на Cloudflare Workers

Страница — чистая статика, бэкенд не нужен.

Состав:

```
Трубошпунт ЗТЗ.dc.html   — страница
support.js               — рантайм
image-slot.js            — слоты под фото объектов
assets/frames/f00..f47.jpg — 48 кадров ролика (1,6 МБ)
assets/process.mp4       — исходный ролик (не используется страницей, можно удалить)
```

Скролл-эффект собран на покадровой последовательности JPEG, а не на видео:
браузер не перематывает медиафайл, поэтому не нужны HTTP Range-запросы и
результат одинаков во всех браузерах и на любом хостинге.

## Промпт для Claude Code

Откройте распакованную папку в Claude Code и вставьте:

> В этой папке статический лендинг: `Трубошпунт ЗТЗ.dc.html`, `support.js`,
> `image-slot.js`, `assets/frames/*.jpg`. Разверни его на Cloudflare Workers
> через Static Assets:
> 1. Создай `public/`, перенеси туда все статические файлы, HTML переименуй в
>    `index.html` (латиницей, без пробелов), проверь относительные пути
>    `./support.js`, `./image-slot.js`, `./assets/frames/`.
> 2. `assets/process.mp4` не копируй — страница его не использует.
> 3. Создай `wrangler.jsonc`: `name: "ztz-truboshpunt"`, сегодняшний
>    `compatibility_date`, `"assets": { "directory": "./public" }`.
> 4. Создай `package.json` с devDependency `wrangler`, скрипты `dev` и `deploy`.
> 5. Запусти `npx wrangler dev`, проверь что при прокрутке первого экрана
>    меняются кадры, затем `npx wrangler deploy`.
> `wrangler login` я выполню сам, когда попросишь.

## После деплоя

- Кадры отдаются как обычные JPEG, кэш Cloudflare их подхватит автоматически.
- Хотите плавнее — нарежьте больше кадров (сейчас 48 на 10 секунд).
  Скажите мне, пересоберу с 72 или 96 кадрами.
- Свой домен: Workers → воркер → Settings → Domains & Routes → Add custom domain.
