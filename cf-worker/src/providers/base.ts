import { Tender } from "../models";
import { TenderQuery } from "../filters";

/** Источник данных о тендерах. Новый источник = новая реализация. */
export interface TenderProvider {
  readonly name: string;
  search(query: TenderQuery): Promise<Tender[]>;
}
