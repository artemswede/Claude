"""SQLite-backed persistence for subscriptions and de-duplication.

Two tables:

* ``subscriptions(chat_id)`` — chats that opted into background notifications.
* ``seen(chat_id, tender_id)`` — tenders already pushed to a chat, so we never
  notify about the same procurement twice.
"""

from __future__ import annotations

from typing import Iterable, List

import aiosqlite


class Storage:
    def __init__(self, path: str) -> None:
        self._path = path
        self._db: aiosqlite.Connection | None = None

    async def connect(self) -> None:
        self._db = await aiosqlite.connect(self._path)
        await self._db.executescript(
            """
            CREATE TABLE IF NOT EXISTS subscriptions (
                chat_id    INTEGER PRIMARY KEY,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            CREATE TABLE IF NOT EXISTS seen (
                chat_id   INTEGER NOT NULL,
                tender_id TEXT    NOT NULL,
                seen_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (chat_id, tender_id)
            );
            """
        )
        await self._db.commit()

    async def close(self) -> None:
        if self._db is not None:
            await self._db.close()
            self._db = None

    @property
    def db(self) -> aiosqlite.Connection:
        if self._db is None:
            raise RuntimeError("Storage.connect() must be called before use.")
        return self._db

    # --- subscriptions ---
    async def subscribe(self, chat_id: int) -> bool:
        """Returns True if newly subscribed, False if already subscribed."""
        cur = await self.db.execute(
            "INSERT OR IGNORE INTO subscriptions (chat_id) VALUES (?)", (chat_id,)
        )
        await self.db.commit()
        return cur.rowcount > 0

    async def unsubscribe(self, chat_id: int) -> bool:
        cur = await self.db.execute(
            "DELETE FROM subscriptions WHERE chat_id = ?", (chat_id,)
        )
        await self.db.commit()
        return cur.rowcount > 0

    async def is_subscribed(self, chat_id: int) -> bool:
        async with self.db.execute(
            "SELECT 1 FROM subscriptions WHERE chat_id = ?", (chat_id,)
        ) as cur:
            return await cur.fetchone() is not None

    async def list_subscribers(self) -> List[int]:
        async with self.db.execute("SELECT chat_id FROM subscriptions") as cur:
            rows = await cur.fetchall()
        return [r[0] for r in rows]

    # --- de-duplication ---
    async def filter_unseen(self, chat_id: int, tender_ids: Iterable[str]) -> List[str]:
        """Return the subset of ``tender_ids`` not yet pushed to ``chat_id``."""
        ids = list(tender_ids)
        if not ids:
            return []
        placeholders = ",".join("?" for _ in ids)
        async with self.db.execute(
            f"SELECT tender_id FROM seen WHERE chat_id = ? AND tender_id IN ({placeholders})",
            (chat_id, *ids),
        ) as cur:
            seen = {row[0] for row in await cur.fetchall()}
        return [tid for tid in ids if tid not in seen]

    async def mark_seen(self, chat_id: int, tender_ids: Iterable[str]) -> None:
        rows = [(chat_id, tid) for tid in tender_ids]
        if not rows:
            return
        await self.db.executemany(
            "INSERT OR IGNORE INTO seen (chat_id, tender_id) VALUES (?, ?)", rows
        )
        await self.db.commit()
