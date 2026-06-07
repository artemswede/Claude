#!/usr/bin/env python3
"""Convenience launcher.

Loads variables from a local ``.env`` (if present) and starts the bot.
Equivalent to ``python -m bot.main`` but with .env auto-loading.
"""

from __future__ import annotations

import os
from pathlib import Path


def _load_dotenv(path: str = ".env") -> None:
    env_path = Path(path)
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip())


if __name__ == "__main__":
    _load_dotenv()
    from bot.main import main

    main()
