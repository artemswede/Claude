"""In-memory mock provider.

Lets you run the whole bot end-to-end (commands, filtering, formatting,
subscriptions) without any aggregator API key. The sample data deliberately
mixes ТБД tenders with noise (small pipes, unfinished tenders) so the filtering
logic is exercised realistically.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import List

from ..filters import TenderQuery, filter_tenders
from ..models import Money, Tender, TenderStatus
from .base import TenderProvider


def _sample_tenders() -> List[Tender]:
    now = datetime.now()
    return [
        Tender(
            id="EIS-0173100000124000123",
            registry_number="0173100000124000123",
            title="Поставка труб стальных большого диаметра 1420 мм для магистрального газопровода",
            okpd2="24.20.13.120",
            law="44-ФЗ",
            customer="ПАО «Газпром трансгаз»",
            customer_inn="7728262893",
            winner="АО «Выксунский металлургический завод»",
            winner_inn="5247004695",
            start_price=Money(1_250_000_000),
            final_price=Money(1_187_500_000),
            volume=18500,
            volume_unit="т",
            platform="ЭТП ГПБ",
            status=TenderStatus.COMPLETED,
            published_at=now - timedelta(days=21),
            result_date=now - timedelta(days=3),
            url="https://zakupki.gov.ru/epz/order/notice/ea44/view/common-info.html?regNumber=0173100000124000123",
        ),
        Tender(
            id="EIS-0348100000124000045",
            registry_number="0348100000124000045",
            title="Закупка ТБД 1020 мм с наружным антикоррозийным покрытием",
            okpd2="24.20.13.000",
            law="223-ФЗ",
            customer="ПАО «Транснефть»",
            customer_inn="7706061801",
            winner="АО «Челябинский трубопрокатный завод»",
            winner_inn="7449006730",
            start_price=Money(845_000_000),
            final_price=Money(802_750_000),
            volume=9200,
            volume_unit="т",
            platform="Сбербанк-АСТ",
            status=TenderStatus.COMPLETED,
            published_at=now - timedelta(days=14),
            result_date=now - timedelta(days=1),
            url="https://zakupki.gov.ru/epz/order/notice/ea44/view/common-info.html?regNumber=0348100000124000045",
        ),
        Tender(
            id="EIS-0102200001124000777",
            registry_number="0102200001124000777",
            title="Трубы стальные электросварные диаметром 720 мм",
            okpd2="24.20.13.110",
            law="44-ФЗ",
            customer="АО «Связьтранснефть»",
            winner="ПАО «ТМК»",
            winner_inn="7710373095",
            start_price=Money(312_400_000),
            final_price=Money(298_000_000),
            volume=4100,
            volume_unit="т",
            platform="РТС-тендер",
            status=TenderStatus.COMPLETED,
            published_at=now - timedelta(days=9),
            result_date=now - timedelta(days=2),
            url="https://zakupki.gov.ru/epz/order/notice/ea44/view/common-info.html?regNumber=0102200001124000777",
        ),
        # --- Noise below: should be filtered out ---
        Tender(
            id="EIS-NOISE-small",
            registry_number="0100000000124000999",
            title="Трубы полипропиленовые 25 мм для системы отопления офиса",
            okpd2="22.21.21.000",
            customer="ООО «Управляющая компания»",
            winner="ООО «СтройМаркет»",
            start_price=Money(120_000),
            final_price=Money(118_500),
            volume=500,
            volume_unit="м",
            platform="РТС-тендер",
            status=TenderStatus.COMPLETED,
            published_at=now - timedelta(days=5),
            result_date=now - timedelta(days=1),
        ),
        Tender(
            id="EIS-NOISE-pending",
            registry_number="0173100000124000456",
            title="Поставка труб большого диаметра 1220 мм (итоги не подведены)",
            okpd2="24.20.13.120",
            customer="ООО «Стройгазмонтаж»",
            winner=None,  # ещё нет победителя
            start_price=Money(560_000_000),
            volume=6800,
            volume_unit="т",
            platform="B2B-Center",
            status=TenderStatus.BIDDING,
            published_at=now - timedelta(days=2),
        ),
    ]


class MockProvider(TenderProvider):
    name = "mock"

    def __init__(self) -> None:
        self._data = _sample_tenders()

    async def search(self, query: TenderQuery) -> List[Tender]:
        # The mock returns the full set; the shared filter does the real work.
        return filter_tenders(self._data, query)
