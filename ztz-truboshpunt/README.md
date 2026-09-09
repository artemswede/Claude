# Трубошпунт ЗТЗ — лендинг на Cloudflare Workers (Static Assets)

Статика, воркер-скрипта нет: Cloudflare отдаёт содержимое `public/` напрямую.

```
public/
  index.html              — страница (бывш. «Трубошпунт ЗТЗ.dc.html», переименована)
  support.js              — рантайм design-canvas
  image-slot.js           — веб-компонент <image-slot>
  assets/frames/f00..f47.jpg — 48 кадров скролл-ролика (2,0 МБ)
wrangler.jsonc            — name: ztz-truboshpunt, assets.directory: ./public
package.json              — devDependency wrangler, скрипты dev / deploy
```

`assets/process.mp4` из архива не переносился: страница его не использует,
скролл-эффект собран на последовательности JPEG. Служебные папки архива
(`uploads/`, `scraps/`, `.thumbnail`) в веб-корень тоже не попали — там
внутренние материалы, публиковать их не нужно.

## Команды

```bash
npm install
npm run dev      # wrangler dev → http://127.0.0.1:8787
npm run deploy   # wrangler deploy (нужен wrangler login или CLOUDFLARE_API_TOKEN)
```

## Заметки по эксплуатации

- `support.js` в рантайме подгружает React 18.3.1 UMD с `unpkg.com`, а шрифты
  идут с `fonts.googleapis.com`. Оба домена — внешние зависимости страницы;
  без доступа к ним лендинг не отрисуется.
- `image-slot.js` при старте запрашивает `.image-slots.state.json`; файла нет,
  в консоли будет 404. На отображение это не влияет — слоты «Фото объекта»
  показывают плейсхолдеры, пока в них не подставят фотографии.
