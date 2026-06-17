// Библиотека «подтип → референс-кейс» (spec-frozen/01f §7). Зашита заранее (без БД).
import type { Branch, Scenario } from "../types";

type Lib = Record<string, Omit<Scenario, "subtype">>;

const A_LIB: Lib = {
  schedule: { reference: "Save-On-Foods", effect: "−80% времени планирования", maturity: "production", horizon: "сейчас" },
  routes: { reference: "Port of LA / DENSO", effect: "+пропускная способность, −простои", maturity: "production/PoC", horizon: "сейчас" },
  equipment_load: { reference: "Ford Otosan", effect: "−80% времени плана", maturity: "production", horizon: "сейчас" },
  production_plan: { reference: "Ford Otosan", effect: "−80% времени плана", maturity: "production", horizon: "сейчас" },
  resource_alloc: { reference: "BBVA (quantum-inspired)", effect: "эффективнее классики (PoC)", maturity: "PoC", horizon: "сейчас" },
  recipe: { reference: "BBVA (quantum-inspired)", effect: "эффективнее классики (PoC)", maturity: "PoC", horizon: "сейчас" },
};

const B_LIB: Lib = {
  new_material: { reference: "Mitsubishi/JSR, Mercedes", effect: "точнее классики (research)", maturity: "research", horizon: "средне-/долгосрок" },
  additive: { reference: "Mitsubishi/JSR", effect: "точнее классики (research)", maturity: "research", horizon: "средне-/долгосрок" },
  corrosion: { reference: "Boeing", effect: "точнее ведущего метода (research)", maturity: "research", horizon: "средне-/долгосрок" },
  catalyst: { reference: "Covestro", effect: "готовность к 200–500 кубитам", maturity: "research", horizon: "долгосрок" },
  coating: { reference: "Mitsubishi/JSR", effect: "расчёт свойств (research)", maturity: "research", horizon: "средне-/долгосрок" },
  battery: { reference: "Mercedes (Li-S)", effect: "расчёт свойств (research)", maturity: "research", horizon: "средне-/долгосрок" },
};

const DEFAULTS: Record<Branch, Omit<Scenario, "subtype">> = {
  A: { reference: "ближайший кейс оптимизации", effect: "ориентир, не гарантия", maturity: "—", horizon: "сейчас" },
  B: { reference: "ближайший кейс по материалам", effect: "ориентир, не гарантия", maturity: "research", horizon: "средне-/долгосрок" },
};

export function matchScenario(branch: Branch, subtype: string | undefined): Scenario {
  const lib = branch === "A" ? A_LIB : B_LIB;
  const hit = subtype ? lib[subtype] : undefined;
  const base = hit ?? DEFAULTS[branch];
  return { subtype: subtype ?? "—", ...base };
}
