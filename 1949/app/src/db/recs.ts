/** Сохранение сформированных рекомендаций. */
import { nowIso } from "../util/time";

export interface RecRow {
  id: number;
  user_id: number;
  status: string;
  text: string;
  basket_json: string | null;
}

export async function getRecommendation(
  db: D1Database,
  id: number,
): Promise<RecRow | null> {
  return db
    .prepare("SELECT id, user_id, status, text, basket_json FROM recommendations WHERE id = ?")
    .bind(id)
    .first<RecRow>();
}

export async function addRecommendation(
  db: D1Database,
  userId: number,
  status: string,
  text: string,
  basketJson: string | null,
): Promise<number> {
  const res = await db
    .prepare(
      "INSERT INTO recommendations (user_id, ts, status, text, basket_json) VALUES (?, ?, ?, ?, ?)",
    )
    .bind(userId, nowIso(), status, text, basketJson)
    .run();
  return Number(res.meta.last_row_id);
}
