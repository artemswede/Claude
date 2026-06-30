/**
 * Расчёт целей по КБЖУ.
 * BMR — формула Mifflin-St Jeor; TDEE = BMR × коэффициент активности;
 * затем поправка под цель (похудение/поддержание/набор) и разбивка по макросам.
 *
 * Важно: это оценочный ориентир, НЕ медицинская рекомендация.
 */

export type Sex = "male" | "female";
export type Activity = "sedentary" | "light" | "moderate" | "active" | "very_active";
export type Goal = "lose" | "maintain" | "gain";

export interface ProfileInput {
  sex: Sex;
  age: number; // лет
  heightCm: number;
  weightKg: number;
  activity: Activity;
  goal: Goal;
}

export interface MacroTargets {
  kcal: number;
  protein: number; // г
  fat: number; // г
  carb: number; // г
}

const ACTIVITY_FACTOR: Record<Activity, number> = {
  sedentary: 1.2, // мало движения, офис
  light: 1.375, // лёгкая активность 1-3 р/нед
  moderate: 1.55, // 3-5 р/нед
  active: 1.725, // 6-7 р/нед
  very_active: 1.9, // тяжёлые тренировки/физ. работа
};

const GOAL_FACTOR: Record<Goal, number> = {
  lose: 0.8, // дефицит ~20%
  maintain: 1.0,
  gain: 1.1, // профицит ~10%
};

/** Базовый обмен (ккал/сутки) по Mifflin-St Jeor. */
export function bmr(p: ProfileInput): number {
  const base = 10 * p.weightKg + 6.25 * p.heightCm - 5 * p.age;
  return p.sex === "male" ? base + 5 : base - 161;
}

/** Полный расход с учётом активности. */
export function tdee(p: ProfileInput): number {
  return bmr(p) * ACTIVITY_FACTOR[p.activity];
}

/**
 * Целевые КБЖУ под цель пользователя.
 * Белок: 1.8 г/кг (адекватно для ПП и сохранения мышц).
 * Жир: 25% калорийности. Углеводы — остаток.
 */
export function calcTargets(p: ProfileInput): MacroTargets {
  const kcal = Math.round(tdee(p) * GOAL_FACTOR[p.goal]);

  const protein = Math.round(1.8 * p.weightKg);
  const fat = Math.round((kcal * 0.25) / 9); // 9 ккал/г жира

  const kcalFromProtein = protein * 4;
  const kcalFromFat = fat * 9;
  const carb = Math.max(0, Math.round((kcal - kcalFromProtein - kcalFromFat) / 4)); // 4 ккал/г

  return { kcal, protein, fat, carb };
}

export const ACTIVITY_LABELS: Record<Activity, string> = {
  sedentary: "Сидячий (мало движения)",
  light: "Лёгкая активность (1–3 р/нед)",
  moderate: "Средняя (3–5 р/нед)",
  active: "Высокая (6–7 р/нед)",
  very_active: "Очень высокая (физ. работа)",
};

export const GOAL_LABELS: Record<Goal, string> = {
  lose: "Снизить вес",
  maintain: "Поддерживать",
  gain: "Набрать массу",
};

export const SEX_LABELS: Record<Sex, string> = {
  male: "Мужской",
  female: "Женский",
};
