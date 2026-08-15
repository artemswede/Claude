#!/usr/bin/env python3
"""CLI для LinkedIn API.

    python3 linkedin/cli.py login
    python3 linkedin/cli.py whoami
    python3 linkedin/cli.py post --text "Привет, LinkedIn"

Коды возврата: 0 — успех, 1 — ошибка выполнения, 2 — ошибка конфигурации,
3 — нужен повторный вход.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Optional

try:  # запуск как пакет: python3 -m linkedin.cli
    from .linkedin_api import (
        DEFAULT_SCOPES,
        VISIBILITY_VALUES,
        ApiError,
        AuthError,
        Config,
        ConfigError,
        LinkedInClient,
        LinkedInError,
        RateLimitError,
        TokenStore,
        human_duration,
        login,
        normalize_author,
        redact,
    )
except ImportError:  # запуск как файл: python3 linkedin/cli.py
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from linkedin_api import (  # type: ignore[no-redef]
        DEFAULT_SCOPES,
        VISIBILITY_VALUES,
        ApiError,
        AuthError,
        Config,
        ConfigError,
        LinkedInClient,
        LinkedInError,
        RateLimitError,
        TokenStore,
        human_duration,
        login,
        normalize_author,
        redact,
    )

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_CONFIG = 2
EXIT_AUTH = 3

POST_URL_TEMPLATE = "https://www.linkedin.com/feed/update/{urn}/"


def _load_config(args: argparse.Namespace) -> Config:
    dotenv = Path(args.env).expanduser() if getattr(args, "env", None) else None
    return Config.from_env(dotenv)


def _read_text(args: argparse.Namespace) -> str:
    sources = [bool(args.text), bool(args.file)]
    if sum(sources) != 1:
        raise LinkedInError("укажите ровно одно из: --text или --file (--file - читает stdin)")
    if args.text:
        return args.text
    if args.file == "-":
        return sys.stdin.read()
    path = Path(args.file).expanduser()
    try:
        return path.read_text(encoding="utf-8-sig")
    except FileNotFoundError:
        raise LinkedInError(f"файл не найден: {path}") from None
    except UnicodeDecodeError as exc:
        raise LinkedInError(f"{path}: файл не в UTF-8 ({exc})") from None


# --------------------------------------------------------------------------- #
# Команды
# --------------------------------------------------------------------------- #


def cmd_login(args: argparse.Namespace) -> int:
    config = _load_config(args)
    store = TokenStore(config.token_path)

    existing = None
    try:
        existing = store.load()
    except AuthError:
        existing = None  # битый файл перезапишем
    if existing and not existing.is_expired and not args.force:
        print(
            f"Токен уже есть и действителен ещё {human_duration(existing.seconds_left)}. "
            "Перевыпустить: `login --force`."
        )
        return EXIT_OK

    scopes = tuple(args.scopes.split()) if args.scopes else config.scopes
    token = login(config, manual=args.manual, scopes=scopes, open_browser=not args.no_browser)
    store.save(token)

    granted = set(token.scopes)
    requested = set(scopes)

    client = LinkedInClient(config, token, store)
    if {"openid", "profile"} <= granted or not granted:
        try:
            info = client.userinfo()
            token.sub = info.get("sub") or token.sub
            token.name = info.get("name") or token.name
            token.email = info.get("email") or token.email
            store.save(token)
        except LinkedInError as exc:
            print(f"Предупреждение: не удалось прочитать профиль: {exc}", file=sys.stderr)

    print(f"\nВход выполнен. Токен сохранён: {store.path} (права 0600)")
    if token.name:
        print(f"Аккаунт: {token.name}" + (f" <{token.email}>" if token.email else ""))
    if token.sub:
        print(f"Person URN: urn:li:person:{token.sub}")
    print(f"Scopes: {' '.join(token.scopes) or '—'}")
    print(f"Действует: {human_duration(token.seconds_left)} (до {_fmt_ts(token.expires_at)})")

    missing = requested - granted
    if granted and missing:
        print(
            "\nВнимание: LinkedIn выдал не все запрошенные scope. Не выданы: "
            + " ".join(sorted(missing))
            + ".\nПроверьте вкладку Products вашего приложения — нужный продукт должен быть добавлен.",
            file=sys.stderr,
        )
    if not token.refresh_token:
        print(
            "\nrefresh_token не выдан (норма для self-serve приложений). "
            "Через 60 дней повторите `login`.",
        )
    return EXIT_OK


def cmd_whoami(args: argparse.Namespace) -> int:
    config = _load_config(args)
    client = LinkedInClient.from_store(config)
    info = client.userinfo()
    if args.json:
        print(json.dumps(info, ensure_ascii=False, indent=2))
        return EXIT_OK
    print(f"Имя:        {info.get('name') or '—'}")
    print(f"Email:      {info.get('email') or '— (нет scope email)'}")
    print(f"Person URN: urn:li:person:{info.get('sub')}")
    print(f"Локаль:     {json.dumps(info.get('locale'), ensure_ascii=False)}")
    return EXIT_OK


def cmd_token(args: argparse.Namespace) -> int:
    config = _load_config(args)
    store = TokenStore(config.token_path)
    token = store.load()
    if token is None:
        print(f"Токена нет ({store.path}). Выполните `login`.", file=sys.stderr)
        return EXIT_AUTH

    print(f"Файл:          {store.path}")
    print(f"access_token:  {redact(token.access_token)}")
    print(f"Выдан:         {_fmt_ts(token.obtained_at)}")
    print(f"Истекает:      {_fmt_ts(token.expires_at)} ({human_duration(token.seconds_left)})")
    print(f"Scopes:        {' '.join(token.scopes) or '—'}")
    print(f"refresh_token: {'есть' if token.refresh_token else 'нет (нужен повторный login)'}")
    if token.refresh_token_expires_at:
        print(f"  истекает:    {_fmt_ts(token.refresh_token_expires_at)}")

    if args.introspect:
        client = LinkedInClient(config, token, store)
        try:
            data = client.introspect()
            print("\nintrospectToken:")
            print(json.dumps(data, ensure_ascii=False, indent=2))
        except LinkedInError as exc:
            print(f"\nintrospectToken не сработал: {exc}", file=sys.stderr)
            return EXIT_ERROR
    return EXIT_OK


def cmd_post(args: argparse.Namespace) -> int:
    config = _load_config(args)
    text = _read_text(args)

    if args.dry_run:
        # Пробуем взять автора из сохранённого токена, но не требуем сети.
        author = args.author
        if not author:
            token = TokenStore(config.token_path).load()
            author = (
                config.person_urn_override
                or (f"urn:li:person:{token.sub}" if token and token.sub else "urn:li:person:UNKNOWN")
            )
        author = normalize_author(author)
        body = LinkedInClient.build_post_body(
            text,
            author=author,
            visibility=args.visibility,
            image_urn="urn:li:image:PLACEHOLDER" if args.image else None,
            alt_text=args.alt,
            allow_reshare=not args.no_reshare,
        )
        print("POST https://api.linkedin.com/rest/posts")
        print(f"LinkedIn-Version: {config.api_version}")
        print(json.dumps(body, ensure_ascii=False, indent=2))
        return EXIT_OK

    client = LinkedInClient.from_store(config)

    if sys.stdin.isatty() and not args.yes:
        preview = text.strip()
        if len(preview) > 400:
            preview = preview[:400] + "…"
        print("--- Будет опубликовано ---")
        print(preview)
        print("--------------------------")
        answer = input("Публиковать? [y/N] ").strip().lower()
        if answer not in ("y", "yes", "д", "да"):
            print("Отменено.")
            return EXIT_OK

    urn = client.create_post(
        text,
        author=args.author,
        visibility=args.visibility,
        image_path=args.image,
        alt_text=args.alt,
        allow_reshare=not args.no_reshare,
        allow_legacy_fallback=not args.no_legacy_fallback,
    )
    print(f"Опубликовано: {urn}")
    print(POST_URL_TEMPLATE.format(urn=urn))
    return EXIT_OK


def cmd_delete(args: argparse.Namespace) -> int:
    config = _load_config(args)
    client = LinkedInClient.from_store(config)
    client.delete_post(args.urn)
    print(f"Удалено: {args.urn}")
    return EXIT_OK


def cmd_logout(args: argparse.Namespace) -> int:
    config = _load_config(args)
    store = TokenStore(config.token_path)
    if store.delete():
        print(f"Токен удалён: {store.path}")
        print(
            "Чтобы отозвать доступ на стороне LinkedIn: "
            "Settings → Data privacy → Permitted services."
        )
    else:
        print("Токена и так не было.")
    return EXIT_OK


def cmd_doctor(args: argparse.Namespace) -> int:
    problems = 0
    try:
        config = _load_config(args)
    except ConfigError as exc:
        print(f"[FAIL] конфигурация: {exc}")
        return EXIT_CONFIG

    print(f"[ok]   client_id:     {redact(config.client_id)}")
    print(f"[ok]   client_secret: {redact(config.client_secret)}")
    print(f"[ok]   redirect_uri:  {config.redirect_uri}")
    print(f"[ok]   API version:   {config.api_version}")
    print(f"[ok]   token_path:    {config.token_path}")

    store = TokenStore(config.token_path)
    try:
        token = store.load()
    except AuthError as exc:
        print(f"[FAIL] токен: {exc}")
        return EXIT_AUTH
    if token is None:
        print("[FAIL] токен: отсутствует. Выполните `login`.")
        return EXIT_AUTH

    mode = store.path.stat().st_mode & 0o777
    if mode & 0o077:
        print(f"[WARN] права на файл токена {oct(mode)} — рекомендуется 600")
        problems += 1
    else:
        print(f"[ok]   права файла токена: {oct(mode)}")

    if token.is_expired:
        print(f"[FAIL] токен истёк ({human_duration(token.seconds_left)}). Выполните `login`.")
        return EXIT_AUTH
    print(f"[ok]   токен действует ещё {human_duration(token.seconds_left)}")
    if token.seconds_left < 7 * 86400:
        print("[WARN] осталось меньше недели — запланируйте повторный `login`")
        problems += 1

    granted = set(token.scopes)
    if granted:
        for scope in ("openid", "profile", "w_member_social"):
            if scope in granted:
                print(f"[ok]   scope {scope}")
            else:
                print(f"[WARN] нет scope {scope}")
                problems += 1
    else:
        print("[WARN] LinkedIn не вернул список scope в ответе токена")

    try:
        client = LinkedInClient(config, token, store)
        info = client.userinfo()
        print(f"[ok]   /v2/userinfo: {info.get('name')} (sub={info.get('sub')})")
    except LinkedInError as exc:
        print(f"[FAIL] /v2/userinfo: {exc}")
        problems += 1

    if problems:
        print(f"\nНайдено замечаний: {problems}")
        return EXIT_ERROR
    print("\nВсё в порядке.")
    return EXIT_OK


def _fmt_ts(value: float) -> str:
    if not value:
        return "—"
    return time.strftime("%Y-%m-%d %H:%M:%S %Z", time.localtime(value))


# --------------------------------------------------------------------------- #
# Разбор аргументов
# --------------------------------------------------------------------------- #


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="linkedin",
        description="Работа с LinkedIn через официальный API (OAuth 2.0 + Posts API).",
    )
    parser.add_argument("--env", help="путь к .env (по умолчанию linkedin/.env)")
    sub = parser.add_subparsers(dest="command", required=True)

    p_login = sub.add_parser("login", help="пройти OAuth и сохранить токен")
    p_login.add_argument("--manual", action="store_true",
                         help="не поднимать локальный сервер, вставить redirect URL руками")
    p_login.add_argument("--no-browser", action="store_true", help="не открывать браузер автоматически")
    p_login.add_argument("--force", action="store_true", help="перевыпустить токен, даже если текущий жив")
    p_login.add_argument("--scopes", help=f"scopes через пробел (по умолчанию: {' '.join(DEFAULT_SCOPES)})")
    p_login.set_defaults(func=cmd_login)

    p_who = sub.add_parser("whoami", help="показать профиль по текущему токену")
    p_who.add_argument("--json", action="store_true", help="сырой ответ /v2/userinfo")
    p_who.set_defaults(func=cmd_whoami)

    p_token = sub.add_parser("token", help="состояние токена")
    p_token.add_argument("--introspect", action="store_true", help="спросить статус у LinkedIn")
    p_token.set_defaults(func=cmd_token)

    p_post = sub.add_parser("post", help="опубликовать пост")
    p_post.add_argument("--text", help="текст поста")
    p_post.add_argument("--file", help="файл с текстом ('-' = stdin)")
    p_post.add_argument("--image", help="путь к изображению (png/jpg, до 10 МБ)")
    p_post.add_argument("--alt", default="", help="alt-текст изображения")
    p_post.add_argument("--visibility", default="PUBLIC", choices=VISIBILITY_VALUES)
    p_post.add_argument("--author", help="urn:li:person:XXX или org:12345 (страница компании)")
    p_post.add_argument("--no-reshare", action="store_true", help="запретить репосты")
    p_post.add_argument("--no-legacy-fallback", action="store_true",
                        help="не откатываться на /v2/ugcPosts при 403/404")
    p_post.add_argument("--dry-run", action="store_true", help="показать тело запроса и выйти")
    p_post.add_argument("-y", "--yes", action="store_true", help="не спрашивать подтверждение")
    p_post.set_defaults(func=cmd_post)

    p_del = sub.add_parser("delete", help="удалить пост по URN")
    p_del.add_argument("urn", help="urn:li:share:123… или urn:li:ugcPost:123…")
    p_del.set_defaults(func=cmd_delete)

    p_logout = sub.add_parser("logout", help="удалить локальный токен")
    p_logout.set_defaults(func=cmd_logout)

    p_doctor = sub.add_parser("doctor", help="проверить конфигурацию, токен и доступы")
    p_doctor.set_defaults(func=cmd_doctor)

    return parser


def main(argv: Optional[list] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.func(args))
    except ConfigError as exc:
        print(f"Ошибка конфигурации: {exc}", file=sys.stderr)
        return EXIT_CONFIG
    except AuthError as exc:
        print(f"Ошибка авторизации: {exc}", file=sys.stderr)
        return EXIT_AUTH
    except RateLimitError as exc:
        wait = f" Повторите через {exc.retry_after} с." if exc.retry_after else ""
        print(f"Превышен лимит запросов LinkedIn.{wait}", file=sys.stderr)
        return EXIT_ERROR
    except ApiError as exc:
        print(f"Ошибка API: {exc}", file=sys.stderr)
        return EXIT_ERROR
    except LinkedInError as exc:
        print(f"Ошибка: {exc}", file=sys.stderr)
        return EXIT_ERROR
    except KeyboardInterrupt:
        print("\nПрервано.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
