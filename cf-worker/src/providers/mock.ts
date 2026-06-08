import { Tender } from "../models";
import { TenderQuery, filterTenders } from "../filters";
import { TenderProvider, SearchOptions } from "./base";

function daysAgo(days: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString();
}

function sampleTenders(): Tender[] {
  return [
    {
      id: "EIS-0173100000124000123",
      registryNumber: "0173100000124000123",
      title:
        "Поставка труб стальных большого диаметра 1420 мм для магистрального газопровода",
      okpd2: "24.20.13.120",
      law: "44-ФЗ",
      customer: "ПАО «Газпром трансгаз»",
      customerInn: "7728262893",
      winner: "АО «Выксунский металлургический завод»",
      winnerInn: "5247004695",
      startPrice: { amount: 1_250_000_000, currency: "RUB" },
      finalPrice: { amount: 1_187_500_000, currency: "RUB" },
      volume: 18500,
      volumeUnit: "т",
      platform: "ЭТП ГПБ",
      status: "completed",
      publishedAt: daysAgo(21),
      resultDate: daysAgo(3),
      url: "https://zakupki.gov.ru/epz/order/notice/ea44/view/common-info.html?regNumber=0173100000124000123",
    },
    {
      id: "EIS-0348100000124000045",
      registryNumber: "0348100000124000045",
      title: "Закупка ТБД 1020 мм с наружным антикоррозийным покрытием",
      okpd2: "24.20.13.000",
      law: "223-ФЗ",
      customer: "ПАО «Транснефть»",
      customerInn: "7706061801",
      winner: "АО «Челябинский трубопрокатный завод»",
      winnerInn: "7449006730",
      startPrice: { amount: 845_000_000, currency: "RUB" },
      finalPrice: { amount: 802_750_000, currency: "RUB" },
      volume: 9200,
      volumeUnit: "т",
      platform: "Сбербанк-АСТ",
      status: "completed",
      publishedAt: daysAgo(14),
      resultDate: daysAgo(1),
      url: "https://zakupki.gov.ru/epz/order/notice/ea44/view/common-info.html?regNumber=0348100000124000045",
    },
    {
      id: "EIS-0102200001124000777",
      registryNumber: "0102200001124000777",
      title: "Трубы стальные электросварные диаметром 720 мм",
      okpd2: "24.20.13.110",
      law: "44-ФЗ",
      customer: "АО «Связьтранснефть»",
      winner: "ПАО «ТМК»",
      winnerInn: "7710373095",
      startPrice: { amount: 312_400_000, currency: "RUB" },
      finalPrice: { amount: 298_000_000, currency: "RUB" },
      volume: 4100,
      volumeUnit: "т",
      platform: "РТС-тендер",
      status: "completed",
      publishedAt: daysAgo(9),
      resultDate: daysAgo(2),
      url: "https://zakupki.gov.ru/epz/order/notice/ea44/view/common-info.html?regNumber=0102200001124000777",
    },
    // --- Шум: должно отсеяться ---
    {
      id: "EIS-NOISE-small",
      registryNumber: "0100000000124000999",
      title: "Трубы полипропиленовые 25 мм для системы отопления офиса",
      okpd2: "22.21.21.000",
      customer: "ООО «Управляющая компания»",
      winner: "ООО «СтройМаркет»",
      startPrice: { amount: 120_000, currency: "RUB" },
      finalPrice: { amount: 118_500, currency: "RUB" },
      volume: 500,
      volumeUnit: "м",
      platform: "РТС-тендер",
      status: "completed",
      publishedAt: daysAgo(5),
      resultDate: daysAgo(1),
    },
    {
      id: "EIS-NOISE-pending",
      registryNumber: "0173100000124000456",
      title: "Поставка труб большого диаметра 1220 мм (итоги не подведены)",
      okpd2: "24.20.13.120",
      customer: "ООО «Стройгазмонтаж»",
      startPrice: { amount: 560_000_000, currency: "RUB" },
      volume: 6800,
      volumeUnit: "т",
      platform: "B2B-Center",
      status: "bidding",
      publishedAt: daysAgo(2),
    },
  ];
}

export class MockProvider implements TenderProvider {
  readonly name = "mock";
  private data = sampleTenders();

  async search(query: TenderQuery, _options?: SearchOptions): Promise<Tender[]> {
    return filterTenders(this.data, query);
  }
}
