/**
 * Формирование корзины продуктов под дефициты дня.
 * Это дифференциатор продукта: не абстрактный список, а товары,
 * закрывающие нехватку по макросам + рекомендованное блюдо.
 */
import type { AdviceResult, Macros } from "./advice";

export interface Basket {
  items: string[];
  /** Поисковый запрос для диплинка в магазин. */
  query: string;
  /** Короткое пояснение, почему такая корзина. */
  reason: string;
}

interface Gap {
  key: "prot" | "fat" | "carb";
  deficit: number; // насколько не добрали (в долях от цели)
}

const STAPLES: Record<Gap["key"], string> = {
  prot: "Куриная грудка или творог 5%",
  carb: "Гречка или бурый рис",
  fat: "Орехи (миндаль/грецкий) или авокадо",
};

/** Строит корзину: рекомендованное блюдо + 1–2 товара под крупнейшие дефициты. */
export function buildBasket(consumed: Macros, target: Macros, advice: AdviceResult): Basket {
  const gaps: Gap[] = (["prot", "fat", "carb"] as const)
    .map((key) => {
      const t = target[key];
      const c = consumed[key];
      const deficit = t > 0 ? (t - c) / t : 0;
      return { key, deficit };
    })
    .filter((g) => g.deficit > 0.15) // дефицит больше 15%
    .sort((a, b) => b.deficit - a.deficit);

  const items: string[] = [advice.recommendedProduct];
  for (const g of gaps.slice(0, 2)) {
    const staple = STAPLES[g.key];
    if (!items.includes(staple)) items.push(staple);
  }

  const reason =
    gaps.length > 0
      ? `Закрываем дефицит: ${gaps.slice(0, 2).map((g) => macroRu(g.key)).join(", ")}.`
      : "Поддерживаем баланс на сегодня.";

  // Запрос для поиска в магазине — из названий товаров (без граммовок/скобок).
  const query = items
    .map((i) => i.replace(/\(.*?\)/g, "").split(/[,/]|или/)[0].trim())
    .join(", ");

  return { items, query, reason };
}

function macroRu(key: Gap["key"]): string {
  return key === "prot" ? "белок" : key === "fat" ? "жиры" : "углеводы";
}
