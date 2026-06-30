/** Доступ к журналу самочувствия (state_log). */
import { nowIso } from "../util/time";

export async function addMoodCheckin(
  db: D1Database,
  userId: number,
  localDate: string,
  moodTag: string,
): Promise<void> {
  await db
    .prepare(
      "INSERT INTO state_log (user_id, ts, local_date, mood_tag) VALUES (?, ?, ?, ?)",
    )
    .bind(userId, nowIso(), localDate, moodTag)
    .run();
}

/** Последний ярлык состояния за день (для контекста советов). */
export async function getLatestMood(
  db: D1Database,
  userId: number,
  localDate: string,
): Promise<string | null> {
  const row = await db
    .prepare(
      `SELECT mood_tag FROM state_log
         WHERE user_id = ? AND local_date = ? AND mood_tag IS NOT NULL
         ORDER BY ts DESC LIMIT 1`,
    )
    .bind(userId, localDate)
    .first<{ mood_tag: string }>();
  return row?.mood_tag ?? null;
}
