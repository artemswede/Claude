"""Entry point: wires config, provider, storage, handlers and the scheduler."""

from __future__ import annotations

import asyncio
import logging

from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.client.session.aiohttp import AiohttpSession
from aiogram.enums import ParseMode

from .config import Config
from .filters import TenderQuery
from .handlers import router
from .providers import build_provider
from .scheduler import SubscriptionScheduler
from .storage import Storage


def build_base_query(config: Config) -> TenderQuery:
    return TenderQuery(
        keywords=config.keywords,
        okpd2_prefixes=config.okpd2_prefixes,
        min_price=config.min_price,
        max_price=config.max_price,
        only_with_winner=config.only_with_winner,
        lookback_days=config.poll_lookback_days,
        limit=config.max_results_per_query,
    )


async def run(config: Config) -> None:
    logging.basicConfig(
        level=getattr(logging, config.log_level, logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    log = logging.getLogger("tender_bot")

    problems = config.validate()
    if problems:
        for p in problems:
            log.error("Config: %s", p)
        raise SystemExit("Исправьте конфигурацию (см. ошибки выше).")

    provider = build_provider(config)
    storage = Storage(config.database_path)
    await storage.connect()

    base_query = build_base_query(config)

    # Optional proxy for reaching api.telegram.org (needed where Telegram is
    # blocked, e.g. some Russian datacenters). Supports http://, https:// and
    # socks5:// URLs (socks requires the aiohttp-socks package).
    session = AiohttpSession(proxy=config.telegram_proxy) if config.telegram_proxy else None
    if config.telegram_proxy:
        log.info("Соединение с Telegram через прокси: %s", config.telegram_proxy.split("@")[-1])

    bot = Bot(
        token=config.bot_token,
        session=session,
        default=DefaultBotProperties(parse_mode=ParseMode.HTML),
    )
    dp = Dispatcher()
    dp.include_router(router)

    # Dependency injection for handlers.
    dp["provider"] = provider
    dp["storage"] = storage
    dp["base_query"] = base_query

    scheduler = SubscriptionScheduler(
        bot=bot,
        provider=provider,
        storage=storage,
        base_query=base_query,
        interval_seconds=config.poll_interval_seconds,
    )
    scheduler.start()

    log.info("Бот запущен. Источник данных: %s", provider.name)
    try:
        await dp.start_polling(bot)
    finally:
        await scheduler.stop()
        await provider.close()
        await storage.close()
        await bot.session.close()


def main() -> None:
    asyncio.run(run(Config.from_env()))


if __name__ == "__main__":
    main()
