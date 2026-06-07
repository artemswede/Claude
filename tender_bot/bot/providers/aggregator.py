"""Generic REST aggregator adapter.

This single class is meant to be pointed at a *paid* procurement aggregator
(Контур.Закупки, Тендерплан, Seldon, Synapse, ...). Because every aggregator
ships a different JSON schema, the field mapping is centralised in
:data:`DEFAULT_FIELD_MAP` and can be overridden without touching any logic:
just adjust the dotted paths to match your provider's response.

How it works
------------
1. Build an HTTP request from the :class:`~bot.filters.TenderQuery`
   (keywords, OKPD2, date range, page size) using :data:`REQUEST_PARAM_MAP`.
2. Send it with the configured auth header.
3. Walk the response, pull each record from ``RESULTS_PATH`` and map fields via
   :data:`DEFAULT_FIELD_MAP` into a :class:`~bot.models.Tender`.

If your aggregator needs a fundamentally different request shape (GraphQL,
SOAP, multi-step), subclass this and override :meth:`_build_request` /
:meth:`_parse_response` — the rest of the bot stays untouched.
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta
from typing import Any, List, Optional

from ..filters import TenderQuery, filter_tenders
from ..models import Money, Tender, TenderStatus
from .base import TenderProvider

logger = logging.getLogger(__name__)

try:  # aiohttp is only needed when actually using this provider
    import aiohttp
except ImportError:  # pragma: no cover - import guard
    aiohttp = None  # type: ignore


# Dotted paths into the aggregator's JSON record. Adjust to your provider.
DEFAULT_FIELD_MAP: dict[str, str] = {
    "id": "id",
    "registry_number": "regNumber",
    "title": "name",
    "okpd2": "okpd2.code",
    "law": "law",
    "customer": "customer.name",
    "customer_inn": "customer.inn",
    "winner": "result.winner.name",
    "winner_inn": "result.winner.inn",
    "start_price": "startPrice",
    "final_price": "result.contractPrice",
    "currency": "currency",
    "volume": "volume.value",
    "volume_unit": "volume.unit",
    "platform": "platform.name",
    "status": "status",
    "published_at": "publishDate",
    "result_date": "result.date",
    "url": "url",
}

# How query fields are translated into request query-string params.
REQUEST_PARAM_MAP: dict[str, str] = {
    "search": "q",            # free-text / keyword search
    "okpd2": "okpd2",         # OKPD2 codes (comma-joined)
    "date_from": "dateFrom",
    "limit": "limit",
    "only_completed": "status",
}

RESULTS_PATH = "items"  # where the array of records lives in the response


def _dig(obj: Any, path: str) -> Any:
    """Resolve a dotted ``path`` like ``result.winner.name`` against nested dicts."""
    current = obj
    for part in path.split("."):
        if isinstance(current, dict):
            current = current.get(part)
        else:
            return None
        if current is None:
            return None
    return current


def _parse_dt(value: Any) -> Optional[datetime]:
    if not value:
        return None
    if isinstance(value, datetime):
        return value
    text = str(value).strip()
    for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d", "%d.%m.%Y"):
        try:
            return datetime.strptime(text[: len(fmt) + 2], fmt)
        except ValueError:
            continue
    # Last resort: ISO 8601 with timezone
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None


def _parse_float(value: Any) -> Optional[float]:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    cleaned = str(value).replace(" ", "").replace(" ", "").replace(",", ".")
    try:
        return float(cleaned)
    except ValueError:
        return None


class AggregatorProvider(TenderProvider):
    name = "aggregator"

    def __init__(
        self,
        base_url: str,
        api_key: str,
        *,
        search_path: str = "/search",
        auth_header: str = "Authorization",
        auth_scheme: str = "Bearer",
        field_map: Optional[dict[str, str]] = None,
        request_param_map: Optional[dict[str, str]] = None,
        results_path: str = RESULTS_PATH,
        timeout_seconds: float = 30.0,
    ) -> None:
        if aiohttp is None:  # pragma: no cover
            raise RuntimeError("aiohttp is required for AggregatorProvider. `pip install aiohttp`.")
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.search_path = search_path if search_path.startswith("/") else f"/{search_path}"
        self.auth_header = auth_header
        self.auth_scheme = auth_scheme
        self.field_map = field_map or dict(DEFAULT_FIELD_MAP)
        self.request_param_map = request_param_map or dict(REQUEST_PARAM_MAP)
        self.results_path = results_path
        self._timeout = aiohttp.ClientTimeout(total=timeout_seconds)
        self._session: Optional["aiohttp.ClientSession"] = None

    async def _ensure_session(self) -> "aiohttp.ClientSession":
        if self._session is None or self._session.closed:
            auth_value = f"{self.auth_scheme} {self.api_key}".strip() if self.auth_scheme else self.api_key
            self._session = aiohttp.ClientSession(
                headers={self.auth_header: auth_value, "Accept": "application/json"},
                timeout=self._timeout,
            )
        return self._session

    def _build_request(self, query: TenderQuery) -> tuple[str, dict[str, Any]]:
        keyword_text = query.free_text or " ".join(query.keywords)
        date_from = (datetime.now() - timedelta(days=query.lookback_days)).strftime("%Y-%m-%d")
        params: dict[str, Any] = {
            self.request_param_map["search"]: keyword_text,
            self.request_param_map["okpd2"]: ",".join(query.okpd2_prefixes),
            self.request_param_map["date_from"]: date_from,
            self.request_param_map["limit"]: query.limit,
        }
        if query.only_with_winner:
            params[self.request_param_map["only_completed"]] = "completed"
        url = f"{self.base_url}{self.search_path}"
        return url, params

    def _map_record(self, record: dict[str, Any]) -> Tender:
        fm = self.field_map
        currency = _dig(record, fm.get("currency", "currency")) or "RUB"

        def money(path_key: str) -> Optional[Money]:
            amount = _parse_float(_dig(record, fm.get(path_key, path_key)))
            return Money(amount, currency) if amount is not None else None

        tender_id = _dig(record, fm["id"])
        return Tender(
            id=str(tender_id) if tender_id is not None else "",
            registry_number=_dig(record, fm.get("registry_number", "")),
            title=_dig(record, fm.get("title", "title")) or "",
            okpd2=_dig(record, fm.get("okpd2", "")),
            law=_dig(record, fm.get("law", "")),
            customer=_dig(record, fm.get("customer", "")),
            customer_inn=_dig(record, fm.get("customer_inn", "")),
            winner=_dig(record, fm.get("winner", "")),
            winner_inn=_dig(record, fm.get("winner_inn", "")),
            start_price=money("start_price"),
            final_price=money("final_price"),
            volume=_parse_float(_dig(record, fm.get("volume", ""))),
            volume_unit=_dig(record, fm.get("volume_unit", "")),
            platform=_dig(record, fm.get("platform", "")),
            status=TenderStatus.from_raw(_dig(record, fm.get("status", ""))),
            published_at=_parse_dt(_dig(record, fm.get("published_at", ""))),
            result_date=_parse_dt(_dig(record, fm.get("result_date", ""))),
            url=_dig(record, fm.get("url", "")),
            extra={"_raw": record},
        )

    def _parse_response(self, payload: Any) -> List[Tender]:
        records = _dig(payload, self.results_path) if isinstance(payload, dict) else payload
        if records is None and isinstance(payload, list):
            records = payload
        if not isinstance(records, list):
            logger.warning("Aggregator response had no list at '%s'", self.results_path)
            return []
        out: List[Tender] = []
        for rec in records:
            if isinstance(rec, dict):
                try:
                    out.append(self._map_record(rec))
                except Exception:  # noqa: BLE001 - never let one bad record kill the batch
                    logger.exception("Failed to map a tender record")
        return out

    async def search(self, query: TenderQuery) -> List[Tender]:
        session = await self._ensure_session()
        url, params = self._build_request(query)
        try:
            async with session.get(url, params=params) as resp:
                resp.raise_for_status()
                payload = await resp.json(content_type=None)
        except Exception:  # noqa: BLE001
            logger.exception("Aggregator request failed: %s params=%s", url, params)
            return []
        tenders = self._parse_response(payload)
        # Re-apply local filtering as a safety net (remote filters are best-effort).
        return filter_tenders(tenders, query)

    async def close(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()
