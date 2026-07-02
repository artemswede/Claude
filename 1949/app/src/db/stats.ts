/** Агрегаты для личной статистики пользователя. */

/** Даты (local_date) с приёмами пищи, свежие сверху. */
export async function getLoggedDates(db: D1Database, userId: number, limit = 60): Promise<string[]> {
  const res = await db
    .prepare(
      "SELECT DISTINCT local_date FROM food_log WHERE user_id = ? ORDER BY local_date DESC LIMIT ?",
    )
    .bind(userId, limit)
    .all<{ local_date: string }>();
  return (res.results ?? []).map((r) => r.local_date);
}

export interface DailyKcal {
  local_date: string;
  kcal: number;
}

/** Сумма ккал по дням, свежие сверху. */
export async function getDailyKcal(db: D1Database, userId: number, limit = 7): Promise<DailyKcal[]> {
  const res = await db
    .prepare(
      `SELECT local_date, SUM(kcal) AS kcal FROM food_log
         WHERE user_id = ? GROUP BY local_date ORDER BY local_date DESC LIMIT ?`,
    )
    .bind(userId, limit)
    .all<DailyKcal>();
  return res.results ?? [];
}

export interface StateRow {
  ts: string;
  mood_tag: string;
}
export async function getStatesRecent(db: D1Database, userId: number, limit = 100): Promise<StateRow[]> {
  const res = await db
    .prepare(
      "SELECT ts, mood_tag FROM state_log WHERE user_id = ? AND mood_tag IS NOT NULL ORDER BY ts DESC LIMIT ?",
    )
    .bind(userId, limit)
    .all<StateRow>();
  return res.results ?? [];
}

export interface FoodTsRow {
  ts: string;
  dish_name: string;
  carb: number;
}
export async function getFoodsRecent(db: D1Database, userId: number, limit = 200): Promise<FoodTsRow[]> {
  const res = await db
    .prepare("SELECT ts, dish_name, carb FROM food_log WHERE user_id = ? ORDER BY ts DESC LIMIT ?")
    .bind(userId, limit)
    .all<FoodTsRow>();
  return res.results ?? [];
}

export async function countEvents(db: D1Database, userId: number, event: string): Promise<number> {
  const row = await db
    .prepare("SELECT COUNT(*) AS n FROM analytics_events WHERE user_id = ? AND event = ?")
    .bind(userId, event)
    .first<{ n: number }>();
  return row?.n ?? 0;
}
