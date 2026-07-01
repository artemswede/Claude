/**
 * Локальный справочник КБЖУ частых продуктов (на 100 г готового продукта).
 * Первый и самый надёжный источник при расчёте блюда по ингредиентам.
 * Значения — усреднённые, для готовых к употреблению продуктов
 * (крупы/макароны — в варёном виде).
 *
 * Матчинг по подстроке: ищем запись, чей ключ входит в название ингредиента.
 */
export interface Per100Macros {
  kcal: number;
  protein: number;
  fat: number;
  carb: number;
}

interface TableEntry {
  keys: string[]; // синонимы/подстроки в нижнем регистре
  per100: Per100Macros;
}

// Крупы/гарниры — в варёном виде; мясо — приготовленное.
const TABLE: TableEntry[] = [
  // Крупы и гарниры (варёные)
  { keys: ["греч"], per100: { kcal: 110, protein: 4, fat: 1.1, carb: 21 } },
  { keys: ["рис бур", "бурый рис"], per100: { kcal: 125, protein: 2.7, fat: 1, carb: 26 } },
  { keys: ["рис"], per100: { kcal: 130, protein: 2.4, fat: 0.3, carb: 28 } },
  { keys: ["овсянк", "овсян", "геркулес"], per100: { kcal: 90, protein: 3, fat: 1.7, carb: 15 } },
  { keys: ["макарон", "паста", "спагетти", "лапш", "вермишель"], per100: { kcal: 130, protein: 4.5, fat: 1, carb: 25 } },
  { keys: ["картоф", "пюре", "картошк"], per100: { kcal: 85, protein: 2, fat: 0.4, carb: 17 } },
  { keys: ["гречк"], per100: { kcal: 110, protein: 4, fat: 1.1, carb: 21 } },
  { keys: ["булгур"], per100: { kcal: 115, protein: 3, fat: 0.3, carb: 24 } },
  { keys: ["киноа", "кинва"], per100: { kcal: 120, protein: 4.4, fat: 1.9, carb: 21 } },
  { keys: ["перловк"], per100: { kcal: 105, protein: 2.3, fat: 0.4, carb: 22 } },
  { keys: ["кускус"], per100: { kcal: 112, protein: 3.8, fat: 0.2, carb: 23 } },
  // Мясо и птица (готовые)
  { keys: ["куриная грудк", "грудк", "курин"], per100: { kcal: 165, protein: 31, fat: 3.6, carb: 0 } },
  { keys: ["куриц", "кура"], per100: { kcal: 190, protein: 25, fat: 10, carb: 0 } },
  { keys: ["индейк", "индюш"], per100: { kcal: 140, protein: 29, fat: 2, carb: 0 } },
  { keys: ["говядин", "стейк"], per100: { kcal: 250, protein: 26, fat: 15, carb: 0 } },
  { keys: ["свинин"], per100: { kcal: 260, protein: 27, fat: 17, carb: 0 } },
  { keys: ["телятин"], per100: { kcal: 170, protein: 30, fat: 5, carb: 0 } },
  { keys: ["баранин"], per100: { kcal: 290, protein: 25, fat: 21, carb: 0 } },
  { keys: ["фарш"], per100: { kcal: 240, protein: 18, fat: 18, carb: 0 } },
  { keys: ["котлет"], per100: { kcal: 220, protein: 15, fat: 14, carb: 8 } },
  { keys: ["сосиск", "сардельк"], per100: { kcal: 270, protein: 11, fat: 24, carb: 2 } },
  { keys: ["колбас", "бекон", "ветчин", "салями"], per100: { kcal: 300, protein: 15, fat: 26, carb: 1 } },
  // Рыба и морепродукты
  { keys: ["лосос", "сёмг", "семг", "форел"], per100: { kcal: 200, protein: 22, fat: 12, carb: 0 } },
  { keys: ["треск", "минтай", "хек", "белая рыб"], per100: { kcal: 90, protein: 18, fat: 1.5, carb: 0 } },
  { keys: ["тунец", "тунц"], per100: { kcal: 130, protein: 28, fat: 1, carb: 0 } },
  { keys: ["креветк"], per100: { kcal: 95, protein: 20, fat: 1.5, carb: 0 } },
  { keys: ["рыб"], per100: { kcal: 140, protein: 20, fat: 6, carb: 0 } },
  // Яйца и молочное
  { keys: ["яйц", "яич", "омлет"], per100: { kcal: 155, protein: 13, fat: 11, carb: 1 } },
  { keys: ["творог 0", "творог обезжир"], per100: { kcal: 70, protein: 18, fat: 0.5, carb: 3.5 } },
  { keys: ["творог"], per100: { kcal: 120, protein: 16, fat: 5, carb: 3 } },
  { keys: ["греческий йогурт", "греч йогурт"], per100: { kcal: 60, protein: 10, fat: 0.4, carb: 3.6 } },
  { keys: ["йогурт"], per100: { kcal: 60, protein: 5, fat: 1.5, carb: 7 } },
  { keys: ["сыр"], per100: { kcal: 350, protein: 24, fat: 28, carb: 2 } },
  { keys: ["молоко"], per100: { kcal: 60, protein: 3, fat: 3.2, carb: 4.7 } },
  { keys: ["сметан"], per100: { kcal: 200, protein: 2.8, fat: 20, carb: 3 } },
  { keys: ["масло сливочн"], per100: { kcal: 720, protein: 0.5, fat: 80, carb: 1 } },
  // Овощи
  { keys: ["огурц", "огурец"], per100: { kcal: 15, protein: 0.8, fat: 0.1, carb: 3 } },
  { keys: ["помидор", "томат"], per100: { kcal: 20, protein: 1, fat: 0.2, carb: 4 } },
  { keys: ["капуст"], per100: { kcal: 28, protein: 1.8, fat: 0.1, carb: 5 } },
  { keys: ["морков"], per100: { kcal: 35, protein: 1.3, fat: 0.1, carb: 8 } },
  { keys: ["брокколи"], per100: { kcal: 34, protein: 2.8, fat: 0.4, carb: 7 } },
  { keys: ["салат", "зелен", "шпинат", "руккол"], per100: { kcal: 20, protein: 2, fat: 0.3, carb: 3 } },
  { keys: ["авокадо"], per100: { kcal: 160, protein: 2, fat: 15, carb: 9 } },
  { keys: ["овощ"], per100: { kcal: 40, protein: 2, fat: 0.3, carb: 8 } },
  // Хлеб/выпечка
  { keys: ["хлеб", "батон", "тост"], per100: { kcal: 250, protein: 8, fat: 3, carb: 48 } },
  { keys: ["булк", "булочк"], per100: { kcal: 300, protein: 8, fat: 6, carb: 55 } },
  // Орехи, масла, соусы
  { keys: ["миндал", "кешью", "грецк", "орех", "фундук"], per100: { kcal: 600, protein: 18, fat: 54, carb: 15 } },
  { keys: ["масло раст", "оливков масл", "подсолнечн масл", "растительн масл"], per100: { kcal: 890, protein: 0, fat: 99, carb: 0 } },
  { keys: ["майонез"], per100: { kcal: 620, protein: 1, fat: 67, carb: 2 } },
  // Фрукты
  { keys: ["банан"], per100: { kcal: 90, protein: 1.1, fat: 0.3, carb: 21 } },
  { keys: ["яблок"], per100: { kcal: 50, protein: 0.4, fat: 0.4, carb: 11 } },
  { keys: ["ягод", "клубник", "черник", "малин"], per100: { kcal: 45, protein: 1, fat: 0.4, carb: 9 } },
];

/** Поиск КБЖУ ингредиента в справочнике. Возвращает per-100 г или null. */
export function lookupTable(name: string): Per100Macros | null {
  const n = name.toLowerCase().trim();
  // Сначала более длинные ключи (специфичнее), чтобы «куриная грудка» победила «курицу».
  let best: { per100: Per100Macros; keyLen: number } | null = null;
  for (const entry of TABLE) {
    for (const key of entry.keys) {
      if (n.includes(key) && (!best || key.length > best.keyLen)) {
        best = { per100: entry.per100, keyLen: key.length };
      }
    }
  }
  return best?.per100 ?? null;
}
