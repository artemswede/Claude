/** Доступ к журналу веса (weight_log). */
import { nowIso } from "../util/time";

export interface WeightRow {
  ts: string;
  local_date: string;
  weight_kg: number;
}

export async function addWeight(
  db: D1Database,
  userId: number,
  localDate: string,
  weightKg: number,
): Promise<void> {
  await db
    .prepare("INSERT INTO weight_log (user_id, ts, local_date, weight_kg) VALUES (?, ?, ?, ?)")
    .bind(userId, nowIso(), localDate, weightKg)
    .run();
}

/** История веса, свежие сверху. */
export async function getWeightHistory(
  db: D1Database,
  userId: number,
  limit = 10,
): Promise<WeightRow[]> {
  const res = await db
    .prepare(
      "SELECT ts, local_date, weight_kg FROM weight_log WHERE user_id = ? ORDER BY ts DESC LIMIT ?",
    )
    .bind(userId, limit)
    .all<WeightRow>();
  return res.results ?? [];
}
