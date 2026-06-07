"""Domain models for the tender bot.

A ``Tender`` is the normalised representation of a procurement lot regardless
of which aggregator (Контур / Тендерплан / Seldon / ...) it came from. Every
provider is responsible for mapping its raw API payload into this shape so the
rest of the application only ever works with one structure.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Optional


class TenderStatus(str, Enum):
    """Lifecycle stage of a procurement.

    We only treat ``COMPLETED`` (results published, winner determined) as a
    "проверенный" / verified tender — that is what the bot reports on.
    """

    PUBLISHED = "published"        # подача заявок
    BIDDING = "bidding"            # идут торги
    COMPLETED = "completed"        # итоги подведены, есть победитель
    CANCELLED = "cancelled"        # закупка отменена / не состоялась
    UNKNOWN = "unknown"

    @classmethod
    def from_raw(cls, value: Optional[str]) -> "TenderStatus":
        if not value:
            return cls.UNKNOWN
        normalised = str(value).strip().lower()
        mapping = {
            "completed": cls.COMPLETED,
            "complete": cls.COMPLETED,
            "finished": cls.COMPLETED,
            "result": cls.COMPLETED,
            "results": cls.COMPLETED,
            "итоги": cls.COMPLETED,
            "завершен": cls.COMPLETED,
            "завершена": cls.COMPLETED,
            "завершено": cls.COMPLETED,
            "подведены итоги": cls.COMPLETED,
            "published": cls.PUBLISHED,
            "подача заявок": cls.PUBLISHED,
            "bidding": cls.BIDDING,
            "торги": cls.BIDDING,
            "cancelled": cls.CANCELLED,
            "canceled": cls.CANCELLED,
            "отменен": cls.CANCELLED,
            "не состоялась": cls.CANCELLED,
        }
        return mapping.get(normalised, cls.UNKNOWN)


@dataclass(slots=True)
class Money:
    """A monetary amount with a currency."""

    amount: float
    currency: str = "RUB"

    def __str__(self) -> str:
        return f"{self.amount:,.2f} {self.currency}".replace(",", " ")


@dataclass(slots=True)
class Tender:
    """Normalised procurement record."""

    # Identity
    id: str                                  # provider-unique id
    registry_number: Optional[str] = None    # реестровый номер (ЕИС)
    title: str = ""                          # наименование закупки

    # Classification
    okpd2: Optional[str] = None              # код ОКПД2
    law: Optional[str] = None                # 44-ФЗ / 223-ФЗ

    # Parties
    customer: Optional[str] = None           # заказчик
    customer_inn: Optional[str] = None
    winner: Optional[str] = None             # победитель
    winner_inn: Optional[str] = None

    # Commercials
    start_price: Optional[Money] = None      # НМЦК
    final_price: Optional[Money] = None      # итоговая цена контракта

    # Volume (объём): for ТБД usually tonnes or metres
    volume: Optional[float] = None
    volume_unit: Optional[str] = None        # "т", "м", "шт", ...

    # Where & when
    platform: Optional[str] = None           # ЭТП / площадка
    status: TenderStatus = TenderStatus.UNKNOWN
    published_at: Optional[datetime] = None
    result_date: Optional[datetime] = None
    url: Optional[str] = None

    # Anything provider-specific we want to keep around
    extra: dict = field(default_factory=dict)

    @property
    def has_winner(self) -> bool:
        return bool(self.winner)

    @property
    def is_verified(self) -> bool:
        """A "проверенный" tender = completed with a known winner."""
        return self.status is TenderStatus.COMPLETED and self.has_winner

    @property
    def best_price(self) -> Optional[Money]:
        """Final contract price if available, otherwise the start price."""
        return self.final_price or self.start_price
