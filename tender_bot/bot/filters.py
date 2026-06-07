"""Filtering logic that turns the broad procurement stream into the narrow set
of "проверенные тендеры на ТБД" (verified large-diameter-pipe tenders).

The same :class:`TenderQuery` is used both for on-demand ``/search`` and for the
background subscription polling, so the matching rules live in exactly one place.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Iterable, Optional, Sequence

from .models import Tender

# Diameters (мм) that are characteristic of ТБД pipelines. Used as a heuristic
# when the lot title mentions a size but no explicit "большого диаметра" phrase.
_TBD_DIAMETERS = (530, 630, 720, 820, 920, 1020, 1067, 1220, 1420)
_DIAMETER_RE = re.compile(r"(?:[øØ]|ду|dn|d=|диаметр(?:ом)?\s*)?\s*(\d{3,4})\s*(?:мм)?", re.IGNORECASE)


@dataclass(slots=True)
class TenderQuery:
    """A normalised search/subscription request."""

    keywords: Sequence[str] = field(default_factory=lambda: ("труб", "тбд", "большого диаметра"))
    okpd2_prefixes: Sequence[str] = field(default_factory=lambda: ("24.20",))
    min_price: Optional[float] = None
    max_price: Optional[float] = None
    only_with_winner: bool = True
    lookback_days: int = 7
    limit: int = 25
    free_text: Optional[str] = None  # extra user-supplied phrase from /search

    def with_free_text(self, text: Optional[str]) -> "TenderQuery":
        cleaned = (text or "").strip() or None
        return TenderQuery(
            keywords=self.keywords,
            okpd2_prefixes=self.okpd2_prefixes,
            min_price=self.min_price,
            max_price=self.max_price,
            only_with_winner=self.only_with_winner,
            lookback_days=self.lookback_days,
            limit=self.limit,
            free_text=cleaned,
        )


def _matches_keywords(text: str, keywords: Iterable[str]) -> bool:
    lowered = text.lower()
    return any(k.lower() in lowered for k in keywords if k)


def _matches_okpd2(code: Optional[str], prefixes: Iterable[str]) -> bool:
    if not code:
        return False
    return any(code.startswith(p) for p in prefixes if p)


def looks_like_large_diameter(text: str) -> bool:
    """Heuristic: does the lot title describe a large-diameter pipe?

    Returns True if it explicitly says "большого диаметра"/"ТБД" or mentions a
    diameter that is in the typical ТБД range (>= 530 мм).
    """
    lowered = text.lower()
    if "большого диаметра" in lowered or "тбд" in lowered:
        return True
    for match in _DIAMETER_RE.finditer(lowered):
        try:
            value = int(match.group(1))
        except (TypeError, ValueError):
            continue
        if value >= _TBD_DIAMETERS[0]:
            return True
    return False


def is_pipe_tender(tender: Tender, query: TenderQuery) -> bool:
    """Does this tender concern (large-diameter) pipes at all?

    A tender qualifies if its OKPD2 is in the steel-pipe family OR its title
    matches the pipe keywords. We additionally require it to *look* like a
    large-diameter lot so we don't flood the chat with small plumbing pipes.
    """
    title = tender.title or ""
    by_okpd2 = _matches_okpd2(tender.okpd2, query.okpd2_prefixes)
    by_keyword = _matches_keywords(title, query.keywords)
    if not (by_okpd2 or by_keyword):
        return False
    # If the only signal is the OKPD2 family, still demand a large-diameter hint
    # from the title; pure keyword "труба" alone is too noisy without it.
    return looks_like_large_diameter(title) or "тбд" in title.lower()


def matches(tender: Tender, query: TenderQuery) -> bool:
    """Full predicate combining subject, status and price constraints."""
    if not is_pipe_tender(tender, query):
        return False

    if query.only_with_winner and not tender.is_verified:
        return False

    if query.free_text and not _matches_keywords(
        f"{tender.title} {tender.customer or ''} {tender.winner or ''}",
        [query.free_text],
    ):
        return False

    price = tender.best_price
    if query.min_price is not None and (price is None or price.amount < query.min_price):
        return False
    if query.max_price is not None and (price is None or price.amount > query.max_price):
        return False

    return True


def filter_tenders(tenders: Iterable[Tender], query: TenderQuery) -> list[Tender]:
    """Apply :func:`matches` and cap the result at ``query.limit``."""
    result = [t for t in tenders if matches(t, query)]
    # Most recent results first (verified date, then published date).
    result.sort(
        key=lambda t: (t.result_date or t.published_at or _MIN_DT),
        reverse=True,
    )
    return result[: query.limit]


# Sentinel so sorting never blows up on missing dates.
from datetime import datetime as _datetime  # noqa: E402

_MIN_DT = _datetime.min
