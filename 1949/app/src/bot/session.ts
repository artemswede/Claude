import type { StorageAdapter } from "grammy";
import type { Activity, Goal, Sex } from "../domain/goals";

/** Шаги онбординга. */
export type OnboardingStep =
  | "sex"
  | "age"
  | "height"
  | "weight"
  | "activity"
  | "goal"
  | "done";

export interface OnboardingDraft {
  step: OnboardingStep;
  sex?: Sex;
  age?: number;
  heightCm?: number;
  weightKg?: number;
  activity?: Activity;
  goal?: Goal;
}

/** Распознанный приём, ожидающий подтверждения пользователем. */
export interface PendingFood {
  dish: string;
  portionG: number;
  kcal: number;
  protein: number;
  fat: number;
  carb: number;
  confidence: number;
  source: "photo" | "menu" | "label" | "manual";
  /** Какое поле сейчас редактируем (ждём текстовый ввод). */
  editing?: "portion" | "dish";
}

/** Данные сессии, хранятся в KV по ключу chat/user. */
export interface SessionData {
  onboarding?: OnboardingDraft;
  pendingFood?: PendingFood;
}

export function initialSession(): SessionData {
  return {};
}

/**
 * Storage adapter для grammY поверх Cloudflare KV.
 * Сессии короткоживущие — ставим TTL, чтобы мусор сам подчищался.
 */
export function kvStorage<T>(kv: KVNamespace, ttlSeconds = 60 * 60 * 24 * 7): StorageAdapter<T> {
  return {
    async read(key: string): Promise<T | undefined> {
      const raw = await kv.get(key);
      return raw ? (JSON.parse(raw) as T) : undefined;
    },
    async write(key: string, value: T): Promise<void> {
      await kv.put(key, JSON.stringify(value), { expirationTtl: ttlSeconds });
    },
    async delete(key: string): Promise<void> {
      await kv.delete(key);
    },
  };
}
