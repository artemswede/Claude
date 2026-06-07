"""Provider abstraction.

A provider knows how to talk to one data source (a paid aggregator, the EIS,
a mock, ...) and return :class:`~bot.models.Tender` objects matching a
:class:`~bot.filters.TenderQuery`. Adding a new data source = implementing this
one method; nothing else in the app needs to change.
"""

from __future__ import annotations

import abc
from typing import List

from ..filters import TenderQuery
from ..models import Tender


class TenderProvider(abc.ABC):
    """Base class for all tender data sources."""

    name: str = "base"

    @abc.abstractmethod
    async def search(self, query: TenderQuery) -> List[Tender]:
        """Return tenders from the source that satisfy ``query``.

        Implementations should push as much of the filtering to the remote API
        as possible (keywords, OKPD2, date range), but they are NOT required to
        guarantee correctness — the caller always re-applies
        :func:`bot.filters.filter_tenders` locally as a safety net.
        """
        raise NotImplementedError

    async def close(self) -> None:
        """Release any resources (HTTP sessions, etc.). Optional override."""
        return None

    async def __aenter__(self) -> "TenderProvider":
        return self

    async def __aexit__(self, *exc) -> None:
        await self.close()
