/**
 * Слой доступа к D1. Тонкие функции над таблицами, без ORM (этап 1).
 * Если запросов станет много — мигрируем на Drizzle.
 */
import { nowIso } from "../util/time";
import type { Activity, Goal, MacroTargets, Sex } from "../domain/goals";

export interface UserRow {
  id: number;
  tg_id: number;
  created_at: string;
  sex: Sex | null;
  age: number | null;
  height_cm: number | null;
  weight_kg: number | null;
  activity: Activity | null;
  goal: Goal | null;
  tz: string;
  goal_kcal: number | null;
  goal_prot: number | null;
  goal_fat: number | null;
  goal_carb: number | null;
  onboarded_at: string | null;
}

export async function getUserByTgId(db: D1Database, tgId: number): Promise<UserRow | null> {
  return db.prepare("SELECT * FROM users WHERE tg_id = ?").bind(tgId).first<UserRow>();
}

/** Создаёт пользователя, если его ещё нет; возвращает существующего/нового. */
export async function ensureUser(db: D1Database, tgId: number): Promise<UserRow> {
  const existing = await getUserByTgId(db, tgId);
  if (existing) return existing;

  await db
    .prepare("INSERT INTO users (tg_id, created_at) VALUES (?, ?)")
    .bind(tgId, nowIso())
    .run();

  const created = await getUserByTgId(db, tgId);
  if (!created) throw new Error("Failed to create user");
  return created;
}

export interface ProfileFields {
  sex: Sex;
  age: number;
  heightCm: number;
  weightKg: number;
  activity: Activity;
  goal: Goal;
}

/** Сохраняет профиль и рассчитанные цели, помечает онбординг завершённым. */
export async function completeOnboarding(
  db: D1Database,
  tgId: number,
  profile: ProfileFields,
  targets: MacroTargets,
): Promise<void> {
  await db
    .prepare(
      `UPDATE users SET
         sex = ?, age = ?, height_cm = ?, weight_kg = ?, activity = ?, goal = ?,
         goal_kcal = ?, goal_prot = ?, goal_fat = ?, goal_carb = ?,
         onboarded_at = ?
       WHERE tg_id = ?`,
    )
    .bind(
      profile.sex,
      profile.age,
      profile.heightCm,
      profile.weightKg,
      profile.activity,
      profile.goal,
      targets.kcal,
      targets.protein,
      targets.fat,
      targets.carb,
      nowIso(),
      tgId,
    )
    .run();
}

export async function logEvent(
  db: D1Database,
  userId: number | null,
  event: string,
  payload?: unknown,
): Promise<void> {
  await db
    .prepare("INSERT INTO analytics_events (user_id, ts, event, payload_json) VALUES (?, ?, ?, ?)")
    .bind(userId, nowIso(), event, payload ? JSON.stringify(payload) : null)
    .run();
}
