"""Background polling loop that powers subscriptions.

Every ``poll_interval_seconds`` it queries the provider, then for each
subscriber sends only the tenders they haven't seen yet (tracked in storage).
"""

from __future__ import annotations

import asyncio
import logging

from aiogram import Bot
from aiogram.exceptions import TelegramForbiddenError, TelegramRetryAfter

from .filters import TenderQuery, filter_tenders
from .formatting import format_results
from .providers.base import TenderProvider
from .storage import Storage

logger = logging.getLogger(__name__)


class SubscriptionScheduler:
    def __init__(
        self,
        bot: Bot,
        provider: TenderProvider,
        storage: Storage,
        base_query: TenderQuery,
        interval_seconds: int,
    ) -> None:
        self._bot = bot
        self._provider = provider
        self._storage = storage
        self._base_query = base_query
        self._interval = max(60, interval_seconds)
        self._task: asyncio.Task | None = None
        self._stop = asyncio.Event()

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._run(), name="subscription-scheduler")

    async def stop(self) -> None:
        self._stop.set()
        if self._task:
            await asyncio.gather(self._task, return_exceptions=True)
            self._task = None

    async def _run(self) -> None:
        logger.info("Scheduler started (interval=%ss)", self._interval)
        while not self._stop.is_set():
            try:
                await self._tick()
            except Exception:  # noqa: BLE001 - keep the loop alive
                logger.exception("Scheduler tick failed")
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=self._interval)
            except asyncio.TimeoutError:
                pass

    async def _tick(self) -> None:
        subscribers = await self._storage.list_subscribers()
        if not subscribers:
            return

        tenders = filter_tenders(await self._provider.search(self._base_query), self._base_query)
        if not tenders:
            return

        by_id = {t.id: t for t in tenders}
        for chat_id in subscribers:
            unseen_ids = await self._storage.filter_unseen(chat_id, by_id.keys())
            if not unseen_ids:
                continue
            fresh = [by_id[i] for i in unseen_ids]
            messages = format_results(fresh, header=f"🆕 Новые проверенные тендеры: {len(fresh)}")
            delivered = await self._deliver(chat_id, messages)
            if delivered:
                await self._storage.mark_seen(chat_id, unseen_ids)

    async def _deliver(self, chat_id: int, messages: list[str]) -> bool:
        try:
            for chunk in messages:
                await self._bot.send_message(chat_id, chunk, disable_web_page_preview=True)
            return True
        except TelegramRetryAfter as exc:
            await asyncio.sleep(exc.retry_after)
            return False
        except TelegramForbiddenError:
            # User blocked the bot — clean up their subscription.
            logger.info("Chat %s blocked the bot; removing subscription", chat_id)
            await self._storage.unsubscribe(chat_id)
            return False
        except Exception:  # noqa: BLE001
            logger.exception("Failed to deliver to chat %s", chat_id)
            return False
