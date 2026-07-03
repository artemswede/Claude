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
  poc_now: {
    ic: "check",
    sub: "Задача похожа на то, что уже решают на практике. Можно начинать пилот.",
    recTitle: "Как можно сделать",
    recs: [
      "Гибридный подход (квантовый + классический): квантовый сопроцессор берёт самую «тяжёлую» часть, остальное считает классика.",
      "Квантово-вдохновлённые алгоритмы (тензорные сети): дают эффект уже сегодня на обычном оборудовании.",
    ],
    next: "Передайте описание задачи экспертам — подберём конкретный подход и спланируем пилот.",
  },
  watchlist: {
    ic: "clock",
    sub: "Потенциал есть, но сейчас рано — лучше вернуться позже.",
    recTitle: "Что требуется и когда вернуться",
    recs: [
      "Обычно нужно: укрупнить масштаб задачи, привести в порядок данные или дождаться более зрелого оборудования.",
      "Вернуться стоит, когда вырастут объём и сложность задачи или появятся недостающие данные.",
      "Уже сейчас можно попробовать гибридный или quantum-inspired вариант как промежуточный шаг.",
    ],
    next: "Сохраните описание задачи — пришлём ориентиры, когда подход созреет, и при желании обсудим промежуточный вариант.",
  },
  classical: {
    ic: "cog",
    sub: "Квантовый подход здесь не нужен — задача эффективно решается классическими методами. Поможем и с этим.",
    recTitle: "Что предлагаем",
    recs: [
      "Промышленные решатели оптимизации и эвристики (класс OR-Tools / CP-SAT, коммерческие солверы) — для расписаний, маршрутов, распределения.",
      "Классический анализ данных и ML — где задача про прогноз и закономерности.",
      "Quantum-inspired методы — как необязательное усиление, без квантового оборудования.",
    ],
    next: "Передадим задачу профильным аналитикам — предложат конкретный инструмент и план внедрения.",
  },
  discard: {
    ic: "x",
    sub: "Сейчас в задаче нет признаков, где квантовые вычисления дают преимущество. Это нормально — большинство задач отлично решаются классически.",
    recTitle: "Что можно сделать",
    recs: [
      "Скорее всего, оптимальный путь — классические ИТ-инструменты под вашу задачу.",
      "Если нужно, подскажем подходящее классическое решение или подключим аналитика.",
    ],
    next: "По вашему желанию передадим задачу аналитикам для рекомендации по классическому пути.",
  },
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
    options: [
      { v: "hard", label: "Да, есть жёсткие", note: "что-то нельзя нарушать ни при каких условиях" },
      { v: "soft", label: "Только пожелания", note: "желательно, но можно отступить" },
      { v: "none", label: "Почти нет" } ] },
  a4: { key: "a4", title: "Как решаете сейчас и хватает ли?", type: "select", dunno: true,
    options: [ { v: "enough", label: "Способ есть, всё устраивает" }, { v: "partial", label: "Решаем, но частично не хватает" }, { v: "not", label: "Не хватает / не получается" } ] },
  a5: { key: "a5", title: "Когда задача растёт — время резко увеличивается?", type: "select", dunno: true,
    options: [
      { v: "sharp", label: "Да, резко", note: "при росте объёма расчёт затягивается в разы" },
      { v: "slow", label: "Понемногу", note: "время растёт примерно пропорционально" },
      { v: "no", label: "Нет" } ] },
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
    options: [
      { v: "small", label: "Немного параметров", note: "несколько чисел или настроек" },
      { v: "medium", label: "Таблицы среднего размера" },
      { v: "huge", label: "Очень большие массивы", note: "миллионы записей / терабайты на вход" } ] },
  t2: { key: "t2", title: "Как часто решаете задачу?", type: "select", dunno: true,
    options: [ { v: "once", label: "Разово" }, { v: "periodic", label: "Периодически" }, { v: "daily", label: "Ежедневно" }, { v: "constant", label: "Постоянно" } ] },
  t3: { key: "t3", title: "Сколько задача стоит сейчас в год, ₽?", hint: "оценка затрат/потерь по задаче", type: "select", dunno: true,
    options: [
      { v: "500000", label: "Менее 1 млн ₽" },
      { v: "3000000", label: "От 1 до 5 млн ₽" },
      { v: "12000000", label: "5–20 млн ₽" },
      { v: "200000000", label: "От 20 млн ₽ и выше" },
      { v: "__custom__", label: "Свой вариант", note: "указать точную сумму", custom: true } ] },
  t4: { key: "t4", title: "Насколько вы готовы к пилоту: данные, ИТ, бюджет?", type: "select", dunno: true,
    options: [
      { v: "high", label: "Высокая", note: "данные в цифре, есть ИТ-специалисты или подрядчик, выделен бюджет" },
      { v: "medium", label: "Средняя", note: "что-то есть, что-то нет (например, данные есть, а бюджета пока нет)" },
      { v: "low", label: "Низкая", note: "данные на бумаге или «в головах», нет ИТ-команды и бюджета" } ] },
  t5: { key: "t5", title: "Нужен идеально точный ответ — или достаточно быстрого, близкого к лучшему?", type: "select", dunno: true,
    options: [
      { v: "yes", label: "Достаточно близкого", note: "решение «почти лучшее», но быстро — нам подходит" },
      { v: "depends", label: "Зависит" },
      { v: "no", label: "Нужен только идеальный", note: "приближённое решение не подходит" } ] },
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

const CASES = [
  { e: "🏪", name: "Save-On-Foods", sector: "Ритейл", task: "Автоматизация расписаний персонала", result: "сокращение времени планирования до 80%", tag: "в эксплуатации",
    more: "Сеть супермаркетов автоматизировала составление графиков смен. Раньше — десятки часов ручной работы в неделю, после — минуты, с экономией тысяч человеко-часов в год." },
  { e: "📡", name: "NTT DOCOMO", sector: "Телеком", task: "Оптимизация мобильной сети", result: "снижение нагрузки на 15%", tag: "в эксплуатации",
    more: "Оператор оптимизировал распределение сигнальной нагрузки между базовыми станциями и повысил ёмкость сети в пиковые часы." },
  { e: "🏭", name: "Ford Otosan", sector: "Производство", task: "Оптимизация производственного плана", result: "сокращение времени расчётов до 80%", tag: "в эксплуатации",
    more: "Автозавод ускорил планирование последовательности сборки (более 1500 вариантов) — с десятков минут до нескольких." },
];
function caseBlock(c, i) {
  return `<div class="cblk"><div class="ce">${c.e}</div><div class="cbody">
    <b>${c.name}</b><span class="csec">${c.sector}</span>
    <div>${c.task}</div>
    <div class="cres">Результат: ${c.result}</div>
    <div class="crow"><span class="tag">${c.tag}</span><button class="link cmore" data-i="${i}">Подробнее →</button></div>
  </div></div>`;
}
function caseDetail(i) {
  const c = CASES[i];
  app.innerHTML = `
    <button class="link" id="back">← Назад</button>
    <div class="kicker">${c.sector} · ${c.tag}</div>
    <h2>${c.e} ${c.name}</h2>
    <p><b>Задача:</b> ${c.task}</p>
    <p><b>Результат:</b> ${c.result}</p>
    <p>${c.more}</p>
    <p class="muted" style="font-size:12px">Публичный кейс (по материалам компаний и платформы D-Wave). Приведён для иллюстрации типов задач, не является проектом сервиса.</p>
    <button class="btn" id="start2">Оценить свою задачу</button>`;
  document.getElementById("back").onclick = home;
  document.getElementById("start2").onclick = onboarding;
}
function qtCard(e, t, d, tag, soon) {
  return `<div class="cblk"><div class="ce">${e}</div><div class="cbody"><b>${t}</b> <span class="tag ${soon ? "tag-soon" : ""}">${tag}</span><div class="cmuted">${d}</div></div></div>`;
}
function quantum() {
  app.innerHTML = `
    <button class="link" id="back">← На главную</button>
    <div class="kicker">О квантовых технологиях</div>
    <h2>Три класса квантовых технологий</h2>
    <p>Современные квантовые технологии — это умение управлять отдельными квантовыми объектами: одиночными атомами, электронами или фотонами. Их свойства — суперпозицию и запутанность — применяют для трёх разных классов задач.</p>
    <div class="glo"><b>1. Квантовые вычисления — обработка информации.</b> Квантовые эффекты ускоряют расчёты и позволяют браться за задачи, недоступные классическим суперкомпьютерам (эффект «взрыва размерности»): сложную комбинаторную оптимизацию, моделирование новых материалов, лекарств и химических соединений, криптоанализ. <i>Именно эти задачи оценивает сервис.</i></div>
    <p class="muted">Скоро сервис пополнится направлениями:</p>
    <div class="glo"><b>2. Квантовые коммуникации — защищённая передача данных. <span class="tag tag-soon">скоро</span></b><br/>Надёжность на уровне законов физики: неизвестное квантовое состояние нельзя скопировать, не разрушив его. Ключ шифрования кодируют в одиночные фотоны и передают по оптоволокну или через спутник; любая попытка перехвата меняет состояние частиц — и вмешательство сразу обнаруживается.</div>
    <div class="glo"><b>3. Квантовые сенсоры — высокоточные измерения. <span class="tag tag-soon">скоро</span></b><br/>Хрупкость квантовых систем превращается в преимущество: малейшее воздействие меняет их состояние, поэтому отдельные частицы становятся сверхчувствительными датчиками. Они измеряют ничтожные изменения среды — например, колебания температуры или электромагнитных полей.</div>
    <button class="btn" id="start2">Оценить свою задачу</button>`;
  document.getElementById("back").onclick = home;
  document.getElementById("start2").onclick = onboarding;
}
function home() {
  app.innerHTML = `
    <div class="hero-ic">${icon("atom")}</div>
    <div class="kicker">Для бизнеса и индустриальных команд</div>
    <h1>Проверьте, может ли квантовый подход помочь вашей задаче</h1>
    <p class="muted">За 10–15 минут оценим потенциал задачи, покажем возможные преимущества и подскажем следующий шаг. На языке бизнеса, без физики.</p>
    <p class="trust">${icon("check")} Оценка основана на базе индустриальных кейсов и методике Национальной квантовой лаборатории.</p>
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

    <h2 class="sec">По итогам оценки вы получите</h2>
    <ul class="get">
      <li>Уровень потенциала задачи и объяснение оценки</li>
      <li>Рекомендацию: классика / quantum-inspired / квантовый компьютер</li>
      <li>Похожий индустриальный кейс с ориентиром эффекта</li>
      <li>Структурированное описание задачи — можно скачать</li>
      <li>Возможность обсудить задачу с экспертами</li>
    </ul>

    <h2 class="sec">Кейсы похожих задач</h2>
    <div class="cgrid">${CASES.map(caseBlock).join("")}</div>
    <p class="muted" style="font-size:12px">Публичные индустриальные кейсы (D-Wave и партнёры) — для иллюстрации типов задач.</p>

    <h2 class="sec">Три класса квантовых технологий</h2>
    <p class="muted">Квантовые свойства — суперпозиция и запутанность — применяют для трёх разных классов задач.</p>
    <div class="cgrid">
      ${qtCard("🖥️", "Квантовые вычисления", "Ускорение расчётов и задачи, недоступные классике: оптимизация, моделирование, химия.", "оцениваем сейчас", false)}
      ${qtCard("🔐", "Квантовые коммуникации", "Защищённая передача данных на уровне законов физики.", "скоро", true)}
      ${qtCard("📡", "Квантовые сенсоры", "Сверхточные измерения слабейших изменений среды.", "скоро", true)}
    </div>
    <button class="btn ghost" id="qmore" style="margin-top:6px">Подробнее о квантовых технологиях</button>

    <div class="plate">Наша цель — не доказать необходимость кванта, а честно оценить применимость. Иногда лучший результат диагностики — рекомендация остаться на классических методах.</div>

    <div class="row sec">
      <button class="btn ghost" id="kb">База знаний</button>
      <button class="btn ghost" id="adv">Чем мы отличаемся</button>
      <button class="btn ghost" id="about">О проекте</button>
    </div>`;
  document.getElementById("start").onclick = onboarding;
  document.getElementById("kb").onclick = knowledge;
  document.getElementById("about").onclick = about;
  document.getElementById("adv").onclick = advantages;
  app.querySelectorAll(".cmore").forEach((b) => (b.onclick = () => caseDetail(+b.dataset.i)));
  document.getElementById("qmore").onclick = quantum;
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
function advantages() {
  const A = [
    ["Язык бизнеса на входе — структура эксперта на выходе", "Конкуренты с глубокой формализацией (D-Wave, QBoard) требуют технических знаний уже на входе — QUBO, кубиты, алгоритмы; MS и IBM дают удобный UX, но не выдают квантовый вердикт. Q-Scope принимает бизнес-задачу «как есть» и отдаёт структурированный бриф: начать может операционный или финансовый директор, без найма физика. Команде — отсев нецелевых задач до привлечения дорогого эксперта."],
    ["Честный вердикт, включая «достаточно классики»", "Вендоры (D-Wave, IBM) и облака (MS/AWS) заинтересованы говорить «да, квант нужен». Q-Scope вендор-нейтрален: рекомендуем то, что реально подходит задаче — классику, квантово-вдохновлённую классику или гибрид. Поэтому вердикту доверяют: мы не продаём конкретное железо."],
    ["Self-service без sales-gate — результат за 10–15 минут", "У D-Wave и IBM путь «оставьте заявку, менеджер свяжется», MS ведёт к Azure-консультации. Мы даём структурированный результат сразу, без посредника — это расширяет воронку и снижает стоимость привлечения."],
    ["Маркировка зрелости кейсов", "D-Wave публикует кейсы без явной зрелости, IBM и MS не классифицируют их по применимости. Мы помечаем: в эксплуатации / пилот / исследование — клиент видит реалистичную картину и решает с открытыми глазами."],
    ["Стандартизированный бриф — операционная ценность", "Ни один конкурент не выдаёт единый документ для передачи задачи эксперту: каждый хэндофф уникален. Q-Scope формирует бриф с фиксированной структурой — хаотичный поток превращается в стандартную очередь и кратно сокращает время первичного разбора."],
  ];
  app.innerHTML = `
    <button class="link" id="back">← На главную</button>
    <div class="kicker">Чем мы отличаемся</div>
    <h2>Конкурентные преимущества</h2>
    ${A.map((a, i) => `<div class="glo"><b>${i + 1}. ${a[0]}</b><br/>${a[1]}</div>`).join("")}
    <button class="btn" id="start2">Оценить свою задачу</button>`;
  document.getElementById("back").onclick = home;
  document.getElementById("start2").onclick = onboarding;
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
    <button class="link" id="tohome">← На главную</button>
    <div class="kicker">Что вас ждёт</div>
    <h2>Короткая диагностика задачи</h2>
    <ul class="get">
      <li>~10 коротких вопросов, 3–5 минут</li>
      <li>На языке бизнеса — технические детали не нужны</li>
      <li>Можно отвечать «не знаю», это нормально</li>
      <li>В конце — предварительная оценка и описание задачи для экспертов</li>
    </ul>
    <button class="btn big" id="go">${icon("bolt")} Начать</button>`;
  document.getElementById("go").onclick = () => { S.answers = {}; S.qi = 0; S.start = Date.now(); ask(); };
  document.getElementById("tohome").onclick = home;
}

function customInput(q) {
  app.innerHTML = `
    <div class="kicker">${q.title}</div>
    <h2>Укажите сумму, ₽ в год</h2>
    <input type="number" id="inp" placeholder="например, 8000000" />
    <button class="btn" id="next">Далее</button>
    <div class="row" style="margin-top:14px"><button class="btn dunno" id="back">← Назад</button></div>`;
  document.getElementById("next").onclick = () => { const el = document.getElementById("inp"); S.answers[q.key] = el.value === "" ? "dunno" : el.value; S.qi++; ask(); };
  document.getElementById("back").onclick = () => ask();
}

function ask() {
  const queue = computeQueue(S.answers);
  if (S.qi >= queue.length) return submit();
  const q = Q[queue[S.qi]];
  const pct = Math.round((S.qi / queue.length) * 100);
  let body = "";
  const prev = S.answers[q.key] !== undefined && S.answers[q.key] !== "dunno" ? S.answers[q.key] : "";
  if (q.type === "select" || q.type === "cards") {
    body = `<div class="opts">${q.options
      .map((o) => `<button class="opt" data-v="${o.v}" ${o.custom ? 'data-custom="1"' : ""} ${o.disabled ? "disabled" : ""}>${o.label}${o.note ? `<span class="note">${o.note}</span>` : ""}${o.disabled ? `<span class="tag">в разработке</span>` : ""}</button>`)
      .join("")}</div>`;
  } else if (q.type === "number") {
    body = `<input type="number" id="inp" placeholder="${q.hint || "число"}" value="${prev}" /><button class="btn" id="next">Далее</button>`;
  } else if (q.type === "text") {
    body = `<input type="text" id="inp" placeholder="одним предложением" value="${prev}" /><button class="btn" id="next">Далее</button>`;
  }
  app.innerHTML = `
    <div class="bar"><i style="width:${pct}%"></i></div>
    <div class="kicker">Вопрос ${S.qi + 1} из ${queue.length}</div>
    <h2>${q.title}</h2>${q.hint ? `<p class="muted">${q.hint}</p>` : ""}
    ${body}
    <div class="row" style="margin-top:14px">
      <button class="btn dunno" id="prev">← Назад</button>
      ${q.dunno ? `<button class="btn dunno" id="dunno">Не знаю</button>` : ""}
      ${q.optional ? `<button class="btn dunno" id="skip">Пропустить</button>` : ""}
    </div>`;
  const set = (v) => { S.answers[q.key] = v; S.qi++; ask(); };
  app.querySelectorAll(".opt").forEach((b) => { if (!b.disabled) b.onclick = () => (b.dataset.custom ? customInput(q) : set(b.dataset.v)); });
  const nx = document.getElementById("next");
  if (nx) nx.onclick = () => { const el = document.getElementById("inp"); set(el.value === "" ? "dunno" : el.value); };
  const dn = document.getElementById("dunno"); if (dn) dn.onclick = () => set("dunno");
  const sk = document.getElementById("skip"); if (sk) sk.onclick = () => set("");
  document.getElementById("prev").onclick = () => { if (S.qi > 0) { S.qi--; ask(); } else onboarding(); };
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
    <div class="recs"><b>${info.recTitle}</b><ul>${info.recs.map((x) => `<li>${x}</li>`).join("")}</ul></div>
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
  document.getElementById("again").onclick = onboarding;
  document.getElementById("tohome2").onclick = home;
}

home();
