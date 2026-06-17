"use strict";
// Project 1950 — фронтенд MVP. Один вопрос за раз, ветки A/B/E, 4-цветный вердикт.
const app = document.getElementById("app");
const S = { answers: {}, qi: 0, start: 0, result: null };

const api = (url, body) =>
  fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) })
    .then((r) => r.json())
    .catch(() => ({}));

const VCOLOR = { poc_now: "green", watchlist: "amber", classical: "blue", discard: "red" };

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
function landing() {
  app.innerHTML = `
    <div class="kicker">Квантовые технологии · оценка задачи</div>
    <h1>Опишите бизнес-задачу — и поймите, перспективна ли она для квантовых вычислений</h1>
    <p class="muted">За пару минут, на языке бизнеса, без физики. Получите честный вердикт и бриф для экспертов.</p>
    <div class="plate">Не обещаем эффект. Можем честно сказать «вам достаточно обычных решений». Это вход в нашу воронку проектов — с задачей не бросаем.</div>
    <button class="btn big" id="go">Проверить задачу</button>`;
  document.getElementById("go").onclick = onboarding;
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
  app.innerHTML = `
    <div class="kicker">Результат</div>
    <div class="badge ${VCOLOR[r.verdict]}"><span class="dot" style="background:#fff"></span>${r.verdictLabel}</div>
    <p style="margin-top:16px"><b>Ваша задача — это ${arche}.</b> ${baseline}</p>
    <div class="plate"><b>Похоже на кейс:</b> ${r.scenario.reference} (${r.scenario.maturity}).<br/>
      Ориентир эффекта: ${r.scenario.effect}; горизонт: ${r.scenario.horizon}.<br/>
      <span class="muted">Это оценка по аналогии, не гарантия эффекта.</span></div>
    ${r.confidence < 0.6 ? `<p class="muted">Часть ответов — «не знаю», поэтому вердикт предварительный.</p>` : ""}
    <button class="btn big" id="brief">Получить бриф</button>`;
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
    <button class="btn" id="again">Проверить другую задачу</button>`;
  document.getElementById("again").onclick = landing;
}

landing();
