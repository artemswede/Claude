"""Provider factory."""

from __future__ import annotations

from ..config import Config
from .aggregator import AggregatorProvider
from .base import TenderProvider
from .mock import MockProvider

__all__ = ["TenderProvider", "MockProvider", "AggregatorProvider", "build_provider"]


def build_provider(config: Config) -> TenderProvider:
    """Instantiate the provider selected in ``config``."""
    if config.provider == "aggregator":
        return AggregatorProvider(
            base_url=config.aggregator_base_url,
            api_key=config.aggregator_api_key,
            search_path=config.aggregator_search_path,
            auth_header=config.aggregator_auth_header,
            auth_scheme=config.aggregator_auth_scheme,
        )
    return MockProvider()
