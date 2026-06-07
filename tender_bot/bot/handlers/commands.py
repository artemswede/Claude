"""Command handlers: /start, /help, /search, /subscribe, /unsubscribe, /status.

Dependencies (provider, storage, base_query) are injected by aiogram from the
dispatcher's workflow data — see ``main.py`` where they are registered.
"""

from __future__ import annotations

import logging

from aiogram import Router
from aiogram.filters import Command, CommandObject
from aiogram.types import Message

from ..filters import TenderQuery, filter_tenders
from ..formatting import format_results
from ..providers.base import TenderProvider
from ..storage import Storage

logger = logging.getLogger(__name__)
router = Router(name="commands")


HELP_TEXT = (
    "<b>Бот мониторинга тендеров на трубы большого диаметра (ТБД)</b>\n\n"
    "Я нахожу <b>проверенные</b> (завершённые, с определённым победителем) "
    "закупки ТБД со всех площадок и показываю <b>цену, объём и победителя</b>.\n\n"
    "<b>Команды:</b>\n"
    "/search [фраза] — найти свежие проверенные тендеры. Доп. фраза сужает поиск, "
    "напр. <code>/search 1420</code> или <code>/search Газпром</code>.\n"
    "/subscribe — подписаться на автоуведомления о новых тендерах.\n"
    "/unsubscribe — отписаться.\n"
    "/status — текущие настройки фильтра и статус подписки.\n"
    "/help — эта справка."
)


@router.message(Command("start"))
async def cmd_start(message: Message) -> None:
    await message.answer(HELP_TEXT)


@router.message(Command("help"))
async def cmd_help(message: Message) -> None:
    await message.answer(HELP_TEXT)


@router.message(Command("search"))
async def cmd_search(
    message: Message,
    command: CommandObject,
    provider: TenderProvider,
    base_query: TenderQuery,
) -> None:
    query = base_query.with_free_text(command.args)
    note = await message.answer("🔎 Ищу проверенные тендеры на ТБД…")
    try:
        tenders = await provider.search(query)
    except Exception:  # noqa: BLE001
        logger.exception("Search failed")
        await note.edit_text("⚠️ Не удалось получить данные от источника. Попробуйте позже.")
        return

    # Defensive local filtering in case the provider didn't apply everything.
    tenders = filter_tenders(tenders, query)

    header = f"Найдено проверенных тендеров: {len(tenders)}"
    messages = format_results(tenders, header=header)

    await note.edit_text(messages[0], disable_web_page_preview=True)
    for chunk in messages[1:]:
        await message.answer(chunk, disable_web_page_preview=True)


@router.message(Command("subscribe"))
async def cmd_subscribe(message: Message, storage: Storage) -> None:
    created = await storage.subscribe(message.chat.id)
    if created:
        await message.answer(
            "✅ Вы подписаны. Буду присылать новые проверенные тендеры на ТБД по мере появления."
        )
    else:
        await message.answer("ℹ️ Вы уже подписаны.")


@router.message(Command("unsubscribe"))
async def cmd_unsubscribe(message: Message, storage: Storage) -> None:
    removed = await storage.unsubscribe(message.chat.id)
    if removed:
        await message.answer("🛑 Подписка отменена.")
    else:
        await message.answer("ℹ️ У вас не было активной подписки.")


@router.message(Command("status"))
async def cmd_status(
    message: Message,
    storage: Storage,
    base_query: TenderQuery,
    provider: TenderProvider,
) -> None:
    subscribed = await storage.is_subscribed(message.chat.id)
    price_range = []
    if base_query.min_price is not None:
        price_range.append(f"от {base_query.min_price:,.0f}".replace(",", " "))
    if base_query.max_price is not None:
        price_range.append(f"до {base_query.max_price:,.0f}".replace(",", " "))
    price_str = " ".join(price_range) + " ₽" if price_range else "без ограничений"

    text = (
        "<b>Текущие настройки</b>\n"
        f"Источник данных: <code>{provider.name}</code>\n"
        f"ОКПД2: <code>{', '.join(base_query.okpd2_prefixes)}</code>\n"
        f"Ключевые слова: <code>{', '.join(base_query.keywords)}</code>\n"
        f"Только с победителем: {'да' if base_query.only_with_winner else 'нет'}\n"
        f"Цена: {price_str}\n"
        f"Окно поиска: {base_query.lookback_days} дн.\n\n"
        f"Подписка на уведомления: {'активна ✅' if subscribed else 'не активна'}"
    )
    await message.answer(text)
