/**
 * Партнёрский магазин для заказа корзины.
 * В MVP — диплинк/поисковая ссылка (без реального API-заказа).
 * Конкретный партнёр и шаблон ссылки настраиваются через переменные окружения,
 * чтобы менять без правки кода.
 */
import type { Env } from "../env";

export interface PartnerLink {
  name: string;
  url: string;
}

const DEFAULT_NAME = "СберМаркет";
// {q} заменяется на поисковый запрос (URL-encoded).
const DEFAULT_TEMPLATE = "https://kuper.ru/search?q={q}";

export function buildPartnerLink(env: Env, query: string): PartnerLink {
  const name = env.PARTNER_NAME?.trim() || DEFAULT_NAME;
  const template = env.PARTNER_SEARCH_URL?.trim() || DEFAULT_TEMPLATE;
  const url = template.replace("{q}", encodeURIComponent(query));
  return { name, url };
}
