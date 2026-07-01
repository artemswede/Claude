/** Доступ к дневнику питания (food_log) и дневным итогам. */
import { nowIso } from "../util/time";

export interface FoodEntry {
  userId: number;
  localDate: string;
  dishName: string;
  kcal: number;
  prot: number;
  fat: number;
  carb: number;
  portionG: number | null;
  confidence: number | null;
  source: string; // 'photo'|'menu'|'label'|'manual'|'manual_edit'
}

export async function addFoodEntry(db: D1Database, e: FoodEntry): Promise<void> {
  await db
    .prepare(
      `INSERT INTO food_log
         (user_id, ts, local_date, dish_name, kcal, prot, fat, carb, portion_g, confidence, source)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
    .bind(
      e.userId,
      nowIso(),
      e.localDate,
      e.dishName,
      e.kcal,
      e.prot,
      e.fat,
      e.carb,
      e.portionG,
      e.confidence,
      e.source,
    )
    .run();
}

/** Удаляет последний (самый свежий) приём за день. Возвращает название или null. */
export async function deleteLastFoodEntry(
  db: D1Database,
  userId: number,
  localDate: string,
): Promise<string | null> {
  const row = await db
    .prepare(
      "SELECT id, dish_name FROM food_log WHERE user_id = ? AND local_date = ? ORDER BY ts DESC LIMIT 1",
    )
    .bind(userId, localDate)
    .first<{ id: number; dish_name: string }>();
  if (!row) return null;
  await db.prepare("DELETE FROM food_log WHERE id = ?").bind(row.id).run();
  return row.dish_name;
}

export interface DayTotals {
  kcal: number;
  prot: number;
  fat: number;
  carb: number;
  count: number;
}

export interface FoodRow {
  id: number;
  ts: string;
  dish_name: string;
  kcal: number;
  prot: number;
  fat: number;
  carb: number;
  portion_g: number | null;
  source: string;
}

/** Список приёмов за день, по времени (сначала свежие). */
export async function getDayEntries(
  db: D1Database,
  userId: number,
  localDate: string,
): Promise<FoodRow[]> {
  const res = await db
    .prepare(
      `SELECT id, ts, dish_name, kcal, prot, fat, carb, portion_g, source
         FROM food_log WHERE user_id = ? AND local_date = ?
         ORDER BY ts DESC`,
    )
    .bind(userId, localDate)
    .all<FoodRow>();
  return res.results ?? [];
}

export async function getDayTotals(
  db: D1Database,
  userId: number,
  localDate: string,
): Promise<DayTotals> {
  const row = await db
    .prepare(
      `SELECT
         COALESCE(SUM(kcal),0) AS kcal,
         COALESCE(SUM(prot),0) AS prot,
         COALESCE(SUM(fat),0)  AS fat,
         COALESCE(SUM(carb),0) AS carb,
         COUNT(*) AS count
       FROM food_log WHERE user_id = ? AND local_date = ?`,
    )
    .bind(userId, localDate)
    .first<DayTotals>();
  return row ?? { kcal: 0, prot: 0, fat: 0, carb: 0, count: 0 };
}
