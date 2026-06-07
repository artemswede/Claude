"""Render :class:`~bot.models.Tender` objects into Telegram (HTML) messages."""

from __future__ import annotations

from html import escape
from typing import Optional

from .models import Money, Tender


def _fmt_money(money: Optional[Money]) -> str:
    if money is None:
        return "—"
    return str(money)


def _fmt_volume(tender: Tender) -> str:
    if tender.volume is None:
        return "—"
    unit = tender.volume_unit or ""
    value = f"{tender.volume:,.0f}".replace(",", " ")
    return f"{value} {unit}".strip()


def _fmt_date(tender: Tender) -> str:
    dt = tender.result_date or tender.published_at
    return dt.strftime("%d.%m.%Y") if dt else "—"


def format_tender(tender: Tender) -> str:
    """Single-tender HTML card."""
    title = escape(tender.title or "Без названия")
    lines = [f"<b>📦 {title}</b>"]

    if tender.registry_number:
        lines.append(f"🔢 Реестр. №: <code>{escape(tender.registry_number)}</code>")

    lines.append(f"💰 Цена: <b>{escape(_fmt_money(tender.best_price))}</b>")
    if tender.final_price and tender.start_price and tender.final_price.amount != tender.start_price.amount:
        lines.append(f"   ↳ НМЦК: {escape(_fmt_money(tender.start_price))}")

    lines.append(f"📐 Объём: {escape(_fmt_volume(tender))}")

    if tender.winner:
        winner = escape(tender.winner)
        inn = f" (ИНН {escape(tender.winner_inn)})" if tender.winner_inn else ""
        lines.append(f"🏆 Победитель: <b>{winner}</b>{inn}")
    else:
        lines.append("🏆 Победитель: <i>не определён</i>")

    if tender.customer:
        lines.append(f"🏢 Заказчик: {escape(tender.customer)}")
    if tender.platform:
        lines.append(f"🌐 Площадка: {escape(tender.platform)}")
    if tender.okpd2:
        lines.append(f"🏷 ОКПД2: <code>{escape(tender.okpd2)}</code>")
    if tender.law:
        lines.append(f"⚖️ Закон: {escape(tender.law)}")

    lines.append(f"📅 Дата итогов: {escape(_fmt_date(tender))}")

    if tender.url:
        lines.append(f'🔗 <a href="{escape(tender.url, quote=True)}">Открыть в ЕИС</a>')

    return "\n".join(lines)


def format_results(tenders: list[Tender], header: Optional[str] = None) -> list[str]:
    """Render a list of tenders into one or more messages.

    Telegram limits a message to 4096 chars, so we pack several cards per
    message and split as needed. Returns a list of ready-to-send HTML strings.
    """
    if not tenders:
        return ["По заданному фильтру проверенных тендеров на ТБД не найдено."]

    cards = [format_tender(t) for t in tenders]
    messages: list[str] = []
    buffer: list[str] = []
    if header:
        buffer.append(f"<b>{escape(header)}</b>\n")

    current_len = sum(len(x) for x in buffer)
    for card in cards:
        block = card + "\n\n" + ("—" * 12) + "\n\n"
        if current_len + len(block) > 3800 and buffer:
            messages.append("".join(buffer).rstrip())
            buffer = []
            current_len = 0
        buffer.append(block)
        current_len += len(block)

    if buffer:
        messages.append("".join(buffer).rstrip())
    return messages
