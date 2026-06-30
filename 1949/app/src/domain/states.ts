/**
 * Наборы быстрых состояний для чек-ина. Набор зависит от контекста
 * (ел сегодня или нет) — как в прототипе. Это «лёгкий» чек-ин в один тап;
 * подробные шкалы 1..5 — на будущее.
 */
export interface MoodOption {
  id: string; // стабильный key, пишется в state_log.mood_tag
  emoji: string;
  label: string;
}

/** Состояния натощак / в начале дня (ещё не ел). */
export const MOODS_EMPTY: MoodOption[] = [
  { id: "dull", emoji: "😶‍🌫️", label: "Туплю" },
  { id: "fresh", emoji: "⚡️", label: "Бодр" },
  { id: "full", emoji: "😌", label: "Сытый" },
];

/** Состояния после приёмов пищи. */
export const MOODS_AFTER: MoodOption[] = [
  { id: "sleepy", emoji: "🥱", label: "Тянет в сон" },
  { id: "lacking", emoji: "🤔", label: "Чего-то не хватает" },
  { id: "low_energy", emoji: "🔋", label: "Мало энергии" },
  { id: "hungry", emoji: "🤤", label: "Голоден" },
];

export function moodsFor(hasEaten: boolean): MoodOption[] {
  return hasEaten ? MOODS_AFTER : MOODS_EMPTY;
}

const ALL = [...MOODS_EMPTY, ...MOODS_AFTER];

export function moodById(id: string): MoodOption | undefined {
  return ALL.find((m) => m.id === id);
}
