"""Application configuration loaded from environment variables.

Copy ``.env.example`` to ``.env`` and fill in the values, or export the
variables in your shell / deployment environment.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Optional


def _get_bool(name: str, default: bool = False) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on", "да"}


def _get_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _get_float(name: str, default: Optional[float]) -> Optional[float]:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw)
    except ValueError:
        return default


@dataclass(slots=True)
class Config:
    # --- Telegram ---
    bot_token: str = ""

    # --- Data provider ---
    # Which provider to use: "mock" (built-in sample data, no key required) or
    # "aggregator" (generic REST adapter — configure it for Контур/Тендерплан/Seldon).
    provider: str = "mock"

    # Generic aggregator adapter settings (see providers/aggregator.py).
    aggregator_base_url: str = ""
    aggregator_api_key: str = ""
    aggregator_search_path: str = "/search"
    aggregator_auth_header: str = "Authorization"
    aggregator_auth_scheme: str = "Bearer"  # "" for raw key, e.g. "Bearer", "Token"

    # --- Default ТБД filter ---
    # OKPD2 prefixes that cover steel pipes (24.20 family). Comma-separated.
    okpd2_prefixes: tuple = ("24.20",)
    # Keywords that mark a lot as "трубы большого диаметра".
    keywords: tuple = ("труб", "тбд", "большого диаметра")
    min_price: Optional[float] = None
    max_price: Optional[float] = None
    only_with_winner: bool = True

    # --- Background polling / subscriptions ---
    poll_interval_seconds: int = 1800   # 30 минут
    poll_lookback_days: int = 7
    max_results_per_query: int = 25

    # --- Storage ---
    database_path: str = "tender_bot.sqlite3"

    # --- Misc ---
    log_level: str = "INFO"

    @classmethod
    def from_env(cls) -> "Config":
        def _csv(name: str, default: tuple) -> tuple:
            raw = os.getenv(name)
            if not raw:
                return default
            return tuple(p.strip() for p in raw.split(",") if p.strip())

        return cls(
            bot_token=os.getenv("BOT_TOKEN", ""),
            provider=os.getenv("PROVIDER", "mock").strip().lower(),
            aggregator_base_url=os.getenv("AGGREGATOR_BASE_URL", ""),
            aggregator_api_key=os.getenv("AGGREGATOR_API_KEY", ""),
            aggregator_search_path=os.getenv("AGGREGATOR_SEARCH_PATH", "/search"),
            aggregator_auth_header=os.getenv("AGGREGATOR_AUTH_HEADER", "Authorization"),
            aggregator_auth_scheme=os.getenv("AGGREGATOR_AUTH_SCHEME", "Bearer"),
            okpd2_prefixes=_csv("OKPD2_PREFIXES", ("24.20",)),
            keywords=tuple(k.lower() for k in _csv("KEYWORDS", ("труб", "тбд", "большого диаметра"))),
            min_price=_get_float("MIN_PRICE", None),
            max_price=_get_float("MAX_PRICE", None),
            only_with_winner=_get_bool("ONLY_WITH_WINNER", True),
            poll_interval_seconds=_get_int("POLL_INTERVAL_SECONDS", 1800),
            poll_lookback_days=_get_int("POLL_LOOKBACK_DAYS", 7),
            max_results_per_query=_get_int("MAX_RESULTS_PER_QUERY", 25),
            database_path=os.getenv("DATABASE_PATH", "tender_bot.sqlite3"),
            log_level=os.getenv("LOG_LEVEL", "INFO").upper(),
        )

    def validate(self) -> list[str]:
        """Return a list of human-readable configuration problems (empty = OK)."""
        problems: list[str] = []
        if not self.bot_token:
            problems.append("BOT_TOKEN не задан — получите токен у @BotFather.")
        if self.provider == "aggregator":
            if not self.aggregator_base_url:
                problems.append("AGGREGATOR_BASE_URL обязателен для provider=aggregator.")
            if not self.aggregator_api_key:
                problems.append("AGGREGATOR_API_KEY обязателен для provider=aggregator.")
        elif self.provider != "mock":
            problems.append(f"Неизвестный PROVIDER='{self.provider}' (ожидается 'mock' или 'aggregator').")
        return problems
