/**
 * Утилиты времени. «День» дневника считается в таймзоне пользователя,
 * поэтому local_date вычисляем через Intl, а не по UTC-полуночи.
 */

/** Текущий момент в ISO (UTC). */
export function nowIso(): string {
  return new Date().toISOString();
}

/**
 * Локальная дата (YYYY-MM-DD) для заданного момента и таймзоны IANA.
 * Использует Intl — доступно в Workers-рантайме.
 */
export function localDate(tz: string, date: Date = new Date()): string {
  // en-CA даёт формат YYYY-MM-DD.
  try {
    return new Intl.DateTimeFormat("en-CA", { timeZone: tz }).format(date);
  } catch {
    // Некорректная таймзона — откатываемся на UTC.
    return new Intl.DateTimeFormat("en-CA", { timeZone: "UTC" }).format(date);
  }
}

/** Час суток (0..23) в таймзоне пользователя — для контекстных советов/чек-ина. */
export function localHour(tz: string, date: Date = new Date()): number {
  try {
    const h = new Intl.DateTimeFormat("en-GB", {
      timeZone: tz,
      hour: "2-digit",
      hour12: false,
    }).format(date);
    return parseInt(h, 10) % 24;
  } catch {
    return date.getUTCHours();
  }
}

export type DayPart = "morning" | "day" | "evening" | "night";

/** Часть суток по локальному часу (как в прототипе: matrix времени). */
export function dayPart(hour: number): DayPart {
  if (hour >= 7 && hour < 12) return "morning";
  if (hour >= 12 && hour < 18) return "day";
  if (hour >= 18 && hour < 23) return "evening";
  return "night";
}
