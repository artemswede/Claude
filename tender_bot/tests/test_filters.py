from datetime import datetime

from bot.filters import (
    TenderQuery,
    filter_tenders,
    is_pipe_tender,
    looks_like_large_diameter,
    matches,
)
from bot.models import Money, Tender, TenderStatus


def _tender(**kwargs) -> Tender:
    base = dict(
        id="1",
        title="Поставка труб большого диаметра 1420 мм",
        okpd2="24.20.13.120",
        winner="АО ВМЗ",
        start_price=Money(1_000_000),
        final_price=Money(950_000),
        status=TenderStatus.COMPLETED,
        result_date=datetime(2026, 1, 1),
    )
    base.update(kwargs)
    return Tender(**base)


def test_looks_like_large_diameter():
    assert looks_like_large_diameter("Трубы большого диаметра")
    assert looks_like_large_diameter("ТБД для газопровода")
    assert looks_like_large_diameter("труба Ду 1020")
    assert looks_like_large_diameter("трубы 720 мм")
    assert not looks_like_large_diameter("трубы 25 мм для отопления")
    assert not looks_like_large_diameter("канцелярские товары")


def test_is_pipe_tender_accepts_tbd():
    q = TenderQuery()
    assert is_pipe_tender(_tender(), q)


def test_is_pipe_tender_rejects_small_pipe():
    q = TenderQuery()
    small = _tender(title="Трубы 25 мм полипропилен", okpd2="22.21.21.000")
    assert not is_pipe_tender(small, q)


def test_matches_requires_winner_when_only_with_winner():
    q = TenderQuery(only_with_winner=True)
    no_winner = _tender(winner=None, status=TenderStatus.BIDDING)
    assert not matches(no_winner, q)
    assert matches(_tender(), q)


def test_matches_price_bounds():
    q = TenderQuery(min_price=2_000_000)
    assert not matches(_tender(), q)  # 950k < 2M
    q2 = TenderQuery(max_price=500_000)
    assert not matches(_tender(), q2)
    q3 = TenderQuery(min_price=100_000, max_price=10_000_000)
    assert matches(_tender(), q3)


def test_free_text_filter():
    q = TenderQuery().with_free_text("Газпром")
    assert not matches(_tender(customer="ПАО Транснефть"), q)
    assert matches(_tender(customer="ПАО Газпром"), q)


def test_filter_tenders_limit_and_sort():
    items = [
        _tender(id="a", result_date=datetime(2026, 1, 1)),
        _tender(id="b", result_date=datetime(2026, 3, 1)),
        _tender(id="c", result_date=datetime(2026, 2, 1)),
    ]
    q = TenderQuery(limit=2)
    out = filter_tenders(items, q)
    assert len(out) == 2
    assert [t.id for t in out] == ["b", "c"]  # newest first
