/**
 * Клиент Open Food Facts — открытая база пищевой ценности продуктов.
 * Используется как справочник КБЖУ: для упакованных продуктов даёт
 * реальные числа (на 100 г), которые точнее оценки модели.
 * Для готовых блюд совпадений обычно нет — тогда возвращаем null.
 *
 * Документация: https://openfoodfacts.github.io/openfoodfacts-server/api/
 * OFF просит указывать User-Agent с названием приложения.
 */

const UA = "Edondon/0.1 (Telegram nutrition bot)";
const SEARCH_URL = "https://world.openfoodfacts.org/cgi/search.pl";
const PRODUCT_URL = "https://world.openfoodfacts.org/api/v2/product";

/** КБЖУ на 100 г. */
export interface Per100 {
  kcal: number;
  protein: number;
  fat: number;
  carb: number;
  productName: string;
}

interface OffNutriments {
  "energy-kcal_100g"?: number;
  proteins_100g?: number;
  fat_100g?: number;
  carbohydrates_100g?: number;
}
interface OffProduct {
  product_name?: string;
  product_name_ru?: string;
  nutriments?: OffNutriments;
}

function toPer100(p: OffProduct): Per100 | null {
  const n = p.nutriments;
  const kcal = n?.["energy-kcal_100g"];
  // Требуем хотя бы калорийность и один макрос — иначе запись бесполезна.
  if (kcal == null || !Number.isFinite(kcal)) return null;
  return {
    kcal,
    protein: n?.proteins_100g ?? 0,
    fat: n?.fat_100g ?? 0,
    carb: n?.carbohydrates_100g ?? 0,
    productName: p.product_name_ru || p.product_name || "",
  };
}

/** Поиск продукта по названию. Возвращает КБЖУ на 100 г лучшего совпадения или null. */
export async function searchByName(name: string): Promise<Per100 | null> {
  const url =
    `${SEARCH_URL}?search_terms=${encodeURIComponent(name)}` +
    "&search_simple=1&action=process&json=1&page_size=5" +
    "&fields=product_name,product_name_ru,nutriments";
  try {
    const res = await fetch(url, { headers: { "User-Agent": UA } });
    if (!res.ok) return null;
    const data = (await res.json()) as { products?: OffProduct[] };
    for (const p of data.products ?? []) {
      const per100 = toPer100(p);
      if (per100) return per100;
    }
    return null;
  } catch {
    return null;
  }
}

/** Поиск по штрих-коду (для сканирования этикеток на будущее). */
export async function lookupBarcode(barcode: string): Promise<Per100 | null> {
  const url = `${PRODUCT_URL}/${encodeURIComponent(barcode)}.json?fields=product_name,product_name_ru,nutriments`;
  try {
    const res = await fetch(url, { headers: { "User-Agent": UA } });
    if (!res.ok) return null;
    const data = (await res.json()) as { status?: number; product?: OffProduct };
    if (data.status !== 1 || !data.product) return null;
    return toPer100(data.product);
  } catch {
    return null;
  }
}
