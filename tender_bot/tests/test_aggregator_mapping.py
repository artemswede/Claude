from bot.providers.aggregator import AggregatorProvider, _dig, _parse_dt, _parse_float
from bot.models import TenderStatus


def test_dig_nested():
    obj = {"result": {"winner": {"name": "АО ВМЗ", "inn": "123"}}}
    assert _dig(obj, "result.winner.name") == "АО ВМЗ"
    assert _dig(obj, "result.winner.inn") == "123"
    assert _dig(obj, "result.missing.x") is None


def test_parse_float_variants():
    assert _parse_float("1 250 000,50") == 1250000.50
    assert _parse_float(1000) == 1000.0
    assert _parse_float(None) is None
    assert _parse_float("n/a") is None


def test_parse_dt_variants():
    assert _parse_dt("2026-01-15").year == 2026
    assert _parse_dt("15.01.2026").day == 15
    assert _parse_dt("2026-01-15T10:30:00").hour == 10
    assert _parse_dt(None) is None


def test_map_record_uses_default_field_map():
    # Build provider without opening a network session (aiohttp import guarded).
    provider = AggregatorProvider.__new__(AggregatorProvider)
    provider.field_map = dict(__import__("bot.providers.aggregator", fromlist=["DEFAULT_FIELD_MAP"]).DEFAULT_FIELD_MAP)

    record = {
        "id": "abc",
        "regNumber": "0173100000124000123",
        "name": "Трубы большого диаметра 1420 мм",
        "okpd2": {"code": "24.20.13.120"},
        "law": "44-ФЗ",
        "customer": {"name": "ПАО Газпром", "inn": "7728262893"},
        "result": {
            "winner": {"name": "АО ВМЗ", "inn": "5247004695"},
            "contractPrice": "1 187 500 000",
            "date": "2026-01-10",
        },
        "startPrice": 1250000000,
        "currency": "RUB",
        "volume": {"value": 18500, "unit": "т"},
        "platform": {"name": "ЭТП ГПБ"},
        "status": "completed",
        "publishDate": "2025-12-20",
        "url": "https://example.com/t/abc",
    }
    t = provider._map_record(record)

    assert t.id == "abc"
    assert t.registry_number == "0173100000124000123"
    assert t.okpd2 == "24.20.13.120"
    assert t.winner == "АО ВМЗ"
    assert t.winner_inn == "5247004695"
    assert t.start_price.amount == 1250000000
    assert t.final_price.amount == 1187500000
    assert t.volume == 18500
    assert t.volume_unit == "т"
    assert t.platform == "ЭТП ГПБ"
    assert t.status is TenderStatus.COMPLETED
    assert t.is_verified
