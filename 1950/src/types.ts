// Типы предметной области (по spec-frozen/01f, 01e).
export type Branch = "A" | "B";
export type Archetype = "optimization" | "simulation";
export type GateState = "pass" | "partial" | "fail";
export type Verdict = "poc_now" | "watchlist" | "classical" | "discard";

export type Answers = Record<string, string | number | undefined>;

export interface Gates {
  g1: GateState;
  g2: GateState;
  g3: GateState;
}

export interface Scores {
  x: number;
  y: number;
  criteria: Record<string, number>;
}

export interface Scenario {
  subtype: string;
  reference: string;
  effect: string;
  maturity: string;
  horizon: string;
}

export interface EvaluateResult {
  branch: Branch;
  archetype: Archetype;
  gates: Gates;
  scores: Scores;
  verdict: Verdict;
  confidence: number;
  gaps: string[];
  scenario: Scenario;
  brief: string;
}
