/**
 * Режим ИМИТАЦИИ (временный, для предпросмотра формата сообщений).
 *
 * Генерирует правдоподобные «выигранные» тендеры на ТБД и рассылает их чатам,
 * включившим имитацию (/sim_on), по будням в рабочее время. Управляется
 * командами /sim_on, /sim_off, /sim. К реальному источнику отношения не имеет.
 */

import { Env } from "./config";
import { Tender } from "./models";
import { formatTender } from "./formatting";
import { Storage } from "./storage";
import { sendMessage } from "./telegram";

export const SIM_BANNER =
  "🧪 <b>ИМИТАЦИЯ</b> — пример уведомления о выигранном тендере (демо-данные)";

const WINNERS: { name: string; inn?: string }[] = [
  { name: "АО «Выксунский металлургический завод»", inn: "5247004695" },
  { name: "АО «Челябинский трубопрокатный завод»", inn: "7449006730" },
  { name: "ПАО «Трубная металлургическая компания»", inn: "7710373095" },
  { name: "АО «Загорский трубный завод»", inn: "5042108211" },
  { name: "ПАО «Северсталь»", inn: "3528000597" },
  { name: "АО «Ижорский трубный завод»" },
  { name: "АО «Уральский трубный завод»" },
];

const CUSTOMERS = [
  "ПАО «Газпром»",
  "ПАО «Транснефть»",
  "ПАО «НК «Роснефть»",
  "ПАО «Газпром нефть»",
  "ПАО «ЛУКОЙЛ»",
  "ПАО «НОВАТЭК»",
  "ПАО «Сургутнефтегаз»",
  "АО «Связьтранснефть»",
  "ООО «Газпром трансгаз Москва»",
];

const PLATFORMS = [
  "ЭТП ГПБ",
  "Сбербанк-АСТ",
  "РТС-тендер",
  "ЕЭТП (Росэлторг)",
  "ТЭК-Торг",
  "B2B-Center",
];

const DIAMETERS = [530, 630, 720, 820, 1020, 1220, 1420];

const DESCRIPTORS = [
  "с наружным трёхслойным антикоррозийным покрытием",
  "с внутренним гладкостным покрытием",
  "с наружным и внутренним защитным покрытием",
  "для магистрального газопровода",
  "для строительства нефтепровода",
  "класса прочности К60",
];

function pick<T>(arr: T[]): T {
  return arr[Math.floor(Math.random() * arr.length)];
}

function randInt(min: number, max: number): number {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function intEnv(value: string | undefined, fallback: number): number {
  const n = parseInt(value ?? "", 10);
  return isNaN(n) ? fallback : n;
}

export function generateSimulatedTender(): Tender {
  const diameter = pick(DIAMETERS);
  const descriptor = pick(DESCRIPTORS);
  const winner = pick(WINNERS);
  const volume = randInt(2, 25) * 1000 + randInt(0, 9) * 100; // тонн
  const pricePerTon = randInt(70_000, 110_000);
  const start = volume * pricePerTon;
  const final = Math.round(start * (0.9 + Math.random() * 0.09));
  const reg = `01731000001260${randInt(10000, 99999)}`;
  const now = new Date().toISOString();

  return {
    id: `SIM-${Date.now()}-${randInt(1000, 9999)}`,
    registryNumber: reg,
    title: `Поставка труб большого диаметра ${diameter} мм ${descriptor}`,
    okpd2: "24.20.13.120",
    law: "44-ФЗ",
    customer: pick(CUSTOMERS),
    winner: winner.name,
    winnerInn: winner.inn,
    startPrice: { amount: start, currency: "RUB" },
    finalPrice: { amount: final, currency: "RUB" },
    volume,
    volumeUnit: "т",
    platform: pick(PLATFORMS),
    status: "completed",
    publishedAt: now,
    resultDate: now,
    url: `https://zakupki.gov.ru/epz/order/notice/ea44/view/common-info.html?regNumber=${reg}`,
  };
}

export function simulatedMessage(): string {
  return `${SIM_BANNER}\n\n${formatTender(generateSimulatedTender())}`;
}

/** Сейчас рабочее время будня? (по московскому времени по умолчанию) */
function isWorkingTime(env: Env): boolean {
  const offsetHours = intEnv(env.SIM_TZ_OFFSET, 3); // МСК = UTC+3
  const local = new Date(Date.now() + offsetHours * 3600_000);
  const day = local.getUTCDay(); // 0=вс ... 6=сб
  if (day === 0 || day === 6) return false;
  const hour = local.getUTCHours();
  const start = intEnv(env.SIM_HOUR_START, 9);
  const end = intEnv(env.SIM_HOUR_END, 19);
  return hour >= start && hour < end;
}

/**
 * Вызывается из cron (каждые 30 мин). Чтобы интервал «гулял» в районе 30–60 мин,
 * часть тиков пропускаем случайно.
 */
export async function runSimulation(env: Env): Promise<void> {
  const storage = new Storage(env.TENDER_KV);
  const chats = await storage.listSimChats();
  if (chats.length === 0) return;
  if (!isWorkingTime(env)) return;

  const probability = parseFloat(env.SIM_PROBABILITY ?? "0.66");
  if (Math.random() > probability) return; // пропускаем тик → разброс интервала

  for (const chatId of chats) {
    try {
      await sendMessage(env.BOT_TOKEN, chatId, simulatedMessage());
    } catch (err) {
      console.error(`Имитация: доставка в чат ${chatId} не удалась`, err);
    }
  }
}
