# Промпт: WMS Dashboard для Загорского Трубного Завода

## Задача

Собрать standalone HTML-файл — мобильный dashboard мониторинга транспорта для Загорского трубного завода (ЗТЗ), Сергиев Посад. Файл деплоится на Vercel как `wms-dashboard.html`. Дорожная сеть строится из 5290 GPS-точек. 11 грузовиков ездят по этой сети через A* pathfinding. Есть API для реальных GPS-позиций (PackAPI). Три темы: Light/Mix/Night.

---

## Структура проекта

```
/
├── wms-dashboard.html     ← Основной файл (~200KB, всё инлайн)
├── vercel.json            ← Rewrites: / → /wms-dashboard.html
└── api/
    └── agr.js             ← Vercel serverless proxy к PackAPI
```

## Технический стек

Всё собрано в **один HTML-файл**. Никаких бандлеров, npm, фреймворков — только CDN-библиотеки и инлайн-код.

### Зависимости (CDN)

```html
<script src="https://cdnjs.cloudflare.com/ajax/libs/react/18.3.1/umd/react.production.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/react-dom/18.3.1/umd/react-dom.production.min.js"></script>
```

### Шрифты (Google Fonts)

```html
<link href="https://fonts.googleapis.com/css2?family=Onest:wght@400;500;600;700&family=JetBrains+Mono:wght@500;700&display=swap" rel="stylesheet">
```

- **Onest** — основной UI шрифт
- **JetBrains Mono** — номера машин, GPS-статус, технические данные

### dc-runtime (Claude Design Component Runtime)

Это ~1900 строк JS, которые нужно инлайнить перед `<x-dc>` блоком. Получить из экспорта Claude Design (файл `support.js`). Runtime обрабатывает:
- Шаблоны `<x-dc>` с биндингами `{{ expression }}`
- Директивы `<sc-for>`, `<sc-if>`
- Компонент `class Component extends DCLogic` в `<script type="text/x-dc">`

### КРИТИЧЕСКИЙ БАГ dc-runtime

dc-runtime содержит функцию `boot()`, которая делает `fetch(location.href)` для hot-reload. При загрузке standalone HTML это:
1. Перекачивает весь файл (200KB)
2. Парсит и не находит `<x-dc>` блок правильно
3. Страница показывает "not a Design Component" на белом фоне через ~1 сек

**Фикс**: Перед `<script>` с dc-runtime добавить:
```html
<script>window.__resources = {};</script>
```
Это заставляет `boot()` пропустить повторный fetch.

---

## Дорожная сеть

### Исходные данные

5290 GPS-точек (heatmap cells) в формате `[x, y, weight]`:
```javascript
window.ROAD_NET = {
  "edges": [],    // пустой — рёбра строятся в JS
  "cells": [
    [858, 838, 9],  // x, y в SVG-координатах, weight 1-9
    [793, 251, 8],
    // ... 5290 точек
  ]
};
```

- **X range**: 0–1085, **Y range**: 0–1504
- Среднее расстояние между соседними точками: **8.6px**
- Точки формируют 2D mesh (у каждой 3-8 соседей в радиусе 12px)
- Weight 1-9 показывает интенсивность трафика

### Как получить эти данные

Данные берутся из GPS-треков PackAPI. Алгоритм:
1. Собрать историю GPS за N дней
2. Конвертировать lat/lng → SVG координаты (см. GEO конвертация ниже)
3. Разбить карту на сетку ~8px
4. Для каждой ячейки посчитать количество попаданий GPS-точек → weight

### Как рендерить дороги (ВАЖНО — уроки из ошибок)

**НЕ РАБОТАЕТ (пробовали, потратили 4+ итерации):**

1. **Отдельные точки (dots)**: `M x y h0` с `stroke-linecap:round` — выглядит как пиксельная каша, растровая решётка из точек. Даже с большим stroke-width видна структура сетки.

2. **Трассировка цепочек (chain tracing)**: обход графа через узлы степени 2 даёт ~17,000 цепочек с макс длиной 7 точек. Слишком короткие для Безье-сглаживания. Результат — россыпь коротких отрезков.

3. **RDP + Catmull-Rom → Bezier**: На таких коротких цепочках сглаживание не помогает.

**РАБОТАЕТ: Линии между соседями**

Для каждой пары соседних точек (расстояние ≤ 12px) рисовать линейный сегмент `M x1 y1 L x2 y2`. С `stroke-linecap:round` эти сегменты сливаются в непрерывную поверхность.

```javascript
// Построение дорог в build()
const RADIUS = 12, BK = 13, grid = new Map(), cellNodes = [], cellW = [];

// 1. Создать узлы и spatial index
j.cells.forEach(([x, y, w], ci) => {
  const i = nodeOf(x, y);
  P[i].n = Math.max(P[i].n, w);
  cellNodes.push(i);
  cellW.push(w);
  const bk = Math.floor(x/BK) + ',' + Math.floor(y/BK);
  if (!grid.has(bk)) grid.set(bk, []);
  grid.get(bk).push({i, x, y, ci, w});
});

// 2. Найти соседей и рисовать линии
j.cells.forEach(([x1, y1, w1], ci) => {
  const bx = Math.floor(x1/BK), by = Math.floor(y1/BK), ni = cellNodes[ci];
  for (let dx = -1; dx <= 1; dx++)
    for (let dy = -1; dy <= 1; dy++) {
      const nbs = grid.get((bx+dx)+','+(by+dy));
      if (!nbs) continue;
      for (const nb of nbs) {
        if (nb.i <= ni) continue;  // избегаем дубликатов
        const d = Math.hypot(nb.x - x1, nb.y - y1);
        if (d <= RADIUS) {
          // Граф смежности для A*
          adj[ni].push({ j: nb.i, d });
          adj[nb.i].push({ j: ni, d });
          // SVG path — линия между соседями
          const w = Math.max(w1, nb.w);
          const k = w < 4 ? 'c1' : w < 7 ? 'c2' : 'c3';
          road[k] += 'M' + x1 + ' ' + y1 + 'L' + nb.x + ' ' + nb.y;
        }
      }
    }
});
```

Это даёт ~17,700 линейных сегментов → ~350KB SVG path data. С `stroke-linecap:round` сегменты сливаются в сплошные дорожные поверхности.

### SVG рендеринг дорог

Три уровня интенсивности трафика. Сначала edge (обводка), потом fill (заливка):

```html
<!-- Edge (обводка дороги) -->
<path d="{{ world.road.c1 }}" style="fill:none;stroke:{{ c.roadEdge }};stroke-width:8;stroke-linecap:round"/>
<path d="{{ world.road.c2 }}" style="fill:none;stroke:{{ c.roadEdge }};stroke-width:10;stroke-linecap:round"/>
<path d="{{ world.road.c3 }}" style="fill:none;stroke:{{ c.roadEdge }};stroke-width:13;stroke-linecap:round"/>
<!-- Fill (тело дороги) -->
<path d="{{ world.road.c1 }}" style="fill:none;stroke:{{ c.road }};stroke-width:5;stroke-linecap:round"/>
<path d="{{ world.road.c2 }}" style="fill:none;stroke:{{ c.road }};stroke-width:7;stroke-linecap:round"/>
<path d="{{ world.road.c3 }}" style="fill:none;stroke:{{ c.road }};stroke-width:10;stroke-linecap:round"/>
```

### Связность графа

После построения графа смежности нужно соединить компоненты. Итеративно находим ближайшие пары узлов из разных компонент и добавляем ребро (до 10 итераций, порог 400px). Это гарантирует что A* может найти путь между любыми двумя точками на дороге.

---

## Симуляция грузовиков

### Флот

```javascript
const fleet = [
  ['А741ВС50', 'Volvo FH', 'semi'],
  ['К318МР50', 'MAN TGS', 'semi'],
  ['Е902ОТ50', 'Scania R450', 'semi'],
  ['Х115КН50', 'КАМАЗ-65115', 'rigid'],
  ['В560АХ50', 'КАМАЗ-5490', 'semi'],
  ['М274РС50', 'КАМАЗ-65115', 'rigid'],
  ['Т047УК50', 'Газель Next · служебная', 'car'],
  ['О681НН50', 'УАЗ Патриот · охрана', 'car'],
  ['С829ЕЕ50', 'Volvo FM', 'semi'],
  ['Н453АВ50', 'MAN TGX', 'semi'],
  ['Р196ТТ50', 'Scania R500', 'semi'],
];
```

3 типа: `semi` (тягач с прицепом), `rigid` (самосвал), `car` (служебная).

### Label

Из номера извлекается числовая часть: `А741ВС50` → `741`. Отображается в pill-badge рядом с иконкой машины.

### Автоматические остановки

При инициализации создаются 5 горячих точек из самых посещаемых узлов дорожной сети + точка выезда (самая западная точка). Грузовики циклически ездят: погрузка → разгрузка → (40% выезд / 60% новая погрузка).

### A* Pathfinding

Стандартный A* по графу смежности. Эвристика — евклидово расстояние. Путь через граф → кумулятивные расстояния → плавная интерполяция позиции.

### Движение

```
requestAnimationFrame loop:
  → step(dt) для каждого грузовика
  → forceUpdate() каждые 40ms
```

Каждый грузовик:
- **drive**: движется по path с скоростью vL (гружёный, ~8-12 км/ч) или vE (порожний, ~24-32 км/ч)
- **dwell**: стоит на точке 8-13 сек (погрузка/разгрузка), 7-12 сек (выезд), 1-4 сек (объезд)
- Направление (heading) плавно интерполируется по касательной к пути
- Скорость замедляется перед финишем (фактор `f = min(1, 0.2 + rem/45)`)

### Классификация состояния

- **Движется** (зелёный): `v >= 3 км/ч`
- **Стоит** (жёлтый): `v < 3 км/ч`, не за территорией
- **За территорией** (серый): на точке выезда

### Классификация загрузки (по скорости)

- **Груженый** (красный): скорость < `loadedBelow` (15 км/ч), стабильно > 1.5 сек
- **Порожний** (серый): скорость > `emptyAbove` (20 км/ч)

---

## API интеграция (PackAPI)

### Serverless proxy (`api/agr.js`)

```javascript
// Vercel serverless function
const PACKAPI_BASE = 'https://packapi.kgk-global.com/api/v1';
const PACKAPI_AUTH = process.env.PACKAPI_AUTH || 'Basic bS5iZWx5aEBpLXQtei5ydTowZGY0ZGJlNjIwMDIyOGJiNTUwNTgxNmFkMWE4NDk0Mg==';
const PACKAPI_GROUP = process.env.PACKAPI_GROUP || '10941';

// GET /api/agr?report=trackers → PackAPI get_trackers
```

5-секундный кэш, CORS headers.

### GPS → SVG конвертация

```javascript
const GEO = {
  latN: 56.4345,  // Северная граница
  latS: 56.4175,  // Южная граница
  lonW: 38.1375,  // Западная граница
  lonE: 38.1565,  // Восточная граница
  svgW: 900,      // Ширина SVG
  svgH: 1200      // Высота SVG
};

const toSVG = (lat, lng) => ({
  x: (lng - GEO.lonW) / (GEO.lonE - GEO.lonW) * GEO.svgW,
  y: (1 - (lat - GEO.latS) / (GEO.latN - GEO.latS)) * GEO.svgH
});
```

### Snap к дорожной сети

GPS-позиция конвертируется в SVG, затем находится ближайший узел графа в радиусе 60px. Если узел не найден — позиция за территорией.

### КРИТИЧНО: НЕ убивать симуляцию при получении API данных

Прошлая ошибка: при первом ответе API все симуляционные машины удалялись и заменялись на API-машины. API-машины не имеют симуляции движения → стоят неподвижно.

**Правильно**: Симуляция работает ВСЕГДА. API только корректирует маршрут ближайшей симуляционной машины:

```javascript
pollAPI() {
  fetch('/api/agr?report=trackers')
    .then(r => r.json())
    .then(raw => {
      const units = Array.isArray(raw) ? raw : (raw?.list || []);
      if (!units.length || !this.P?.length) return;
      units.forEach(u => {
        const s = u.state || u;
        const lat = s.lat || u.lat, lng = s.lng || u.lng;
        if (!lat || !lng) return;
        const sv = toSVG(lat, lng);
        // Отфильтровать позиции вне карты
        if (sv.x < -50 || sv.x > 950 || sv.y < -50 || sv.y > 1250) return;
        const node = snap(sv.x, sv.y);
        if (node < 0) return;
        // Найти ближайшую симуляционную машину
        let best = null, bd = 1e9;
        for (const t of this.trucks) {
          const d = Math.hypot(t.x - sv.x, t.y - sv.y);
          if (d < bd) { bd = d; best = t; }
        }
        if (!best || bd > 200) return;
        // Перенаправить машину к реальной позиции
        if (node !== best.node) {
          const path = this.astar(best.node, node);
          if (path?.length >= 2) { best.state = 'drive'; this.setPath(best, path); }
        }
      });
    })
    .catch(() => { this.apiOK = false; });
}
```

Polling каждые 10 секунд. GPS-статус показывается в хедере.

---

## Темы

3 темы: **Light** (карта+UI светлые), **Mix** (карта светлая, UI тёмный), **Night** (всё тёмное).

### Палитра карты

```javascript
// Light / Mix
const map = {
  grass: '#c3e3a4',    // Фон карты
  yard: '#efece4',     // Территория завода
  fence: '#b3ae9f',    // Ограда
  road: '#d5d3cc',     // Тело дороги
  roadEdge: '#b0ada4', // Обводка дороги
  shadow: '#2b3a20',   // Тени зданий
  tree1: '#7cc062',    // Деревья (основной)
  tree2: '#97d67b',    // Деревья (блик)
  label: '#2a2622',    // Подписи
  pipe: '#b9bec7',     // Трубопроводы
  pill: '#ffffff',     // Pill-badges
  pillText: '#0b0b0c',
};

// Night
const mapDark = {
  grass: '#0c0d0e',
  yard: '#131315',
  fence: '#3a3b40',
  road: '#2a2b31',
  roadEdge: '#1a1b20',
  shadow: '#000000',
  tree1: '#16221a',
  tree2: '#1c2c20',
  label: '#f1efe9',
  pipe: '#34363d',
  pill: '#0b0b0c',
  pillText: '#f5f5f4',
};
```

### Палитра UI

```javascript
// Light
const uiL = { uiBg: '#ffffff', card: '#f4f3ef', line: '#e8e6e0', text: '#0b0b0c', sub: '#5f5f66', chip: 'rgba(255,255,255,0.92)' };
// Dark / Mix
const uiD = { uiBg: '#0a0a0b', card: '#161618', line: '#26262b', text: '#f5f5f4', sub: '#9a9aa2', chip: 'rgba(14,14,16,0.9)' };
```

### Функциональные цвета

- Движение: `#22c55e` (зелёный)
- Стоянка: `#f5b400` (жёлтый)
- За территорией: `#8e8e93` (серый)
- Груженый / акцент: `#ff4b1f` (красно-оранжевый)
- Разгрузка: `#3b82f6` (синий)

---

## UI Layout

### Mobile-first (< 880px)

```
┌──────────────────────┐
│  Header (56px)       │  Logo · GPS · L/M/N
├──────────────────────┤
│  Mini stats bar      │  4 числа в ряд
│  Layer chips         │  Тепловая карта, Следы
│                      │
│      SVG MAP         │  Pan/zoom/pinch
│                      │
│  +/- zoom buttons    │
├──────────────────────┤
│  Bottom nav          │  Карта | Машины | Рейсы
└──────────────────────┘
```

Tabbar переключает между картой и списком/событиями.

### Desktop (>= 880px)

```
┌────────────────────────────────────────┐
│  Header                               │
├────────────────────────┬───────────────┤
│                        │  Stats grid   │
│      SVG MAP           │  Sub tabs     │
│                        │  Search       │
│      (flex: 1)         │  Truck list   │
│                        │  -or-         │
│                        │  Events feed  │
│                        │  (380px)      │
└────────────────────────┴───────────────┘
```

### Карта — SVG слои (снизу вверх)

1. Деревья (круги с тенью)
2. Территория (rect с пунктиром)
3. Тепловая карта (toggle, 3 слоя opacity)
4. Дороги (edge + fill, 3 уровня)
5. Здания (rect с тенью + крыша)
6. Трубопроводы (круги)
7. Следы машин (polyline)
8. Грузовики (SVG группы с transform)

### Грузовик (SVG)

```
[кузов]───[кабина][решётка]
```

- `semi`: кузов 22×9, кабина 9×8
- `rigid`: кузов 14×9, кабина 9×8
- `car`: кузов 14×8, кабина 5×5

Каждый грузовик повёрнут по направлению движения (heading). Scale адаптируется к зуму.

### Взаимодействие с картой

- **Pan**: pointer drag (один палец)
- **Zoom**: wheel (desktop), pinch (mobile)
- **Click на грузовик**: выделение + popup
- **Fit**: сбросить позицию (двойная авто-подгонка при resize)

---

## Vercel конфигурация

### vercel.json

```json
{
  "version": 2,
  "rewrites": [
    { "source": "/api/agr", "destination": "/api/agr" },
    { "source": "/", "destination": "/wms-dashboard.html" }
  ]
}
```

Важно: файл называется `wms-dashboard.html`, не `index.html`. Без rewrite корень вернёт 404.

---

## Полная структура HTML файла

```
<!DOCTYPE html>
<html lang="ru">
<head>
  meta charset, viewport (user-scalable=no)
  <title>ЗТЗ Транспорт</title>
  React 18.3.1 CDN (2 scripts)
  Google Fonts (Onest + JetBrains Mono)
  <style>body{margin:0;overflow:hidden}...</style>
</head>
<body>
  <script>window.__resources = {};</script>     ← КРИТИЧНО: без этого crash
  <script>
    // dc-runtime (~1900 строк, инлайн из support.js)
  </script>
  <script>
    window.ROAD_NET = {"edges":[],"cells":[[858,838,9],...]};  ← 5290 точек
  </script>
  <x-dc>
    <helmet>...</helmet>
    <!-- HTML шаблон с {{ bindings }} и <sc-for>/<sc-if> -->
  </x-dc>
  <script type="text/x-dc" data-dc-script data-props="...">
    class Component extends DCLogic {
      // Вся логика: build, pollAPI, astar, step, theme, renderVals
    }
  </script>
</body>
</html>
```

---

## Чеклист готовности

- [ ] Дороги рендерятся как сплошные поверхности (линии между соседями), не как точки
- [ ] 11 грузовиков ездят по дорогам через A* pathfinding
- [ ] Симуляция НЕ останавливается при получении API данных
- [ ] Белый экран не появляется (window.__resources fix)
- [ ] 3 темы работают (Light / Mix / Night)
- [ ] Pan/zoom на мобильном (touch events)
- [ ] Pinch-to-zoom
- [ ] Боковая панель с поиском машин
- [ ] Лента событий (погрузка/разгрузка/выезд/въезд)
- [ ] GPS-статус в хедере (зелёный/жёлтый/красный)
- [ ] Vercel rewrite / → /wms-dashboard.html
- [ ] API proxy /api/agr → PackAPI
- [ ] Подписи зданий масштабируются с зумом
- [ ] Pill-badges номеров машин кликабельны
- [ ] Popup с деталями выбранной машины
