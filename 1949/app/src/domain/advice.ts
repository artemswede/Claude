/**
 * Движок советов нутрициолога.
 *
 * Состоит из детерминированной матрицы (время × состояние × макросы),
 * перенесённой из прототипа. Используется и как самостоятельный fallback
 * (когда ИИ недоступен/слаб), и как «скелет логики» для промпта ИИ.
 */
import type { DayPart } from "../util/time";
import type { Goal } from "./goals";

export type AdviceStatus = "success" | "warning" | "danger";

export interface AdviceResult {
  status: AdviceStatus;
  headerStatus: string;
  adviceText: string;
  /** Конкретный продукт/блюдо с порцией — основа будущей корзины. */
  recommendedProduct: string;
}

export interface Macros {
  kcal: number;
  prot: number;
  fat: number;
  carb: number;
}

export interface AdviceContext {
  dayPart: DayPart;
  mood: string | null; // id состояния (dull/fresh/full/sleepy/lacking/low_energy/hungry)
  consumed: Macros;
  target: Macros;
  goal: Goal | null; // цель пользователя (похудение/поддержание/набор)
  eaten: string[]; // что уже съедено сегодня (для разнообразия и контекста)
}

function pct(part: number, whole: number): number {
  return whole > 0 ? part / whole : 0;
}

/**
 * Детерминированный совет. Полностью покрывает все ветки —
 * всегда возвращает осмысленный результат.
 */
export function deterministicAdvice(ctx: AdviceContext): AdviceResult {
  const { dayPart, mood, consumed: c, target: t } = ctx;
  const pPct = pct(c.prot, t.prot);
  const fPct = pct(c.fat, t.fat);
  const cPct = pct(c.carb, t.carb);
  const calsLeft = t.kcal - c.kcal;

  // 4. Критические сценарии (Damage Control) — высший приоритет.
  if (dayPart === "night") {
    return {
      status: "danger",
      headerStatus: "Организм уже готовится ко сну",
      adviceText: "Лишняя еда сейчас — плохой сон. Лучше тёплый напиток без калорий.",
      recommendedProduct: "Травяной чай (ромашка или мята), 0 ккал.",
    };
  }
  if (fPct > 1.1) {
    return {
      status: "danger",
      headerStatus: "Жиры в красной зоне",
      adviceText: `Жиров сегодня уже с избытком (${Math.round(c.fat)}/${t.fat} г). Оставшиеся приёмы делаем постными.`,
      recommendedProduct: "Белая рыба на пару или куриная грудка (200 г) + овощи гриль.",
    };
  }
  if (dayPart === "evening" && pPct < 0.5) {
    return {
      status: "danger",
      headerStatus: "Критический недобор белка",
      adviceText: "Белка за день мало — закрой белковое окно, иначе завтра вялость.",
      recommendedProduct: "150 г творога 0% или порция изолята протеина.",
    };
  }

  // 1. Утро.
  if (dayPart === "morning") {
    if (mood === "dull" || c.kcal === 0) {
      return {
        status: "warning",
        headerStatus: "Запускаем метаболизм",
        adviceText: "Мозгу нужно топливо. Нужны сложные углеводы для долгой энергии.",
        recommendedProduct: "Овсянка с ягодами и орехами (40 г сухой крупы + 10 г миндаля).",
      };
    }
    if (mood === "fresh") {
      return {
        status: "success",
        headerStatus: "Отличный настрой!",
        adviceText: "Поддержим драйв белком, чтобы энергия не упала к полудню.",
        recommendedProduct: "Омлет из 2 яиц с авокадо (¼ авокадо).",
      };
    }
    if (mood === "full") {
      return {
        status: "success",
        headerStatus: "Лёгкий старт дня",
        adviceText: "После плотного вечера начнём легко, чтобы разгрузить ЖКТ.",
        recommendedProduct: "Зелёный смузи (шпинат + яблоко), 250 мл.",
      };
    }
  }

  // 2. День.
  if (dayPart === "day") {
    if (mood === "sleepy" || (cPct > 0.6 && pPct < 0.4)) {
      return {
        status: "warning",
        headerStatus: "Сахарный провал",
        adviceText: "Похоже на углеводную просадку. «Заземлимся» белком и клетчаткой.",
        recommendedProduct: "Творог 5% с семенами чиа (150 г).",
      };
    }
    if (mood === "lacking" || fPct < 0.3) {
      return {
        status: "warning",
        headerStatus: "Добавим полезных жиров",
        adviceText: "Не хватает жиров для гормонов — горсть орехов решает вопрос.",
        recommendedProduct: "Миндаль или кешью (30 г — одна горсть).",
      };
    }
    if (mood === "low_energy" || c.kcal < t.kcal * 0.4) {
      return {
        status: "warning",
        headerStatus: "Энергия на исходе",
        adviceText: "Обед был лёгким — есть риск сорваться к ужину. Добавим перекус.",
        recommendedProduct: "Протеиновый батончик без сахара или банан.",
      };
    }
  }

  // 3. Вечер.
  if (dayPart === "evening") {
    if (mood === "hungry" && calsLeft > 500) {
      return {
        status: "success",
        headerStatus: "Время плотного ужина",
        adviceText: "Есть запас по калориям — можно плотный, но правильный ужин.",
        recommendedProduct: "Стейк из индейки + бурый рис (150 г мяса, 100 г риса).",
      };
    }
    if (mood === "hungry" && calsLeft <= 100) {
      return {
        status: "danger",
        headerStatus: "Лимит почти исчерпан",
        adviceText: "Чтобы не лечь голодным — выберем объём без калорий.",
        recommendedProduct: "Большой салат из огурцов и зелени (300 г, лимонный сок).",
      };
    }
    if (mood === "sleepy" || cPct > 0.9) {
      return {
        status: "warning",
        headerStatus: "Углеводный перебор",
        adviceText: "Ужин был углеводным — чтобы утром не было отёков, добавь воды.",
        recommendedProduct: "Стакан воды с лимоном и короткая прогулка.",
      };
    }
  }

  // Базовый случай — с учётом цели и остатка калорий.
  const goal = ctx.goal;
  if (goal === "lose" && calsLeft < t.kcal * 0.2) {
    return {
      status: "warning",
      headerStatus: "Почти у лимита",
      adviceText: `Осталось ~${Math.max(0, Math.round(calsLeft))} ккал. Для похудения добери белком и овощами — они сытные при малых калориях.`,
      recommendedProduct: "Куриная грудка (150 г) + салат из овощей.",
    };
  }
  if (goal === "gain" && calsLeft > t.kcal * 0.3) {
    return {
      status: "warning",
      headerStatus: "Нужен профицит",
      adviceText: `Для набора осталось добрать ~${Math.round(calsLeft)} ккал. Добавь калорийный, но полезный приём.`,
      recommendedProduct: "Рис (150 г) + говядина (150 г) + ложка масла.",
    };
  }
  return {
    status: "success",
    headerStatus: "Ты в графике",
    adviceText: `Показатели в норме, осталось ~${Math.max(0, Math.round(calsLeft))} ккал. Продолжаем в том же духе!`,
    recommendedProduct: "Лёгкий перекус по желанию: греческий йогурт (150 г).",
  };
}
