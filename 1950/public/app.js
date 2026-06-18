"use strict";
// Project 1950 — фронтенд MVP. Один вопрос за раз, ветки A/B/E, 4-цветный вердикт.
const app = document.getElementById("app");
const S = { answers: {}, qi: 0, start: 0, result: null };

const api = (url, body) =>
  fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) })
    .then((r) => r.json())
    .catch(() => ({}));

const VCOLOR = { poc_now: "green", watchlist: "amber", classical: "blue", discard: "red" };

// ---------- иконки (inline SVG) ----------
const SW = `fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"`;
const ICON = {
  atom: `<svg viewBox="0 0 24 24" ${SW}><circle cx="12" cy="12" r="2"/><ellipse cx="12" cy="12" rx="10" ry="4.3"/><ellipse cx="12" cy="12" rx="10" ry="4.3" transform="rotate(60 12 12)"/><ellipse cx="12" cy="12" rx="10" ry="4.3" transform="rotate(120 12 12)"/></svg>`,
  bolt: `<svg viewBox="0 0 24 24" ${SW}><path d="M13 2L4 14h7l-1 8 9-12h-7z"/></svg>`,
  dot: `<svg viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="12" r="4"/></svg>`,
  check: `<svg viewBox="0 0 24 24" ${SW}><circle cx="12" cy="12" r="9"/><path d="M8 12l3 3 5-6"/></svg>`,
  clock: `<svg viewBox="0 0 24 24" ${SW}><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>`,
  cog: `<svg viewBox="0 0 24 24" ${SW}><circle cx="12" cy="12" r="3.2"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"/></svg>`,
  x: `<svg viewBox="0 0 24 24" ${SW}><circle cx="12" cy="12" r="9"/><path d="M9 9l6 6M15 9l-6 6"/></svg>`,
  doc: `<svg viewBox="0 0 24 24" ${SW}><path d="M7 3h7l4 4v14H7z"/><path d="M14 3v4h4M9 12h7M9 16h6"/></svg>`,
  grid: `<svg viewBox="0 0 24 24" ${SW}><rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/></svg>`,
  gauge: `<svg viewBox="0 0 24 24" ${SW}><path d="M4 16a8 8 0 0 1 16 0"/><path d="M12 16l4-3"/></svg>`,
  book: `<svg viewBox="0 0 24 24" ${SW}><path d="M5 5a2 2 0 0 1 2-2h12v16H7a2 2 0 0 0-2 2z"/><path d="M5 5v14"/></svg>`,
  arrow: `<svg viewBox="0 0 24 24" ${SW}><path d="M4 12h14M13 6l6 6-6 6"/></svg>`,
};
const icon = (n) => `<span class="icon">${ICON[n] || ""}</span>`;
const caseCard = (name, task, effect, mat) =>
  `<div class="case">${icon("dot")}<div><b>${name}</b> · ${task}<div class="cmuted">${effect}</div></div><span class="tag">${mat}</span></div>`;

const VERDICT_INFO = {
  poc_now: { ic: "check", sub: "Задача похожа на то, что уже запускают пилотами.", next: "Передайте бриф экспертам — поможем начать пилот." },
  watchlist: { ic: "clock", sub: "Потенциал есть, но пока рано — лучше гибридный подход и вернуться позже.", next: "Сохраните бриф; подскажем, что докрутить для переоценки." },
  classical: { ic: "cog", sub: "Квант не нужен — задача эффективно решается обычными методами. И с этим мы тоже поможем.", next: "Передайте бриф — предложим классическое/гибридное решение." },
  discard: { ic: "x", sub: "Для этой задачи квантовые технологии не дают смысла.", next: "Если задача вырастет или изменится — возвращайтесь." },
};

// ---------- определения вопросов ----------
const IND = ["Логистика/транспорт", "Производство", "Энергетика", "Материалы/химия", "Финансы", "ИТ/другое"];
const ROLE = ["Владелец/руководитель", "Операции", "R&D", "ИТ/аналитик"];

const Q = {
  industry: { key: "industry", title: "Ваша отрасль", type: "select", options: IND.map((v) => ({ v, label: v })) },
  role: { key: "role", title: "Ваша роль", type: "select", options: ROLE.map((v) => ({ v, label: v })) },
  task_type: {
    key: "task_type", title: "Что вы хотите получить?", type: "cards",
    options: [
      { v: "A", label: "Найти лучший вариант из множества", note: "расписания, маршруты, загрузка, распределение, состав" },
      { v: "B", label: "Рассчитать свойства материала или вещества", note: "материал, сплав, покрытие, химия, катализатор" },
      { v: "C", label: "Оценить риск и перебрать сценарии", note: "в разработке", disabled: true },
      { v: "D", label: "Спрогнозировать по данным", note: "в разработке", disabled: true },
      { v: "E", label: "Не уверен — помогите выбрать", note: "" },
    ],
  },
  e1: {
    key: "e1", title: "Что ближе к вашей задаче?", type: "cards",
    options: [
      { v: "A", label: "Выбрать лучший вариант из множества", note: "объекты и правила выбора" },
      { v: "B", label: "Понять свойства материала/вещества", note: "прочность, состав, химия" },
    ],
  },
  e3: { key: "e3", title: "Опишите задачу своими словами (необязательно)", type: "text", optional: true },
  // ветка A
  a1: { key: "a1", title: "Что именно оптимизируете?", type: "select", options: [
    { v: "schedule", label: "Расписание/смены" }, { v: "routes", label: "Маршруты/логистика" },
    { v: "equipment_load", label: "Загрузка оборудования" }, { v: "resource_alloc", label: "Распределение ресурсов" },
    { v: "recipe", label: "Рецептура/состав" }, { v: "production_plan", label: "План производства" } ] },
  a2: { key: "a2", title: "Сколько объектов нужно учесть одновременно?", hint: "примерное число", type: "number", dunno: true },
  a3: { key: "a3", title: "Есть ли жёсткие ограничения «нельзя нарушать»?", type: "select", dunno: true,
    options: [ { v: "hard", label: "Да, есть жёсткие" }, { v: "soft", label: "Только пожелания" }, { v: "none", label: "Почти нет" } ] },
  a4: { key: "a4", title: "Как решаете сейчас и хватает ли?", type: "select", dunno: true,
    options: [ { v: "enough", label: "Способ есть, всё устраивает" }, { v: "partial", label: "Решаем, но частично не хватает" }, { v: "not", label: "Не хватает / не получается" } ] },
  a5: { key: "a5", title: "Когда задача растёт — время резко увеличивается?", type: "select", dunno: true,
    options: [ { v: "sharp", label: "Да, резко" }, { v: "slow", label: "Понемногу" }, { v: "no", label: "Нет" } ] },
  a6: { key: "a6", title: "Что считаете лучшим результатом?", type: "select", dunno: true,
    options: [ { v: "min", label: "Минимум затрат/времени" }, { v: "max", label: "Максимум выхода/прибыли" } ] },
  // ветка B
  b1: { key: "b1", title: "Что моделируете?", type: "select", options: [
    { v: "new_material", label: "Новый материал/сплав" }, { v: "additive", label: "Добавка" },
    { v: "corrosion", label: "Стойкость/коррозия" }, { v: "catalyst", label: "Катализатор" },
    { v: "coating", label: "Покрытие" }, { v: "battery", label: "Батарея" } ] },
  b2: { key: "b2", title: "Обычных методов расчёта хватает по точности/масштабу?", type: "select", dunno: true,
    options: [ { v: "enough", label: "Да, хватает" }, { v: "not_enough", label: "Нет, не хватает" } ] },
  b3: { key: "b3", title: "Размер системы", type: "select", dunno: true,
    options: [ { v: "small", label: "Маленькая" }, { v: "medium", label: "Средняя" }, { v: "large", label: "Большая" } ] },
  b4: { key: "b4", title: "Цель расчёта понятна?", type: "select", dunno: true,
    options: [ { v: "clear", label: "Да, понятна" }, { v: "unclear", label: "Пока размыта" } ] },
  // хвост
  t1: { key: "t1", title: "Сколько данных нужно «загрузить» на вход задачи?", type: "select", dunno: true,
    options: [ { v: "small", label: "Немного параметров" }, { v: "medium", label: "Таблицы среднего размера" }, { v: "huge", label: "Очень большие массивы" } ] },
  t2: { key: "t2", title: "Как часто решаете задачу?", type: "select", dunno: true,
    options: [ { v: "once", label: "Разово" }, { v: "periodic", label: "Периодически" }, { v: "daily", label: "Ежедневно" }, { v: "constant", label: "Постоянно" } ] },
  t3: { key: "t3", title: "Сколько задача стоит сейчас в год, ₽?", hint: "оценка, можно «не знаю»", type: "number", dunno: true },
  t4: { key: "t4", title: "Готовность: данные / ИТ / команда / бюджет", type: "select", dunno: true,
    options: [ { v: "high", label: "Высокая" }, { v: "medium", label: "Средняя" }, { v: "low", label: "Низкая" } ] },
  t5: { key: "t5", title: "Нужен идеально точный ответ — или достаточно быстрого, близкого к лучшему?", type: "select", dunno: true,
    options: [ { v: "yes", label: "Достаточно близкого" }, { v: "depends", label: "Зависит" }, { v: "no", label: "Нужен только идеальный" } ] },
};

const A_KEYS = ["a1", "a2", "a3", "a4", "a5", "a6"];
const B_KEYS = ["b1", "b2", "b3", "b4"];
const TAIL = ["t1", "t2", "t3", "t4", "t5"];

function branchOf(a) {
  if (a.task_type === "B") return "B";
  if (a.task_type === "E") return a.e1 === "B" ? "B" : a.e1 === "A" ? "A" : null;
  if (a.task_type === "A") return "A";
  return null;
}
function computeQueue(a) {
  const q = ["industry", "role", "task_type"];
  if (a.task_type === "E") { q.push("e1"); if (a.e1) q.push("e3"); }
  const b = branchOf(a);
  if (b) q.push(...(b === "A" ? A_KEYS : B_KEYS), ...TAIL);
  return q;
}

// ---------- экраны ----------
// ---------- сайт-обвязка (главная + модули из ТЗ) ----------
const MODULES = [
  { id: "nav", ic: "search", title: "Бизнес-навигатор", status: "live",
    desc: "Пошаговая анкета с ветвлением по отрасли — находит «взрыв размерности» там, где классика буксует." },
  { id: "lib", ic: "grid", title: "Библиотека квантового преимущества", status: "soon",
    desc: "100+ индустриальных кейсов с предзаполнением анкеты по «похожей задаче»." },
  { id: "score", ic: "gauge", title: "Квалификатор потенциала", status: "partial",
    desc: "Авто-скоринг и матрица (высокий / средний / низкий). УГТ 1–9 — в разработке." },
  { id: "passport", ic: "doc", title: "Генератор паспорта задачи", status: "partial",
    desc: "Бриф для экспертов. QUBO/Изинг, оценка кубитов и типа оборудования — в разработке." },
  { id: "edu", ic: "book", title: "Образовательные подсказки", status: "partial",
    desc: "Глоссарий и развенчание мифов. Контекстные тултипы в анкете — в разработке." },
  { id: "route", ic: "arrow", title: "Маршрутизация к экспертам", status: "partial",
    desc: "Передача лида с брифом. Прикрепление датасетов и интеграция с CRM — в разработке." },
];
const STATUS_BADGE = {
  live: `<span class="badge-live">работает</span>`,
  partial: `<span class="badge-part">частично</span>`,
  soon: `<span class="badge-soon">в разработке</span>`,
};
const PARTIAL = {
  score: { title: "Квалификатор потенциала", live: "Авто-скоринг и вердикт (4 категории) работают в составе «Бизнес-навигатора».",
    dev: ["Уровень готовности технологии (УГТ/TRL) 1–9", "Матрица: универсальный QPU / квантовый отжигатель / гибрид S-Quantum", "Явный класс «Big Data → нерелевантность» (квантовый I/O)"] },
  passport: { title: "Генератор паспорта задачи", live: "Бриф формируется (скачать .md / печать в PDF).",
    dev: ["Формализация в QUBO / модель Изинга", "Оценка числа физических и логических кубитов", "Рекомендация оборудования: сверхпроводники / ионы / нейтральные атомы / фотоника", "Экспорт в брендированный PDF"] },
  route: { title: "Маршрутизация к экспертам", live: "Передача лида с согласием работает.",
    dev: ["Прикрепление обезличенных датасетов", "Интеграция с CRM Проектного офиса", "Прогон на эмуляторах с моделями шумов NISQ"] },
};

function step(n, t, d) {
  return `<div class="step"><span class="sn">${n}</span><div><b>${t}</b><div class="cmuted">${d}</div></div></div>`;
}
const TASK_CHIPS = ["Маршрутизация транспорта", "Производственное планирование", "Составление расписаний", "Логистика и склады", "Управление портфелем", "Распределение ресурсов", "Цепочки поставок", "Свойства материалов"];
const CASE_ROWS = [["Логистика", "Маршрутизация доставки"], ["Производство", "Планирование смен и загрузки"], ["Финансы", "Оптимизация портфеля"], ["Энергетика", "Балансировка нагрузки"], ["Материалы", "Подбор состава и свойств"], ["Ритейл", "Расписания персонала"]];

function home() {
  app.innerHTML = `
    <div class="hero-ic">${icon("atom")}</div>
    <div class="kicker">Для бизнеса и индустриальных команд</div>
    <h1>Проверим, подходит ли ваша задача для квантовых вычислений</h1>
    <p class="muted">За 10–15 минут оценим потенциал задачи, покажем возможные преимущества и подскажем следующий шаг. На языке бизнеса, без физики.</p>
    <button class="btn big" id="start">${icon("bolt")} Начать оценку</button>

    <h2 class="sec">Для каких задач</h2>
    <div class="chips">${TASK_CHIPS.map((c) => `<span class="chip">${c}</span>`).join("")}</div>

    <h2 class="sec">Как это работает</h2>
    <div class="steps">
      ${step(1, "Опишите задачу", "простыми словами, по шагам")}
      ${step(2, "Получите оценку", "за пару минут, прозрачно")}
      ${step(3, "Узнайте потенциал", "есть ли смысл в квантовом подходе")}
      ${step(4, "Рекомендации и эксперты", "что делать дальше")}
    </div>

    <h2 class="sec">Что вы получите</h2>
    <ul class="get">
      <li>Оценку применимости: классика / quantum-inspired / квантовый компьютер</li>
      <li>Потенциальные зоны ускорения расчётов</li>
      <li>Похожий индустриальный кейс с ориентиром эффекта</li>
      <li>Структурированное описание задачи — можно скачать</li>
      <li>При необходимости — связь с экспертами</li>
    </ul>

    <h2 class="sec">Примеры задач</h2>
    <table class="ctab"><tbody>${CASE_ROWS.map((r) => `<tr><td>${r[0]}</td><td>${r[1]}</td></tr>`).join("")}</tbody></table>

    <div class="row sec">
      <button class="btn ghost" id="kb">База знаний</button>
      <button class="btn ghost" id="about">О проекте</button>
    </div>
    <p class="muted" style="font-size:13px">Сервис предварительной оценки прикладных задач. Оценка ориентировочная, не гарантия эффекта.</p>`;
  document.getElementById("start").onclick = landing;
  document.getElementById("kb").onclick = knowledge;
  document.getElementById("about").onclick = about;
}
function knowledge() {
  app.innerHTML = `
    <button class="link" id="back">← На главную</button>
    <div class="kicker">База знаний</div>
    <h2>Кейсы, термины и мифы</h2>
    <h2 class="sec" style="font-size:18px">Примеры кейсов</h2>
    <div class="cases">
      ${caseCard("Логистика", "Маршрутизация сетей", "десятки тысяч узлов", "есть в проде")}
      ${caseCard("Производство", "Планирование смен", "−80% времени (по аналогии)", "есть в проде")}
      ${caseCard("Финансы", "Оптимизация портфеля", "антифрод, деривативы", "пилоты")}
      ${caseCard("Материалы", "Молекулы и катализаторы", "новые материалы и батареи", "исследования")}
    </div>
    <p class="muted">Полная библиотека (100+ кейсов) с предзаполнением задачи — <b>скоро</b>.</p>
    <h2 class="sec" style="font-size:18px">Коротко о квантовых вычислениях</h2>
    <div class="glo"><b>Миф:</b> «квантовый компьютер перебирает все варианты сразу».<br/><b>Как есть:</b> он усиливает правильные ответы и гасит неверные за счёт интерференции — это не перебор.</div>
    <div class="glo"><b>Сегодня:</b> квантовые устройства шумят, поэтому работают в связке с обычными компьютерами — как ускоритель.</div>
    <div class="glo"><b>Где польза:</b> когда вариантов так много, что обычные методы становятся слишком медленными или дорогими.</div>
    <div class="glo"><b>Где пользы нет:</b> простой анализ очень больших объёмов данных — это лучше делает классика.</div>`;
  document.getElementById("back").onclick = home;
}
function about() {
  app.innerHTML = `
    <button class="link" id="back">← На главную</button>
    <div class="kicker">О проекте</div>
    <h2>Назначение и цели</h2>
    <p>Сервис первично структурирует бизнес-проблемы корпоративных клиентов, переводит их в математические модели (QUBO, модель Изинга) и оценивает целесообразность квантовых / quantum-inspired подходов на базе мощностей консорциума НКЛ.</p>
    <p class="muted">Цели:</p>
    <ul>
      <li>Снизить барьер входа бизнеса в квантовые технологии.</li>
      <li>Экономить ресурс экспертов Проектного офиса за счёт пре-скоринга и отсева хайпа.</li>
      <li>Формировать воронку пилотных проектов по Дорожной карте «Квантовые вычисления».</li>
    </ul>
    <div class="plate">Демо-MVP. Часть модулей — заглушки, помечены «в разработке».</div>`;
  document.getElementById("back").onclick = home;
}

function landing() {
  app.innerHTML = `
    <button class="link" id="tohome">← На главную</button>
    <div class="hero-ic">${icon("atom")}</div>
    <div class="kicker">Квантовые технологии · оценка задачи</div>
    <h1>Опишите бизнес-задачу — и поймите, перспективна ли она для квантовых вычислений</h1>
    <p class="muted">За пару минут, на языке бизнеса, без физики. Получите честный вердикт и бриф для экспертов.</p>
    <div class="plate">Не обещаем эффект. Можем честно сказать «вам достаточно обычных решений». С задачей не бросаем — это вход в нашу воронку проектов.</div>
    <div class="cases">
      ${caseCard("Save-On-Foods", "Расписания смен", "−80% времени планирования", "production")}
      ${caseCard("NTT DOCOMO", "Мобильная сеть", "−15% нагрузки", "production")}
      ${caseCard("Ford Otosan", "План производства", "−80% времени", "production")}
    </div>
    <button class="btn big" id="go">${icon("bolt")} Проверить задачу</button>`;
  document.getElementById("go").onclick = onboarding;
  document.getElementById("tohome").onclick = home;
}
function onboarding() {
  api("/api/event", { type: "start" });
  app.innerHTML = `
    <div class="kicker">Перед стартом</div>
    <h2>Что важно знать</h2>
    <ul class="muted">
      <li>Реальные успехи кванта сегодня — в основном пилоты и исследования, не гарантия эффекта.</li>
      <li>Мы можем сказать и «вам достаточно обычных методов».</li>
      <li>Займёт ~10 коротких вопросов. «Не знаю» — это нормально.</li>
    </ul>
    <button class="btn big" id="go">Начать</button>`;
  document.getElementById("go").onclick = () => { S.answers = {}; S.qi = 0; S.start = Date.now(); ask(); };
}

function ask() {
  const queue = computeQueue(S.answers);
  if (S.qi >= queue.length) return submit();
  const q = Q[queue[S.qi]];
  const pct = Math.round((S.qi / queue.length) * 100);
  let body = "";
  if (q.type === "select" || q.type === "cards") {
    body = `<div class="opts">${q.options
      .map((o) => `<button class="opt" data-v="${o.v}" ${o.disabled ? "disabled" : ""}>${o.label}${o.note ? `<span class="note">${o.note}</span>` : ""}${o.disabled ? `<span class="tag">в разработке</span>` : ""}</button>`)
      .join("")}</div>`;
  } else if (q.type === "number") {
    body = `<input type="number" id="inp" placeholder="${q.hint || "число"}" /><button class="btn" id="next">Далее</button>`;
  } else if (q.type === "text") {
    body = `<input type="text" id="inp" placeholder="одним предложением" /><button class="btn" id="next">Далее</button>`;
  }
  app.innerHTML = `
    <div class="bar"><i style="width:${pct}%"></i></div>
    <div class="kicker">Вопрос ${S.qi + 1} из ${queue.length}</div>
    <h2>${q.title}</h2>${q.hint ? `<p class="muted">${q.hint}</p>` : ""}
    ${body}
    <div class="row" style="margin-top:14px">
      ${q.dunno ? `<button class="btn dunno" id="dunno">Не знаю</button>` : ""}
      ${q.optional ? `<button class="btn dunno" id="skip">Пропустить</button>` : ""}
    </div>`;
  const set = (v) => { S.answers[q.key] = v; S.qi++; ask(); };
  app.querySelectorAll(".opt").forEach((b) => { if (!b.disabled) b.onclick = () => set(b.dataset.v); });
  const nx = document.getElementById("next");
  if (nx) nx.onclick = () => { const el = document.getElementById("inp"); set(el.value === "" ? "dunno" : el.value); };
  const dn = document.getElementById("dunno"); if (dn) dn.onclick = () => set("dunno");
  const sk = document.getElementById("skip"); if (sk) sk.onclick = () => set("");
}

async function submit() {
  app.innerHTML = `<h2>Считаем…</h2>`;
  const res = await api("/api/evaluate", S.answers);
  S.result = res;
  api("/api/event", { type: "complete", verdict: res.verdict, archetype: res.archetype, industry: S.answers.industry, timeMs: Date.now() - S.start });
  result();
}

function result() {
  const r = S.result;
  const baseline = r.gates.g1 === "pass"
    ? "Обычные методы здесь упираются — поэтому есть смысл смотреть в сторону кванта."
    : "Обычные методы с такой задачей справляются.";
  const arche = r.archetype === "optimization" ? "поиск лучшего варианта" : "расчёт свойств материалов";
  const info = VERDICT_INFO[r.verdict];
  app.innerHTML = `
    <div class="kicker">Результат</div>
    <div class="badge ${VCOLOR[r.verdict]}">${icon(info.ic)} ${r.verdictLabel}</div>
    <p class="vsub">${info.sub}</p>
    <p><b>Ваша задача — это ${arche}.</b> ${baseline}</p>
    <div class="plate"><b>Похоже на кейс:</b> ${r.scenario.reference} (${r.scenario.maturity}).<br/>
      Ориентир эффекта: ${r.scenario.effect}; горизонт: ${r.scenario.horizon}.<br/>
      <span class="muted">Это оценка по аналогии, не гарантия эффекта.</span></div>
    ${r.confidence < 0.6 ? `<p class="muted">Часть ответов — «не знаю», поэтому вердикт предварительный.</p>` : ""}
    <p class="next">${info.next}</p>
    <button class="btn big" id="brief">${icon("doc")} Получить бриф</button>`;
  document.getElementById("brief").onclick = brief;
}

function brief() {
  const r = S.result;
  app.innerHTML = `
    <div class="kicker">Бриф для экспертов</div>
    <h2>Готовый документ по вашей задаче</h2>
    <pre class="brief">${r.brief.replace(/[<>&]/g, (c) => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;" }[c]))}</pre>
    <div class="row">
      <button class="btn" id="dl">Скачать .md</button>
      <button class="btn ghost" id="pdf">Печать / сохранить в PDF</button>
      <button class="btn ghost" id="exp">Передать экспертам</button>
    </div>`;
  document.getElementById("dl").onclick = () => {
    const blob = new Blob([r.brief], { type: "text/markdown" });
    const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = "brief-1950.md"; a.click();
  };
  document.getElementById("pdf").onclick = () => window.print();
  document.getElementById("exp").onclick = lead;
}

function lead() {
  const r = S.result;
  app.innerHTML = `
    <div class="kicker">Передать экспертам</div>
    <h2>Эксперт свяжется и оценит применимость по вашему брифу</h2>
    <input type="text" id="name" placeholder="Имя" />
    <input type="email" id="email" placeholder="Рабочая почта" />
    <input type="text" id="website" class="hp" tabindex="-1" autocomplete="off" />
    <label class="chk"><input type="checkbox" id="consent" /> <span>Согласен на обработку контакта для передачи брифа аналитикам (хранение до 90 дней).</span></label>
    <h2 style="font-size:18px;margin-top:18px">Насколько легко было описать задачу?</h2>
    <div class="scale" id="csat">${[1,2,3,4,5].map((i)=>`<button data-i="${i}">${i}</button>`).join("")}</div>
    <button class="btn big" id="send">Отправить</button>
    <button class="btn dunno" id="skip" style="margin-top:10px">Пропустить</button>`;
  let csat = 0;
  app.querySelectorAll("#csat button").forEach((b) => b.onclick = () => {
    csat = +b.dataset.i; app.querySelectorAll("#csat button").forEach((x)=>x.style.background="#fff"); b.style.background="var(--chip)";
  });
  const finish = async () => {
    const consent = document.getElementById("consent").checked;
    await api("/api/lead", { name: name.value, email: email.value, website: website.value, consent,
      verdict: r.verdict, archetype: r.archetype, industry: S.answers.industry });
    if (csat) await api("/api/event", { type: "csat", csat });
    done();
  };
  document.getElementById("send").onclick = finish;
  document.getElementById("skip").onclick = async () => { if (csat) await api("/api/event", { type: "csat", csat }); done(); };
}

function done() {
  app.innerHTML = `<h1>Готово ✅</h1><p>Спасибо! Бриф можно скачать повторно или передать экспертам.</p>
    <div class="row"><button class="btn" id="again">Проверить другую задачу</button>
    <button class="btn ghost" id="tohome2">На главную</button></div>`;
  document.getElementById("again").onclick = landing;
  document.getElementById("tohome2").onclick = home;
}

home();
