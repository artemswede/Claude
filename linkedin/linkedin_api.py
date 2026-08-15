#!/usr/bin/env python3
"""LinkedIn API client: OAuth 2.0 (authorization code) + Posts API.

Только стандартная библиотека Python 3.9+. Никаких зависимостей.

Покрывает:
  * OAuth 2.0 authorization code flow с локальным callback-сервером
    и ручным режимом (для машин без браузера).
  * Хранение токена в файле с правами 0600, атомарная перезапись.
  * OpenID Connect /v2/userinfo -> person URN.
  * Публикация текста и изображений через versioned Posts API
    (POST /rest/posts) с автоматическим откатом на legacy /v2/ugcPosts.
  * Удаление поста, интроспекция токена.

Ключевые особенности LinkedIn, учтённые здесь:
  * access token живёт 60 дней; refresh token выдаётся ТОЛЬКО приложениям,
    одобренным в Marketing Developer Platform. Обычное self-serve приложение
    обязано проходить повторный login раз в 60 дней.
  * commentary в /rest/posts использует формат "little text": символы
    \\ | { } @ [ ] ( ) < > # * _ ~ обязаны экранироваться обратным слэшем,
    иначе текст с круглой скобкой молча обрезается по неё.
  * POST /rest/posts НЕ идемпотентен и здесь никогда не ретраится
    автоматически — повтор после таймаута даёт дубль публикации.
"""

from __future__ import annotations

import hmac
import http.server
import json
import os
import random
import re
import secrets
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

__all__ = [
    "Config",
    "TokenStore",
    "Token",
    "LinkedInClient",
    "LinkedInError",
    "ConfigError",
    "AuthError",
    "ApiError",
    "RateLimitError",
    "escape_little_text",
    "login",
]

# --------------------------------------------------------------------------- #
# Константы
# --------------------------------------------------------------------------- #

AUTHORIZE_URL = "https://www.linkedin.com/oauth/v2/authorization"
TOKEN_URL = "https://www.linkedin.com/oauth/v2/accessToken"
INTROSPECT_URL = "https://www.linkedin.com/oauth/v2/introspectToken"
API_BASE = "https://api.linkedin.com"

DEFAULT_SCOPES = ("openid", "profile", "email", "w_member_social")
# Версии LinkedIn API поддерживаются примерно год с момента выпуска.
DEFAULT_API_VERSION = "202605"

COMMENTARY_MAX_CHARS = 3000
DEFAULT_TIMEOUT = 30.0
DEFAULT_UPLOAD_TIMEOUT = 180.0
LOGIN_WAIT_SECONDS = 300

# Формат "little text": каждый из этих символов экранируется обратным слэшем.
LITTLE_TEXT_RESERVED = frozenset("\\|{}@[]()<>#*_~")

VISIBILITY_VALUES = ("PUBLIC", "CONNECTIONS", "LOGGED_IN", "CONTAINER")

USER_AGENT = "linkedin-cli/1.0 (+stdlib-urllib)"

_PERSON_SUB_RE = re.compile(r"^[A-Za-z0-9_-]{2,64}$")
_URN_RE = re.compile(r"^urn:li:(person|organization|organizationBrand):[A-Za-z0-9_-]+$")
_POST_URN_RE = re.compile(r"^urn:li:(share|ugcPost):[0-9]+$")


# --------------------------------------------------------------------------- #
# Ошибки
# --------------------------------------------------------------------------- #


class LinkedInError(Exception):
    """Базовая ошибка."""


class ConfigError(LinkedInError):
    """Не хватает или некорректна конфигурация приложения."""


class AuthError(LinkedInError):
    """Нужен повторный вход (нет токена, истёк, отозван, не тот app)."""


class ApiError(LinkedInError):
    """LinkedIn вернул ошибку HTTP."""

    def __init__(
        self,
        status: int,
        body: str,
        url: str = "",
        service_error_code: Optional[int] = None,
    ) -> None:
        self.status = status
        self.body = body
        self.url = url
        self.service_error_code = service_error_code
        detail = body.strip()
        if len(detail) > 800:
            detail = detail[:800] + "…"
        super().__init__(f"HTTP {status} от {url or 'LinkedIn'}: {detail}")


class RateLimitError(ApiError):
    """429. retry_after — секунды из заголовка Retry-After, если он был."""

    def __init__(self, status: int, body: str, url: str, retry_after: Optional[int]) -> None:
        self.retry_after = retry_after
        super().__init__(status, body, url)


# --------------------------------------------------------------------------- #
# Конфигурация
# --------------------------------------------------------------------------- #


def _parse_dotenv(path: Path) -> Dict[str, str]:
    """Минимальный парсер .env: KEY=VALUE, # — комментарий, кавычки снимаются."""
    result: Dict[str, str] = {}
    try:
        raw = path.read_text(encoding="utf-8-sig")
    except FileNotFoundError:
        return result
    except OSError as exc:
        raise ConfigError(f"не могу прочитать {path}: {exc}") from exc

    for lineno, line in enumerate(raw.splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :].lstrip()
        if "=" not in line:
            raise ConfigError(f"{path}:{lineno}: ожидалось KEY=VALUE, получено {line!r}")
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        if key:
            result[key] = value
    return result


def _default_token_path() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME")
    root = Path(base) if base else Path.home() / ".config"
    return root / "linkedin-cli" / "token.json"


@dataclass
class Config:
    client_id: str
    client_secret: str
    redirect_uri: str = "http://localhost:8765/callback"
    api_version: str = DEFAULT_API_VERSION
    token_path: Path = field(default_factory=_default_token_path)
    scopes: Tuple[str, ...] = DEFAULT_SCOPES
    person_urn_override: Optional[str] = None

    @classmethod
    def from_env(cls, dotenv_path: Optional[Path] = None) -> "Config":
        """Переменные окружения имеют приоритет над .env."""
        values: Dict[str, str] = {}
        if dotenv_path is None:
            dotenv_path = Path(__file__).resolve().parent / ".env"
        values.update(_parse_dotenv(Path(dotenv_path)))
        for key in list(values):
            if key in os.environ:
                values[key] = os.environ[key]
        for key, value in os.environ.items():
            if key.startswith("LINKEDIN_"):
                values[key] = value

        client_id = values.get("LINKEDIN_CLIENT_ID", "").strip()
        client_secret = values.get("LINKEDIN_CLIENT_SECRET", "").strip()
        missing = [
            name
            for name, val in (
                ("LINKEDIN_CLIENT_ID", client_id),
                ("LINKEDIN_CLIENT_SECRET", client_secret),
            )
            if not val
        ]
        if missing:
            raise ConfigError(
                "не заданы " + ", ".join(missing) + ". Скопируйте linkedin/.env.example "
                "в linkedin/.env и впишите значения из LinkedIn Developer Portal → Auth."
            )

        redirect_uri = values.get("LINKEDIN_REDIRECT_URI", "").strip() or "http://localhost:8765/callback"
        api_version = values.get("LINKEDIN_API_VERSION", "").strip() or DEFAULT_API_VERSION
        if not re.fullmatch(r"\d{6}", api_version):
            raise ConfigError(
                f"LINKEDIN_API_VERSION={api_version!r} — ожидается формат YYYYMM, например {DEFAULT_API_VERSION}"
            )

        scopes_raw = values.get("LINKEDIN_SCOPES", "").strip()
        scopes = tuple(scopes_raw.split()) if scopes_raw else DEFAULT_SCOPES

        token_path_raw = values.get("LINKEDIN_TOKEN_PATH", "").strip()
        token_path = Path(token_path_raw).expanduser() if token_path_raw else _default_token_path()

        person_override = values.get("LINKEDIN_PERSON_URN", "").strip() or None
        if person_override and not _URN_RE.match(person_override):
            raise ConfigError(
                f"LINKEDIN_PERSON_URN={person_override!r} не похож на URN "
                "вида urn:li:person:XXXX"
            )

        cfg = cls(
            client_id=client_id,
            client_secret=client_secret,
            redirect_uri=redirect_uri,
            api_version=api_version,
            token_path=token_path,
            scopes=scopes,
            person_urn_override=person_override,
        )
        cfg.validate_redirect_uri()
        return cfg

    def validate_redirect_uri(self) -> urllib.parse.ParseResult:
        parsed = urllib.parse.urlparse(self.redirect_uri)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise ConfigError(
                f"LINKEDIN_REDIRECT_URI={self.redirect_uri!r} должен быть абсолютным http(s) URL"
            )
        if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
            raise ConfigError(
                "LinkedIn принимает http только для localhost/127.0.0.1; "
                "для внешнего хоста нужен https"
            )
        if parsed.query or parsed.fragment:
            raise ConfigError("LINKEDIN_REDIRECT_URI не должен содержать query-строку или фрагмент")
        return parsed

    @property
    def is_local_callback(self) -> bool:
        parsed = urllib.parse.urlparse(self.redirect_uri)
        return parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1", "::1")

    @property
    def callback_port(self) -> int:
        parsed = urllib.parse.urlparse(self.redirect_uri)
        return parsed.port or (443 if parsed.scheme == "https" else 80)

    @property
    def callback_path(self) -> str:
        return urllib.parse.urlparse(self.redirect_uri).path or "/"


# --------------------------------------------------------------------------- #
# Токен
# --------------------------------------------------------------------------- #


@dataclass
class Token:
    access_token: str
    expires_at: float
    scope: str = ""
    refresh_token: Optional[str] = None
    refresh_token_expires_at: Optional[float] = None
    obtained_at: float = 0.0
    client_id: str = ""
    sub: Optional[str] = None
    name: Optional[str] = None
    email: Optional[str] = None

    @property
    def seconds_left(self) -> float:
        return self.expires_at - time.time()

    @property
    def is_expired(self) -> bool:
        # 60-секундный запас, чтобы не отправить запрос с токеном, который
        # истечёт в полёте.
        return self.seconds_left <= 60

    @property
    def scopes(self) -> List[str]:
        return self.scope.split() if self.scope else []

    @property
    def can_refresh(self) -> bool:
        if not self.refresh_token:
            return False
        if self.refresh_token_expires_at is None:
            return True
        return self.refresh_token_expires_at > time.time() + 60

    def to_dict(self) -> Dict[str, Any]:
        return {
            "access_token": self.access_token,
            "expires_at": self.expires_at,
            "scope": self.scope,
            "refresh_token": self.refresh_token,
            "refresh_token_expires_at": self.refresh_token_expires_at,
            "obtained_at": self.obtained_at,
            "client_id": self.client_id,
            "sub": self.sub,
            "name": self.name,
            "email": self.email,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Token":
        try:
            return cls(
                access_token=str(data["access_token"]),
                expires_at=float(data["expires_at"]),
                scope=str(data.get("scope") or ""),
                refresh_token=data.get("refresh_token") or None,
                refresh_token_expires_at=(
                    float(data["refresh_token_expires_at"])
                    if data.get("refresh_token_expires_at") is not None
                    else None
                ),
                obtained_at=float(data.get("obtained_at") or 0.0),
                client_id=str(data.get("client_id") or ""),
                sub=data.get("sub") or None,
                name=data.get("name") or None,
                email=data.get("email") or None,
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise AuthError(f"файл токена повреждён ({exc}); выполните `login` заново") from exc

    @classmethod
    def from_token_response(cls, payload: Dict[str, Any], client_id: str) -> "Token":
        if "access_token" not in payload:
            raise AuthError(f"ответ token-эндпоинта без access_token: {payload!r}")
        now = time.time()
        expires_in = payload.get("expires_in")
        try:
            expires_in = float(expires_in)
        except (TypeError, ValueError):
            # LinkedIn всегда присылает expires_in; если нет — считаем 60 дней.
            expires_in = 5184000.0
        refresh_expires = payload.get("refresh_token_expires_in")
        try:
            refresh_expires_at = now + float(refresh_expires) if refresh_expires else None
        except (TypeError, ValueError):
            refresh_expires_at = None
        return cls(
            access_token=str(payload["access_token"]),
            expires_at=now + expires_in,
            scope=str(payload.get("scope") or ""),
            refresh_token=payload.get("refresh_token") or None,
            refresh_token_expires_at=refresh_expires_at,
            obtained_at=now,
            client_id=client_id,
        )


class TokenStore:
    """Файловое хранилище токена: 0600 на файл, 0700 на каталог, atomic replace."""

    def __init__(self, path: Path) -> None:
        self.path = Path(path).expanduser()

    def load(self) -> Optional[Token]:
        try:
            raw = self.path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return None
        except OSError as exc:
            raise AuthError(f"не могу прочитать {self.path}: {exc}") from exc
        if not raw.strip():
            return None
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise AuthError(f"{self.path}: невалидный JSON ({exc}); выполните `login` заново") from exc
        if not isinstance(data, dict):
            raise AuthError(f"{self.path}: ожидался JSON-объект; выполните `login` заново")
        return Token.from_dict(data)

    def save(self, token: Token) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(self.path.parent, 0o700)
        except OSError:
            pass  # на Windows/exotic FS может не поддерживаться — не критично
        tmp = self.path.with_name(self.path.name + f".tmp-{os.getpid()}")
        payload = json.dumps(token.to_dict(), ensure_ascii=False, indent=2) + "\n"
        fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp, self.path)
        except BaseException:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass

    def delete(self) -> bool:
        try:
            self.path.unlink()
            return True
        except FileNotFoundError:
            return False
        except OSError as exc:
            raise LinkedInError(f"не могу удалить {self.path}: {exc}") from exc


# --------------------------------------------------------------------------- #
# HTTP
# --------------------------------------------------------------------------- #


@dataclass
class HttpResponse:
    status: int
    headers: Dict[str, str]
    body: bytes

    def json(self) -> Any:
        if not self.body:
            return None
        try:
            return json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ApiError(self.status, self.body[:500].decode("utf-8", "replace"),
                           service_error_code=None) from exc

    @property
    def text(self) -> str:
        return self.body.decode("utf-8", "replace")


def _retry_after_seconds(headers: Dict[str, str]) -> Optional[int]:
    raw = headers.get("retry-after")
    if not raw:
        return None
    try:
        return max(0, int(float(raw.strip())))
    except ValueError:
        return None  # HTTP-date форма — не пытаемся парсить, вернём None


def _is_definitely_undelivered(exc: urllib.error.URLError) -> bool:
    """True, если запрос точно не дошёл до сервера — только тогда POST можно повторить."""
    reason = getattr(exc, "reason", None)
    return isinstance(reason, (ConnectionRefusedError, socket.gaierror))


def http_request(
    method: str,
    url: str,
    *,
    headers: Optional[Dict[str, str]] = None,
    data: Optional[bytes] = None,
    timeout: float = DEFAULT_TIMEOUT,
    max_attempts: int = 4,
    retry_on_status: Tuple[int, ...] = (429, 500, 502, 503, 504),
    idempotent: bool = True,
) -> HttpResponse:
    """Запрос с ретраями.

    idempotent=False -> ретраится ТОЛЬКО когда достоверно известно, что запрос
    не дошёл (connection refused / DNS). Это защищает от дублей публикации.
    """
    final_headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    final_headers.update(headers or {})

    last_exc: Optional[BaseException] = None
    for attempt in range(1, max_attempts + 1):
        request = urllib.request.Request(url, data=data, method=method.upper())
        for key, value in final_headers.items():
            request.add_header(key, value)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return HttpResponse(
                    status=response.status,
                    headers={k.lower(): v for k, v in response.headers.items()},
                    body=response.read(),
                )
        except urllib.error.HTTPError as exc:
            body = exc.read() or b""
            resp_headers = {k.lower(): v for k, v in (exc.headers or {}).items()}
            response = HttpResponse(status=exc.code, headers=resp_headers, body=body)
            retryable = idempotent and exc.code in retry_on_status and attempt < max_attempts
            if not retryable:
                return response
            delay = _retry_after_seconds(resp_headers)
            if delay is None:
                delay = min(2 ** attempt, 30) + random.uniform(0, 1)
            last_exc = exc
            time.sleep(delay)
        except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError) as exc:
            last_exc = exc
            can_retry = attempt < max_attempts and (
                idempotent
                or (isinstance(exc, urllib.error.URLError) and _is_definitely_undelivered(exc))
            )
            if not can_retry:
                hint = ""
                if not idempotent:
                    hint = (
                        " Запрос неидемпотентный и не повторялся автоматически: "
                        "проверьте ленту LinkedIn, публикация могла пройти."
                    )
                raise LinkedInError(f"сетевая ошибка при {method} {url}: {exc}.{hint}") from exc
            time.sleep(min(2 ** attempt, 30) + random.uniform(0, 1))

    raise LinkedInError(f"не удалось выполнить {method} {url}: {last_exc}")


# --------------------------------------------------------------------------- #
# little text
# --------------------------------------------------------------------------- #


def escape_little_text(text: str) -> str:
    """Экранирует зарезервированные символы формата little text.

    Без этого LinkedIn молча обрезает commentary начиная с первой круглой
    скобки, а квадратные скобки/собаку трактует как разметку упоминаний.
    """
    out: List[str] = []
    for char in text:
        if char in LITTLE_TEXT_RESERVED:
            out.append("\\")
        out.append(char)
    return "".join(out)


def validate_commentary(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n").strip("﻿")
    if not text.strip():
        raise LinkedInError("текст поста пустой")
    if len(text) > COMMENTARY_MAX_CHARS:
        raise LinkedInError(
            f"текст поста {len(text)} символов, лимит LinkedIn — {COMMENTARY_MAX_CHARS}"
        )
    return text


def normalize_author(value: str) -> str:
    """'person:ABC' | 'org:123' | 'urn:li:person:ABC' | 'ABC' -> полный URN."""
    value = value.strip()
    if _URN_RE.match(value):
        return value
    if value.startswith("org:") or value.startswith("organization:"):
        ident = value.split(":", 1)[1]
        if not ident.isdigit():
            raise LinkedInError(f"id организации должен быть числом, получено {ident!r}")
        return f"urn:li:organization:{ident}"
    if value.startswith("person:"):
        ident = value.split(":", 1)[1]
    else:
        ident = value
    if not _PERSON_SUB_RE.match(ident):
        raise LinkedInError(f"не могу разобрать автора {value!r}; ожидается urn:li:person:XXXX или org:12345")
    return f"urn:li:person:{ident}"


# --------------------------------------------------------------------------- #
# OAuth
# --------------------------------------------------------------------------- #


def build_authorize_url(config: Config, state: str, scopes: Optional[Tuple[str, ...]] = None) -> str:
    params = {
        "response_type": "code",
        "client_id": config.client_id,
        "redirect_uri": config.redirect_uri,
        "state": state,
        "scope": " ".join(scopes or config.scopes),
    }
    return AUTHORIZE_URL + "?" + urllib.parse.urlencode(params, quote_via=urllib.parse.quote)


def _post_token_endpoint(payload: Dict[str, str]) -> Dict[str, Any]:
    body = urllib.parse.urlencode(payload).encode("utf-8")
    response = http_request(
        "POST",
        TOKEN_URL,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        data=body,
        # Код авторизации одноразовый: повторяем только при заведомо
        # недоставленном запросе, иначе получим invalid_grant.
        idempotent=False,
    )
    if response.status != 200:
        try:
            parsed = response.json() or {}
        except ApiError:
            parsed = {}
        error = parsed.get("error", "")
        description = parsed.get("error_description", response.text)
        if error in ("invalid_grant", "invalid_request"):
            raise AuthError(
                f"LinkedIn отклонил обмен кода ({error}): {description}. "
                "Чаще всего это несовпадение redirect_uri с тем, что записан в "
                "Developer Portal → Auth → Authorized redirect URLs, либо код уже использован."
            )
        if error == "invalid_client":
            raise AuthError(
                "LinkedIn не принял client_id/client_secret. Проверьте значения "
                "в linkedin/.env (секрет виден в Developer Portal → Auth → Client secret)."
            )
        raise ApiError(response.status, response.text, TOKEN_URL)
    parsed = response.json()
    if not isinstance(parsed, dict):
        raise AuthError(f"неожиданный ответ token-эндпоинта: {response.text[:300]}")
    return parsed


def exchange_code(config: Config, code: str) -> Token:
    payload = _post_token_endpoint(
        {
            "grant_type": "authorization_code",
            "code": code,
            "client_id": config.client_id,
            "client_secret": config.client_secret,
            "redirect_uri": config.redirect_uri,
        }
    )
    return Token.from_token_response(payload, config.client_id)


def refresh_token(config: Config, token: Token) -> Token:
    if not token.refresh_token:
        raise AuthError("refresh_token отсутствует — обновление невозможно, нужен повторный login")
    payload = _post_token_endpoint(
        {
            "grant_type": "refresh_token",
            "refresh_token": token.refresh_token,
            "client_id": config.client_id,
            "client_secret": config.client_secret,
        }
    )
    fresh = Token.from_token_response(payload, config.client_id)
    # LinkedIn может не вернуть refresh_token повторно — сохраняем прежний.
    if not fresh.refresh_token:
        fresh.refresh_token = token.refresh_token
        fresh.refresh_token_expires_at = token.refresh_token_expires_at
    fresh.sub = token.sub
    fresh.name = token.name
    fresh.email = token.email
    return fresh


class _CallbackResult:
    def __init__(self) -> None:
        self.code: Optional[str] = None
        self.state: Optional[str] = None
        self.error: Optional[str] = None
        self.error_description: Optional[str] = None
        self.done = threading.Event()


_HTML_PAGE = (
    "<!doctype html><meta charset=utf-8>"
    "<title>LinkedIn</title>"
    "<style>body{{font:16px/1.5 system-ui,sans-serif;margin:15vh auto;max-width:32rem;"
    "text-align:center;color:#111}}h1{{font-size:1.25rem}}p{{color:#555}}</style>"
    "<h1>{title}</h1><p>{message}</p>"
)


def _make_handler(result: _CallbackResult, callback_path: str):
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.0"

        def do_GET(self) -> None:  # noqa: N802 (имя задано базовым классом)
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path != callback_path:
                # Браузер часто дёргает /favicon.ico — не считаем это ответом.
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            query = urllib.parse.parse_qs(parsed.query)
            result.code = (query.get("code") or [None])[0]
            result.state = (query.get("state") or [None])[0]
            result.error = (query.get("error") or [None])[0]
            result.error_description = (query.get("error_description") or [None])[0]

            if result.error:
                title, message = "Доступ не выдан", (result.error_description or result.error)
            elif result.code:
                title, message = "Готово", "Можно закрыть вкладку и вернуться в терминал."
            else:
                title, message = "Странный ответ", "В callback нет ни code, ни error."
            page = _HTML_PAGE.format(title=title, message=message).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(page)))
            self.end_headers()
            self.wfile.write(page)
            result.done.set()

        def log_message(self, *args: Any) -> None:
            pass  # не засоряем stdout

    return Handler


def _wait_for_callback(config: Config, timeout: int = LOGIN_WAIT_SECONDS) -> _CallbackResult:
    result = _CallbackResult()
    handler = _make_handler(result, config.callback_path)
    try:
        server = http.server.HTTPServer(("127.0.0.1", config.callback_port), handler)
    except OSError as exc:
        raise LinkedInError(
            f"не могу занять 127.0.0.1:{config.callback_port} ({exc}). "
            "Освободите порт, поменяйте LINKEDIN_REDIRECT_URI или используйте `login --manual`."
        ) from exc

    server.timeout = 1.0
    deadline = time.monotonic() + timeout
    try:
        while not result.done.is_set():
            if time.monotonic() > deadline:
                raise LinkedInError(
                    f"не дождались редиректа за {timeout} с. Повторите `login` или используйте `--manual`."
                )
            server.handle_request()
    except KeyboardInterrupt:
        raise LinkedInError("вход прерван пользователем") from None
    finally:
        server.server_close()
    return result


def _consume_callback(result: _CallbackResult, expected_state: str) -> str:
    if result.error:
        if result.error in ("user_cancelled_login", "user_cancelled_authorize"):
            raise AuthError("вы отклонили выдачу доступа в окне LinkedIn")
        raise AuthError(f"LinkedIn вернул ошибку {result.error}: {result.error_description or '—'}")
    if not result.code:
        raise AuthError("в callback нет параметра code")
    if not result.state or not hmac.compare_digest(result.state, expected_state):
        raise AuthError(
            "state в ответе не совпал с отправленным — возможна CSRF-атака, вход отменён"
        )
    return result.code


def login(
    config: Config,
    *,
    manual: bool = False,
    scopes: Optional[Tuple[str, ...]] = None,
    open_browser: bool = True,
    out=sys.stderr,
) -> Token:
    """Полный authorization code flow. Возвращает токен (не сохраняет его)."""
    state = secrets.token_urlsafe(32)
    url = build_authorize_url(config, state, scopes)

    use_manual = manual or not config.is_local_callback
    print("Откройте ссылку и подтвердите доступ:\n", file=out)
    print(url + "\n", file=out)
    if open_browser and not use_manual:
        try:
            webbrowser.open(url)
        except Exception:  # noqa: BLE001 — отсутствие браузера не должно ломать вход
            pass

    if use_manual:
        print(
            "После подтверждения браузер уйдёт на redirect_uri. Скопируйте адресную "
            "строку целиком и вставьте сюда.",
            file=out,
        )
        try:
            pasted = input("Redirect URL: ").strip()
        except (EOFError, KeyboardInterrupt):
            raise LinkedInError("вход прерван") from None
        if not pasted:
            raise AuthError("пустой ввод")
        parsed = urllib.parse.urlparse(pasted)
        query = urllib.parse.parse_qs(parsed.query)
        result = _CallbackResult()
        result.code = (query.get("code") or [None])[0]
        result.state = (query.get("state") or [None])[0]
        result.error = (query.get("error") or [None])[0]
        result.error_description = (query.get("error_description") or [None])[0]
        if not (result.code or result.error):
            raise AuthError("в вставленном URL нет ни code, ни error — вставьте адрес целиком")
    else:
        print(f"Жду редирект на {config.redirect_uri} …", file=out)
        result = _wait_for_callback(config)

    code = _consume_callback(result, state)
    token = exchange_code(config, code)
    return token


# --------------------------------------------------------------------------- #
# Клиент API
# --------------------------------------------------------------------------- #


class LinkedInClient:
    def __init__(self, config: Config, token: Token, store: Optional[TokenStore] = None) -> None:
        self.config = config
        self.token = token
        self.store = store
        if token.client_id and token.client_id != config.client_id:
            raise AuthError(
                "сохранённый токен выдан другому приложению "
                f"(client_id {token.client_id[:6]}…, в конфиге {config.client_id[:6]}…). "
                "Выполните `login` заново."
            )

    # -- фабрика -------------------------------------------------------------

    @classmethod
    def from_store(cls, config: Config, *, auto_refresh: bool = True) -> "LinkedInClient":
        store = TokenStore(config.token_path)
        token = store.load()
        if token is None:
            raise AuthError(
                f"токен не найден ({store.path}). Выполните: python3 -m linkedin.cli login"
            )
        if token.is_expired:
            if auto_refresh and token.can_refresh:
                token = refresh_token(config, token)
                store.save(token)
            else:
                days = abs(token.seconds_left) / 86400
                raise AuthError(
                    f"access token истёк {days:.1f} дн. назад, refresh_token недоступен "
                    "(его получают только приложения, одобренные в Marketing Developer Platform). "
                    "Выполните `login` заново."
                )
        return cls(config, token, store)

    # -- низкий уровень ------------------------------------------------------

    def _headers(self, *, versioned: bool, json_body: bool = False) -> Dict[str, str]:
        headers = {
            "Authorization": f"Bearer {self.token.access_token}",
            "X-Restli-Protocol-Version": "2.0.0",
        }
        if versioned:
            headers["LinkedIn-Version"] = self.config.api_version
        if json_body:
            headers["Content-Type"] = "application/json"
        return headers

    def _call(
        self,
        method: str,
        path: str,
        *,
        versioned: bool,
        payload: Optional[Dict[str, Any]] = None,
        timeout: float = DEFAULT_TIMEOUT,
        idempotent: bool = True,
        expected: Tuple[int, ...] = (200, 201, 204),
    ) -> HttpResponse:
        url = path if path.startswith("http") else API_BASE + path
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        response = http_request(
            method,
            url,
            headers=self._headers(versioned=versioned, json_body=payload is not None),
            data=data,
            timeout=timeout,
            idempotent=idempotent,
        )
        if response.status in expected:
            return response
        self._raise(response, url)

    def _raise(self, response: HttpResponse, url: str) -> "None":
        text = response.text
        service_code = None
        try:
            parsed = json.loads(text) if text else {}
            if isinstance(parsed, dict):
                service_code = parsed.get("serviceErrorCode")
        except json.JSONDecodeError:
            pass

        if response.status == 401:
            raise AuthError(
                "LinkedIn вернул 401: токен истёк или отозван (например, вы сменили пароль "
                f"или отозвали доступ приложению). Выполните `login` заново. Ответ: {text[:300]}"
            )
        if response.status == 403:
            raise ApiError(
                response.status,
                text
                + "  |  403 обычно значит, что у приложения нет нужного продукта/scope. "
                "Проверьте Developer Portal → Products: для публикации от лица человека нужен "
                "«Share on LinkedIn» (scope w_member_social), для страницы компании — "
                "Community Management API (одобряется по заявке).",
                url,
                service_code,
            )
        if response.status == 429:
            raise RateLimitError(429, text, url, _retry_after_seconds(response.headers))
        raise ApiError(response.status, text, url, service_code)

    # -- профиль -------------------------------------------------------------

    def userinfo(self) -> Dict[str, Any]:
        """OpenID Connect. Требует scope openid+profile (email — для поля email)."""
        response = self._call("GET", "/v2/userinfo", versioned=False, expected=(200,))
        data = response.json()
        if not isinstance(data, dict):
            raise ApiError(response.status, response.text, "/v2/userinfo")
        return data

    def person_urn(self) -> str:
        if self.config.person_urn_override:
            return self.config.person_urn_override
        if self.token.sub:
            return f"urn:li:person:{self.token.sub}"
        info = self.userinfo()
        sub = info.get("sub")
        if not sub:
            raise AuthError(
                "в /v2/userinfo нет поля sub — скорее всего у токена нет scope openid/profile. "
                "Переполучите токен: `login`, либо задайте LINKEDIN_PERSON_URN вручную."
            )
        self.token.sub = str(sub)
        self.token.name = info.get("name") or self.token.name
        self.token.email = info.get("email") or self.token.email
        if self.store:
            self.store.save(self.token)
        return f"urn:li:person:{self.token.sub}"

    def introspect(self) -> Dict[str, Any]:
        body = urllib.parse.urlencode(
            {
                "client_id": self.config.client_id,
                "client_secret": self.config.client_secret,
                "token": self.token.access_token,
            }
        ).encode("utf-8")
        response = http_request(
            "POST",
            INTROSPECT_URL,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            data=body,
            idempotent=True,
        )
        if response.status != 200:
            raise ApiError(response.status, response.text, INTROSPECT_URL)
        data = response.json()
        return data if isinstance(data, dict) else {}

    # -- посты ---------------------------------------------------------------

    @staticmethod
    def build_post_body(
        text: str,
        *,
        author: str,
        visibility: str = "PUBLIC",
        image_urn: Optional[str] = None,
        alt_text: str = "",
        allow_reshare: bool = True,
    ) -> Dict[str, Any]:
        if visibility not in VISIBILITY_VALUES:
            raise LinkedInError(
                f"visibility={visibility!r}; допустимо: {', '.join(VISIBILITY_VALUES)}"
            )
        body: Dict[str, Any] = {
            "author": author,
            "commentary": escape_little_text(validate_commentary(text)),
            "visibility": visibility,
            "distribution": {
                "feedDistribution": "MAIN_FEED",
                "targetEntities": [],
                "thirdPartyDistributionChannels": [],
            },
            "lifecycleState": "PUBLISHED",
            "isReshareDisabledByAuthor": not allow_reshare,
        }
        if image_urn:
            media: Dict[str, Any] = {"id": image_urn}
            if alt_text:
                media["altText"] = alt_text[:300]
            body["content"] = {"media": media}
        return body

    @staticmethod
    def build_ugc_body(
        text: str,
        *,
        author: str,
        visibility: str = "PUBLIC",
    ) -> Dict[str, Any]:
        """Legacy /v2/ugcPosts. Здесь little-text-экранирование НЕ применяется."""
        vis = "PUBLIC" if visibility == "PUBLIC" else "CONNECTIONS"
        return {
            "author": author,
            "lifecycleState": "PUBLISHED",
            "specificContent": {
                "com.linkedin.ugc.ShareContent": {
                    "shareCommentary": {"text": validate_commentary(text)},
                    "shareMediaCategory": "NONE",
                }
            },
            "visibility": {"com.linkedin.ugc.MemberNetworkVisibility": vis},
        }

    def create_post(
        self,
        text: str,
        *,
        author: Optional[str] = None,
        visibility: str = "PUBLIC",
        image_path: Optional[str] = None,
        alt_text: str = "",
        allow_reshare: bool = True,
        allow_legacy_fallback: bool = True,
    ) -> str:
        """Публикует пост. Возвращает URN созданного поста."""
        author_urn = normalize_author(author) if author else self.person_urn()

        image_urn = None
        if image_path:
            image_urn = self.upload_image(image_path, owner=author_urn)

        body = self.build_post_body(
            text,
            author=author_urn,
            visibility=visibility,
            image_urn=image_urn,
            alt_text=alt_text,
            allow_reshare=allow_reshare,
        )
        try:
            response = self._call(
                "POST",
                "/rest/posts",
                versioned=True,
                payload=body,
                idempotent=False,
                expected=(200, 201),
            )
        except ApiError as exc:
            fallback_ok = (
                allow_legacy_fallback
                and exc.status in (403, 404)
                and image_urn is None
                and author_urn.startswith("urn:li:person:")
            )
            if not fallback_ok:
                raise
            # 403/404 означают, что запрос отвергнут до создания сущности —
            # повтор через legacy-эндпоинт безопасен, дубля не будет.
            legacy = self._call(
                "POST",
                "/v2/ugcPosts",
                versioned=False,
                payload=self.build_ugc_body(text, author=author_urn, visibility=visibility),
                idempotent=False,
                expected=(200, 201),
            )
            return self._extract_post_urn(legacy)
        return self._extract_post_urn(response)

    @staticmethod
    def _extract_post_urn(response: HttpResponse) -> str:
        urn = response.headers.get("x-restli-id")
        if urn:
            return urn
        try:
            data = response.json()
        except ApiError:
            data = None
        if isinstance(data, dict):
            for key in ("id", "urn", "activity"):
                if data.get(key):
                    return str(data[key])
        raise ApiError(
            response.status,
            "пост, судя по коду ответа, создан, но в ответе нет заголовка x-restli-id: "
            + response.text[:300],
        )

    def delete_post(self, urn: str) -> None:
        urn = urn.strip()
        if not _POST_URN_RE.match(urn):
            raise LinkedInError(
                f"{urn!r} не похож на URN поста; ожидается urn:li:share:123 или urn:li:ugcPost:123"
            )
        encoded = urllib.parse.quote(urn, safe="")
        self._call("DELETE", f"/rest/posts/{encoded}", versioned=True, expected=(200, 204))

    # -- изображения ---------------------------------------------------------

    def upload_image(self, path: str, *, owner: Optional[str] = None) -> str:
        file_path = Path(path).expanduser()
        if not file_path.is_file():
            raise LinkedInError(f"файл не найден: {file_path}")
        size = file_path.stat().st_size
        if size == 0:
            raise LinkedInError(f"файл пустой: {file_path}")
        if size > 10 * 1024 * 1024:
            raise LinkedInError(
                f"{file_path}: {size / 1048576:.1f} МБ — LinkedIn ограничивает изображение 10 МБ"
            )

        owner_urn = owner or self.person_urn()
        init = self._call(
            "POST",
            "/rest/images?action=initializeUpload",
            versioned=True,
            payload={"initializeUploadRequest": {"owner": owner_urn}},
            idempotent=True,
            expected=(200, 201),
        )
        data = init.json()
        value = (data or {}).get("value") if isinstance(data, dict) else None
        if not isinstance(value, dict) or not value.get("uploadUrl") or not value.get("image"):
            raise ApiError(init.status, init.text, "/rest/images?action=initializeUpload")

        payload = file_path.read_bytes()
        upload = http_request(
            "PUT",
            value["uploadUrl"],
            headers={
                "Authorization": f"Bearer {self.token.access_token}",
                # urllib иначе подставит x-www-form-urlencoded и загрузка сломается.
                "Content-Type": "application/octet-stream",
            },
            data=payload,
            timeout=DEFAULT_UPLOAD_TIMEOUT,
            idempotent=True,
        )
        if upload.status not in (200, 201, 204):
            raise ApiError(upload.status, upload.text, "upload image")
        return str(value["image"])


# --------------------------------------------------------------------------- #
# Утилиты вывода
# --------------------------------------------------------------------------- #


def redact(secret: str, keep: int = 4) -> str:
    if not secret:
        return "—"
    if len(secret) <= keep * 2:
        return "*" * len(secret)
    return f"{secret[:keep]}…{secret[-keep:]} ({len(secret)} симв.)"


def human_duration(seconds: float) -> str:
    seconds = int(seconds)
    if seconds < 0:
        return f"истёк {human_duration(-seconds)} назад"
    days, rem = divmod(seconds, 86400)
    hours, rem = divmod(rem, 3600)
    minutes = rem // 60
    if days:
        return f"{days} дн. {hours} ч."
    if hours:
        return f"{hours} ч. {minutes} мин."
    return f"{minutes} мин."
