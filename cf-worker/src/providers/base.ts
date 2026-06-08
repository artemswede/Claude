import { Tender } from "../models";
import { TenderQuery } from "../filters";

/** Доп. параметры поиска. mode важен для Seldon: "new" — новые объекты
 *  (для фоновой рассылки), "update" — повторная выдача уже найденных (для /search). */
export interface SearchOptions {
  mode?: "new" | "update";
}

/** Источник данных о тендерах. Новый источник = новая реализация. */
export interface TenderProvider {
  readonly name: string;
  search(query: TenderQuery, options?: SearchOptions): Promise<Tender[]>;
}
