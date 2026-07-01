import { InlineKeyboard } from "grammy";
import type { BotContext } from "./bot";
import type { Env } from "../env";
import {
  ACTIVITY_LABELS,
  GOAL_LABELS,
  SEX_LABELS,
  calcTargets,
  type Activity,
  type Goal,
  type Sex,
} from "../domain/goals";
import { completeOnboarding, ensureUser, logEvent } from "../db/repo";

const DISCLAIMER =
  "⚠️ Рекомендации ЕДОНДОНа — это оценочный ориентир по питанию, а не медицинский совет. " +
  "При проблемах со здоровьем консультируйтесь со специалистом.";

/** Запускает онбординг: создаёт пользователя и спрашивает пол. */
export async function startOnboarding(ctx: BotContext, env: Env): Promise<void> {
  if (ctx.from) await ensureUser(env.DB, ctx.from.id);
  ctx.session.onboarding = { step: "sex" };
  await ctx.reply(
    [
      "Давай настроим твои цели по питанию. Это займёт минуту. 🎯",
      "",
      DISCLAIMER,
    ].join("\n"),
  );
  await askSex(ctx);
}

async function askSex(ctx: BotContext): Promise<void> {
  const kb = new InlineKeyboard()
    .text(SEX_LABELS.male, "ob:sex:male")
    .text(SEX_LABELS.female, "ob:sex:female");
  await ctx.reply("Укажи пол:", { reply_markup: kb });
}

async function askActivity(ctx: BotContext): Promise<void> {
  const kb = new InlineKeyboard();
  (Object.keys(ACTIVITY_LABELS) as Activity[]).forEach((a) => {
    kb.text(ACTIVITY_LABELS[a], `ob:activity:${a}`).row();
  });
  await ctx.reply("Какой у тебя уровень активности?", { reply_markup: kb });
}

async function askGoal(ctx: BotContext): Promise<void> {
  const kb = new InlineKeyboard();
  (Object.keys(GOAL_LABELS) as Goal[]).forEach((g) => {
    kb.text(GOAL_LABELS[g], `ob:goal:${g}`).row();
  });
  await ctx.reply("Какая цель?", { reply_markup: kb });
}

/** Обработка inline-кнопок онбординга (sex/activity/goal). */
export async function handleOnboardingCallback(ctx: BotContext, env: Env): Promise<boolean> {
  const data = ctx.callbackQuery?.data;
  if (!data || !data.startsWith("ob:")) return false;

  const draft = ctx.session.onboarding;
  if (!draft) {
    await ctx.answerCallbackQuery();
    return true;
  }

  const [, field, value] = data.split(":");

  if (field === "sex" && draft.step === "sex") {
    draft.sex = value as Sex;
    draft.step = "age";
    await ctx.answerCallbackQuery();
    await ctx.reply("Сколько тебе лет? (числом)");
  } else if (field === "activity" && draft.step === "activity") {
    draft.activity = value as Activity;
    draft.step = "goal";
    await ctx.answerCallbackQuery();
    await askGoal(ctx);
  } else if (field === "goal" && draft.step === "goal") {
    draft.goal = value as Goal;
    await ctx.answerCallbackQuery();
    await finishOnboarding(ctx, env);
  } else {
    await ctx.answerCallbackQuery();
  }
  return true;
}

/** Обработка текстовых шагов онбординга (age/height/weight). Возвращает true, если ввод был для онбординга. */
export async function handleOnboardingText(ctx: BotContext): Promise<boolean> {
  const draft = ctx.session.onboarding;
  if (!draft) return false;
  const text = ctx.message?.text?.trim() ?? "";

  if (draft.step === "age") {
    const age = parseInt(text, 10);
    if (!Number.isFinite(age) || age < 10 || age > 100) {
      await ctx.reply("Введи возраст числом от 10 до 100.");
      return true;
    }
    draft.age = age;
    draft.step = "height";
    await ctx.reply("Какой у тебя рост в см? (например, 178)");
    return true;
  }

  if (draft.step === "height") {
    const h = parseFloat(text.replace(",", "."));
    if (!Number.isFinite(h) || h < 100 || h > 250) {
      await ctx.reply("Введи рост в см от 100 до 250.");
      return true;
    }
    draft.heightCm = h;
    draft.step = "weight";
    await ctx.reply("Текущий вес в кг? (например, 72.5)");
    return true;
  }

  if (draft.step === "weight") {
    const w = parseFloat(text.replace(",", "."));
    if (!Number.isFinite(w) || w < 30 || w > 300) {
      await ctx.reply("Введи вес в кг от 30 до 300.");
      return true;
    }
    draft.weightKg = w;
    draft.step = "activity";
    await askActivity(ctx);
    return true;
  }

  return false;
}

async function finishOnboarding(ctx: BotContext, env: Env): Promise<void> {
  const d = ctx.session.onboarding;
  if (!d || !d.sex || !d.age || !d.heightCm || !d.weightKg || !d.activity || !d.goal) {
    await ctx.reply("Что-то пошло не так с данными. Попробуй /start заново.");
    ctx.session.onboarding = undefined;
    return;
  }

  const profile = {
    sex: d.sex,
    age: d.age,
    heightCm: d.heightCm,
    weightKg: d.weightKg,
    activity: d.activity,
    goal: d.goal,
  };
  const targets = calcTargets(profile);

  if (ctx.from) {
    await completeOnboarding(env.DB, ctx.from.id, profile, targets);
    const user = await ensureUser(env.DB, ctx.from.id);
    await logEvent(env.DB, user.id, "onboarded", { goal: d.goal });
  }

  ctx.session.onboarding = undefined;

  await ctx.reply(
    [
      "Готово! Вот твои дневные цели: 🎯",
      "",
      `🔥 Калории: *${targets.kcal}* ккал`,
      `🥩 Белки: *${targets.protein}* г`,
      `🥑 Жиры: *${targets.fat}* г`,
      `🍚 Углеводы: *${targets.carb}* г`,
      "",
      "Дальше — пришли фото еды, и я посчитаю КБЖУ. 📸",
      "",
      DISCLAIMER,
    ].join("\n"),
    { parse_mode: "Markdown" },
  );
}
