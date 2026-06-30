/** Сохранение сформированных рекомендаций. */
import { nowIso } from "../util/time";

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
