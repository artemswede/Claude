#!/usr/bin/env python3
"""Offline simulator for the tender bot.

The sandbox blocks outbound network, so we can't run live long-polling against
Telegram. Instead this harness feeds fake messages through the *real* handler
functions and the *real* scheduler logic with the mock data provider, and prints
exactly what the bot would reply. Nothing here re-implements business logic — it
calls the same code paths as production.

Run:  python simulate.py
"""

from __future__ import annotations

import asyncio
import re
import tempfile
from dataclasses import dataclass, field
from typing import Optional

from bot.config import Config
from bot.filters import TenderQuery
from bot.handlers import commands as cmd
from bot.main import build_base_query
from bot.providers import build_provider
from bot.scheduler import SubscriptionScheduler
from bot.storage import Storage

# -----------------------------------------------------------------------------
# Tiny console rendering of Telegram HTML so the output is readable in a terminal
# -----------------------------------------------------------------------------
def render_html(text: str) -> str:
    text = re.sub(r"<a href=\"([^\"]+)\"[^>]*>(.*?)</a>", r"\2 (\1)", text, flags=re.DOTALL)
    text = re.sub(r"</?(b|i|code|pre)>", "", text)
    return text


def banner(title: str) -> None:
    print("\n" + "=" * 72)
    print(f"  {title}")
    print("=" * 72)


# -----------------------------------------------------------------------------
# Fake aiogram objects: just enough surface for the handlers we exercise.
# -----------------------------------------------------------------------------
@dataclass
class FakeChat:
    id: int


@dataclass
class FakeSent:
    """Represents a message the bot sent; supports edit_text like aiogram."""

    text: str

    async def edit_text(self, text: str, **_kw) -> "FakeSent":
        print("   ↻ (бот редактирует предыдущее сообщение)")
        print_bot(text)
        self.text = text
        return self


@dataclass
class FakeMessage:
    text: str
    chat: FakeChat
    sent: list = field(default_factory=list)

    async def answer(self, text: str, **_kw) -> FakeSent:
        print_bot(text)
        s = FakeSent(text)
        self.sent.append(s)
        return s


@dataclass
class FakeCommand:
    """Mimics aiogram CommandObject (.args = text after the command)."""

    args: Optional[str] = None


def print_user(text: str) -> None:
    print(f"\n👤 USER: {text}")


def print_bot(text: str) -> None:
    print("🤖 BOT:")
    for line in render_html(text).splitlines():
        print(f"   {line}")


# -----------------------------------------------------------------------------
# A fake Bot for the scheduler (it only calls bot.send_message).
# -----------------------------------------------------------------------------
class FakeBot:
    def __init__(self) -> None:
        self.outbox: list[tuple[int, str]] = []

    async def send_message(self, chat_id: int, text: str, **_kw) -> None:
        print(f"\n📨 PUSH → chat {chat_id}:")
        for line in render_html(text).splitlines():
            print(f"   {line}")
        self.outbox.append((chat_id, text))


# -----------------------------------------------------------------------------
async def main() -> None:
    # Force mock provider regardless of env.
    cfg = Config.from_env()
    cfg.provider = "mock"
    cfg.database_path = tempfile.mktemp(suffix=".sqlite3")

    provider = build_provider(cfg)
    storage = Storage(cfg.database_path)
    await storage.connect()
    base_query: TenderQuery = build_base_query(cfg)

    chat = FakeChat(id=777_001)

    banner("/start — приветствие и справка")
    print_user("/start")
    await cmd.cmd_start(FakeMessage("/start", chat))

    banner("/status — текущие настройки фильтра ТБД")
    print_user("/status")
    await cmd.cmd_status(FakeMessage("/status", chat), storage, base_query, provider)

    banner("/search — поиск проверенных тендеров на ТБД (все площадки)")
    print_user("/search")
    await cmd.cmd_search(FakeMessage("/search", chat), FakeCommand(args=None), provider, base_query)

    banner("/search 1420 — сужение поиска по фразе (диаметр 1420)")
    print_user("/search 1420")
    await cmd.cmd_search(FakeMessage("/search 1420", chat), FakeCommand(args="1420"), provider, base_query)

    banner("/search Транснефть — сужение по заказчику")
    print_user("/search Транснефть")
    await cmd.cmd_search(FakeMessage("/search Транснефть", chat), FakeCommand(args="Транснефть"), provider, base_query)

    banner("/subscribe — подписка на автоуведомления")
    print_user("/subscribe")
    await cmd.cmd_subscribe(FakeMessage("/subscribe", chat), storage)
    print_user("/subscribe (повторно — идемпотентно)")
    await cmd.cmd_subscribe(FakeMessage("/subscribe", chat), storage)

    # --- Background scheduler: first tick pushes everything new ---
    fake_bot = FakeBot()
    scheduler = SubscriptionScheduler(
        bot=fake_bot,  # type: ignore[arg-type]
        provider=provider,
        storage=storage,
        base_query=base_query,
        interval_seconds=1800,
    )

    banner("Фоновая рассылка — тик №1 (приходят новые тендеры)")
    await scheduler._tick()

    banner("Фоновая рассылка — тик №2 (дедупликация: ничего нового → тишина)")
    before = len(fake_bot.outbox)
    await scheduler._tick()
    after = len(fake_bot.outbox)
    if after == before:
        print("   ✓ Повторных уведомлений нет — дедупликация работает.")

    banner("/unsubscribe — отписка")
    print_user("/unsubscribe")
    await cmd.cmd_unsubscribe(FakeMessage("/unsubscribe", chat), storage)

    await provider.close()
    await storage.close()
    print("\n" + "=" * 72)
    print("  Симуляция завершена.")
    print("=" * 72)


if __name__ == "__main__":
    asyncio.run(main())
