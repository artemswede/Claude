import pytest

from bot.filters import TenderQuery
from bot.providers.mock import MockProvider


@pytest.mark.asyncio
async def test_mock_provider_returns_only_verified_tbd():
    provider = MockProvider()
    results = await provider.search(TenderQuery())

    # The two noise records (small pipe, pending) must be filtered out.
    ids = {t.id for t in results}
    assert "EIS-NOISE-small" not in ids
    assert "EIS-NOISE-pending" not in ids

    # Every returned tender is verified (completed + winner) and large-diameter.
    assert results, "expected at least one ТБД tender"
    for t in results:
        assert t.is_verified
        assert t.has_winner
        assert t.best_price is not None


@pytest.mark.asyncio
async def test_mock_provider_free_text_narrows():
    provider = MockProvider()
    results = await provider.search(TenderQuery().with_free_text("Транснефть"))
    assert all("транснефть" in (t.customer or "").lower() for t in results)
    assert results
