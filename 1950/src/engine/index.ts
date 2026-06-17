// Детерминированный движок (spec-frozen/01f). Чистые функции, без I/O.
import type { Answers, Archetype, Branch, EvaluateResult, GateState, Gates, Scores, Verdict } from "../types";
import { matchScenario } from "../data/scenarios";

const HIGH = 3.0; // порог high/low (01f §6)

const s = (a: Answers, k: string): string => (a[k] === undefined ? "" : String(a[k]));
const n = (a: Answers, k: string): number => {
  const v = a[k];
  const x = typeof v === "number" ? v : parseFloat(String(v));
  return Number.isFinite(x) ? x : NaN;
};
const isDunno = (v: string | number | undefined): boolean => v === undefined || v === "" || v === "dunno";

// --- ветка и архетип ---
export function deriveBranch(a: Answers): Branch {
  const t = s(a, "task_type");
  if (t === "B") return "B";
  if (t === "E") return s(a, "e1") === "B" ? "B" : "A";
  return "A"; // 'A' и дефолт
}
const archetypeOf = (b: Branch): Archetype => (b === "B" ? "simulation" : "optimization");

// --- scale_score (01f §3) ---
function scaleScore(a: Answers, b: Branch): number {
  if (b === "A") {
    const v = n(a, "a2");
    if (!Number.isFinite(v)) return 2.5;
    if (v < 10) return 0;
    if (v < 50) return 1;
    if (v < 500) return 2;
    if (v < 5000) return 3;
    if (v < 50000) return 4;
    return 5;
  }
  const map: Record<string, number> = { small: 1, medium: 3, large: 5 };
  return map[s(a, "b3")] ?? 2.5;
}

// --- gate-фильтры по ветке (01f §4) ---
export function computeGates(a: Answers, b: Branch, scale: number): Gates {
  if (b === "A") {
    const a4 = s(a, "a4"); // not | partial | enough | dunno
    const a5 = s(a, "a5"); // sharp | slow | no | dunno
    const a3 = s(a, "a3"); // hard | soft | none | dunno
    const a6 = s(a, "a6"); // min | max | dunno
    const g1: GateState = (a4 === "not" || a4 === "partial") && (a5 === "sharp" || scale >= 2) ? "pass" : "fail";
    const objClear = a6 === "min" || a6 === "max";
    const g2: GateState = a3 === "hard" && objClear ? "pass" : a3 === "hard" || objClear ? "partial" : "fail";
    const g3: GateState = s(a, "t1") === "huge" ? "fail" : "pass";
    return { g1, g2, g3 };
  }
  const b2 = s(a, "b2"); // not_enough | enough | dunno
  const b4 = s(a, "b4"); // clear | unclear | dunno
  const g1: GateState = b2 === "not_enough" ? "pass" : "fail";
  const g2: GateState = b4 === "clear" ? "pass" : b4 === "dunno" ? "partial" : "fail";
  return { g1, g2, g3: "pass" };
}

// --- критерии и оси (01f §5) ---
export function computeScores(a: Answers, b: Branch, scale: number): Scores {
  // pain
  let pain: number;
  if (b === "A") {
    const m: Record<string, number> = { not: 5, partial: 3, enough: 0 };
    pain = m[s(a, "a4")] ?? 2.5;
  } else {
    const m: Record<string, number> = { not_enough: 5, enough: 1 };
    pain = m[s(a, "b2")] ?? 2.5;
  }
  // growth
  const growth = b === "A" ? ({ sharp: 5, slow: 2, no: 0 } as Record<string, number>)[s(a, "a5")] ?? 2.5 : 2.5;
  const cHardness = (pain + scale + growth) / 3;

  // value: стоимость ₽ + бонус частоты
  const cost = n(a, "t3");
  let cValue: number;
  if (!Number.isFinite(cost)) cValue = 2;
  else if (cost < 1e6) cValue = 1;
  else if (cost < 1e7) cValue = 2;
  else if (cost < 1e8) cValue = 3;
  else if (cost < 1e9) cValue = 4;
  else cValue = 5;
  const freq = s(a, "t2");
  if (freq === "daily" || freq === "constant") cValue = Math.min(5, cValue + 1);

  const cArchfit = b === "B" ? 5 : 4;
  const cFeasibility = b === "A" ? 5 : scale <= 3 ? 4 : 2;
  const cReadiness = ({ high: 5, medium: 3, low: 1 } as Record<string, number>)[s(a, "t4")] ?? 2.5;
  const cApprox = ({ yes: 5, depends: 3, no: 1 } as Record<string, number>)[s(a, "t5")] ?? 2.5;

  const y = 0.5 * cHardness + 0.3 * cArchfit + 0.2 * cApprox;
  const x = 0.5 * cValue + 0.3 * cFeasibility + 0.2 * cReadiness;
  return {
    x: Math.round(x * 10) / 10,
    y: Math.round(y * 10) / 10,
    criteria: { cValue, cHardness: Math.round(cHardness * 10) / 10, cArchfit, cFeasibility, cReadiness, cApprox },
  };
}

// --- вердикт (01f §6, проверено на CSV 16/16) ---
export function decideVerdict(g: Gates, x: number, y: number): Verdict {
  const hardFail = g.g1 === "fail" || g.g2 === "fail" || g.g3 === "fail";
  if (hardFail) return x >= HIGH ? "classical" : "discard";
  return y >= HIGH && x >= HIGH ? "poc_now" : "watchlist";
}

// --- пробелы «не знаю» (01f §2) ---
const LABELS: Record<string, string> = {
  industry: "Отрасль", role: "Роль", task_type: "Тип задачи",
  a1: "Что оптимизируете", a2: "Число объектов", a3: "Ограничения", a4: "Текущее решение", a5: "Рост сложности", a6: "Цель",
  b1: "Что моделируете", b2: "Хватает ли обычных методов", b3: "Размер системы", b4: "Цель расчёта",
  t1: "Данные", t2: "Частота", t3: "Стоимость задачи", t4: "Готовность", t5: "Допустимость близкого решения",
};
function collectGaps(a: Answers, keys: string[]): string[] {
  return keys.filter((k) => isDunno(a[k])).map((k) => LABELS[k] ?? k);
}

export function evaluate(a: Answers): EvaluateResult {
  const branch = deriveBranch(a);
  const archetype = archetypeOf(branch);
  const scale = scaleScore(a, branch);
  const gates = computeGates(a, branch, scale);
  const scores = computeScores(a, branch, scale);
  const verdict = decideVerdict(gates, scores.x, scores.y);

  const relevant = ["industry", "task_type", ...(branch === "A" ? ["a1", "a2", "a3", "a4", "a5", "a6"] : ["b1", "b2", "b3", "b4"]), "t1", "t2", "t3", "t4", "t5"];
  const answered = relevant.filter((k) => !isDunno(a[k])).length;
  const confidence = Math.round((answered / relevant.length) * 100) / 100;
  const gaps = collectGaps(a, relevant);

  const subtype = branch === "A" ? s(a, "a1") : s(a, "b1");
  const scenario = matchScenario(branch, subtype || undefined);
  const brief = buildBrief(a, { branch, archetype, gates, scores, verdict, confidence, gaps, scenario, brief: "" });
  return { branch, archetype, gates, scores, verdict, confidence, gaps, scenario, brief };
}

// --- бриф (Markdown) для бизнес-аналитиков (01e/01d) ---
const VERDICT_RU: Record<Verdict, string> = {
  poc_now: "🟢 Можно начинать пилот",
  watchlist: "🟡 Перспективно — вернёмся позже",
  classical: "🔵 Есть классическое решение",
  discard: "🔴 Квант здесь не нужен",
};
export function verdictLabel(v: Verdict): string {
  return VERDICT_RU[v];
}

export function buildBrief(a: Answers, r: EvaluateResult): string {
  const L: string[] = [];
  L.push(`# Бриф задачи — Project 1950`);
  L.push("");
  L.push(`**Вердикт:** ${VERDICT_RU[r.verdict]}${r.confidence < 0.6 ? " _(предварительно, требуются уточнения)_" : ""}`);
  L.push(`**Тип задачи:** ${r.archetype === "optimization" ? "поиск лучшего варианта" : "расчёт свойств материалов"}`);
  L.push(`**Баллы движка:** бизнес-ценность X=${r.scores.x} · квантовый потенциал Y=${r.scores.y} · confidence=${Math.round(r.confidence * 100)}%`);
  L.push(`**Gate-фильтры:** G1=${r.gates.g1} · G2=${r.gates.g2} · G3=${r.gates.g3}`);
  L.push("");
  L.push(`## Сценарий`);
  L.push(`Похоже на кейс **${r.scenario.reference}** (${r.scenario.maturity}); ориентир эффекта: ${r.scenario.effect}; горизонт: ${r.scenario.horizon}. _Это оценка, не гарантия._`);
  L.push("");
  L.push(`## Ответы клиента`);
  L.push(`- Отрасль: ${a.industry ?? "—"} · Роль: ${a.role ?? "—"}`);
  if (r.branch === "A") {
    L.push(`- Что оптимизирует: ${a.a1 ?? "—"} · объектов: ${a.a2 ?? "—"} · ограничения: ${a.a3 ?? "—"}`);
    L.push(`- Текущее решение/боль: ${a.a4 ?? "—"} · рост сложности: ${a.a5 ?? "—"} · цель: ${a.a6 ?? "—"}`);
  } else {
    L.push(`- Что моделирует: ${a.b1 ?? "—"} · хватает ли обычных методов: ${a.b2 ?? "—"} · размер: ${a.b3 ?? "—"} · цель: ${a.b4 ?? "—"}`);
  }
  L.push(`- Данные: ${a.t1 ?? "—"} · частота: ${a.t2 ?? "—"} · стоимость: ${a.t3 ?? "—"} · готовность: ${a.t4 ?? "—"} · близкое решение ок: ${a.t5 ?? "—"}`);
  if (a.e3) L.push(`- Комментарий клиента: ${a.e3}`);
  L.push("");
  if (r.gaps.length) {
    L.push(`## Пробелы (клиент ответил «не знаю»)`);
    L.push(r.gaps.map((g) => `- ${g}`).join("\n"));
    L.push("");
  }
  L.push(`## Для эксперта`);
  L.push(`- [ ] Вердикт эксперта: __________`);
  L.push(`- [ ] Взять в работу / отклонить / уточнить`);
  return L.join("\n");
}
