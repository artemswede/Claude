#!/usr/bin/env python3
"""Офлайн-тесты. Никаких обращений к LinkedIn — только localhost.

    python3 linkedin/test_linkedin_api.py
"""

from __future__ import annotations

import http.server
import io
import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import linkedin_api as li  # noqa: E402


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class TestLittleText(unittest.TestCase):
    def test_parentheses_escaped(self):
        self.assertEqual(li.escape_little_text("(a)"), r"\(a\)")

    def test_backslash_escaped_once(self):
        self.assertEqual(li.escape_little_text("a\\b"), "a\\\\b")

    def test_all_reserved(self):
        src = "\\|{}@[]()<>#*_~"
        escaped = li.escape_little_text(src)
        self.assertEqual(len(escaped), 2 * len(src))
        self.assertTrue(all(escaped[i] == "\\" for i in range(0, len(escaped), 2)))

    def test_plain_and_unicode_untouched(self):
        for text in ("Привет, мир!", "日本語テキスト", "a-b+c/d=e", "emoji 🚀 ok", ""):
            self.assertEqual(li.escape_little_text(text), text)

    def test_newlines_preserved(self):
        self.assertEqual(li.escape_little_text("a\nb"), "a\nb")


class TestValidateCommentary(unittest.TestCase):
    def test_empty_rejected(self):
        for value in ("", "   ", "\n\t "):
            with self.assertRaises(li.LinkedInError):
                li.validate_commentary(value)

    def test_limit_boundary(self):
        self.assertEqual(len(li.validate_commentary("x" * li.COMMENTARY_MAX_CHARS)),
                         li.COMMENTARY_MAX_CHARS)
        with self.assertRaises(li.LinkedInError):
            li.validate_commentary("x" * (li.COMMENTARY_MAX_CHARS + 1))

    def test_crlf_normalised(self):
        self.assertEqual(li.validate_commentary("a\r\nb\rc"), "a\nb\nc")

    def test_bom_stripped(self):
        self.assertEqual(li.validate_commentary("﻿текст"), "текст")

    def test_limit_counts_before_escaping(self):
        # 3000 скобок — валидно по лимиту, хотя после экранирования станет 6000.
        text = "(" * li.COMMENTARY_MAX_CHARS
        self.assertEqual(len(li.escape_little_text(li.validate_commentary(text))),
                         2 * li.COMMENTARY_MAX_CHARS)


class TestNormalizeAuthor(unittest.TestCase):
    def test_variants(self):
        cases = {
            "urn:li:person:AbC-123": "urn:li:person:AbC-123",
            "person:AbC": "urn:li:person:AbC",
            "AbC": "urn:li:person:AbC",
            "org:98765": "urn:li:organization:98765",
            "organization:1": "urn:li:organization:1",
            "urn:li:organization:42": "urn:li:organization:42",
            "  urn:li:person:X1  ": "urn:li:person:X1",
        }
        for src, expected in cases.items():
            self.assertEqual(li.normalize_author(src), expected, src)

    def test_bad_input(self):
        for bad in ("org:abc", "", "urn:li:share:1", "person:!!", "a b c"):
            with self.assertRaises(li.LinkedInError, msg=bad):
                li.normalize_author(bad)


class TestPostBody(unittest.TestCase):
    def test_text_post_shape(self):
        body = li.LinkedInClient.build_post_body(
            "Запуск (beta) уже здесь", author="urn:li:person:X"
        )
        self.assertEqual(body["author"], "urn:li:person:X")
        self.assertEqual(body["commentary"], r"Запуск \(beta\) уже здесь")
        self.assertEqual(body["visibility"], "PUBLIC")
        self.assertEqual(body["lifecycleState"], "PUBLISHED")
        self.assertFalse(body["isReshareDisabledByAuthor"])
        self.assertEqual(body["distribution"]["feedDistribution"], "MAIN_FEED")
        self.assertNotIn("content", body)

    def test_image_and_alt(self):
        body = li.LinkedInClient.build_post_body(
            "x", author="urn:li:person:X", image_urn="urn:li:image:Y", alt_text="a" * 400
        )
        self.assertEqual(body["content"]["media"]["id"], "urn:li:image:Y")
        self.assertEqual(len(body["content"]["media"]["altText"]), 300)

    def test_no_reshare(self):
        body = li.LinkedInClient.build_post_body("x", author="urn:li:person:X", allow_reshare=False)
        self.assertTrue(body["isReshareDisabledByAuthor"])

    def test_bad_visibility(self):
        with self.assertRaises(li.LinkedInError):
            li.LinkedInClient.build_post_body("x", author="urn:li:person:X", visibility="SECRET")

    def test_serialisable_as_utf8(self):
        body = li.LinkedInClient.build_post_body("Привет 🚀", author="urn:li:person:X")
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.assertEqual(json.loads(raw.decode("utf-8"))["commentary"], "Привет 🚀")

    def test_ugc_body_not_escaped(self):
        body = li.LinkedInClient.build_ugc_body("Запуск (beta)", author="urn:li:person:X")
        commentary = body["specificContent"]["com.linkedin.ugc.ShareContent"]["shareCommentary"]
        self.assertEqual(commentary["text"], "Запуск (beta)")
        self.assertEqual(body["visibility"]["com.linkedin.ugc.MemberNetworkVisibility"], "PUBLIC")

    def test_ugc_visibility_downgrade(self):
        body = li.LinkedInClient.build_ugc_body(
            "x", author="urn:li:person:X", visibility="LOGGED_IN"
        )
        self.assertEqual(body["visibility"]["com.linkedin.ugc.MemberNetworkVisibility"], "CONNECTIONS")


class TestToken(unittest.TestCase):
    def test_expiry_math(self):
        fresh = li.Token(access_token="t", expires_at=time.time() + 3600)
        self.assertFalse(fresh.is_expired)
        stale = li.Token(access_token="t", expires_at=time.time() + 30)
        self.assertTrue(stale.is_expired, "токен с остатком <60 c считается истёкшим")
        dead = li.Token(access_token="t", expires_at=time.time() - 1)
        self.assertTrue(dead.is_expired)

    def test_from_response_defaults(self):
        token = li.Token.from_token_response(
            {"access_token": "a", "expires_in": 5184000, "scope": "openid w_member_social"}, "cid"
        )
        self.assertAlmostEqual(token.seconds_left, 5184000, delta=5)
        self.assertEqual(token.scopes, ["openid", "w_member_social"])
        self.assertFalse(token.can_refresh)

    def test_from_response_garbage_expires_in(self):
        token = li.Token.from_token_response({"access_token": "a", "expires_in": "nope"}, "cid")
        self.assertAlmostEqual(token.seconds_left, 5184000, delta=5)

    def test_from_response_missing_access_token(self):
        with self.assertRaises(li.AuthError):
            li.Token.from_token_response({"error": "invalid_grant"}, "cid")

    def test_refresh_window(self):
        token = li.Token(
            access_token="a",
            expires_at=time.time() + 10,
            refresh_token="r",
            refresh_token_expires_at=time.time() - 1,
        )
        self.assertFalse(token.can_refresh, "просроченный refresh_token не годится")


class TestTokenStore(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / "nested" / "token.json"

    def tearDown(self):
        self.dir.cleanup()

    def test_roundtrip_and_permissions(self):
        store = li.TokenStore(self.path)
        self.assertIsNone(store.load())
        token = li.Token(
            access_token="secret", expires_at=time.time() + 100, scope="openid", client_id="cid",
            sub="AbC", name="Артём",
        )
        store.save(token)
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.path.parent.stat().st_mode & 0o777, 0o700)
        loaded = store.load()
        self.assertEqual(loaded.access_token, "secret")
        self.assertEqual(loaded.name, "Артём")
        self.assertEqual(loaded.sub, "AbC")

    def test_no_temp_files_left(self):
        store = li.TokenStore(self.path)
        store.save(li.Token(access_token="a", expires_at=time.time() + 100))
        store.save(li.Token(access_token="b", expires_at=time.time() + 100))
        leftovers = [p.name for p in self.path.parent.iterdir() if ".tmp-" in p.name]
        self.assertEqual(leftovers, [])
        self.assertEqual(store.load().access_token, "b")

    def test_corrupt_file(self):
        self.path.parent.mkdir(parents=True)
        self.path.write_text("{not json", encoding="utf-8")
        with self.assertRaises(li.AuthError):
            li.TokenStore(self.path).load()

    def test_empty_file_treated_as_absent(self):
        self.path.parent.mkdir(parents=True)
        self.path.write_text("", encoding="utf-8")
        self.assertIsNone(li.TokenStore(self.path).load())

    def test_delete(self):
        store = li.TokenStore(self.path)
        store.save(li.Token(access_token="a", expires_at=time.time() + 100))
        self.assertTrue(store.delete())
        self.assertFalse(store.delete())


class TestDotenvAndConfig(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.env_path = Path(self.dir.name) / ".env"
        self._saved = {k: v for k, v in os.environ.items() if k.startswith("LINKEDIN_")}
        for key in self._saved:
            del os.environ[key]

    def tearDown(self):
        for key in [k for k in os.environ if k.startswith("LINKEDIN_")]:
            del os.environ[key]
        os.environ.update(self._saved)
        self.dir.cleanup()

    def _write(self, text: str):
        self.env_path.write_text(text, encoding="utf-8")

    def test_parsing(self):
        self._write(
            "# комментарий\n"
            "\n"
            "export LINKEDIN_CLIENT_ID=abc\n"
            'LINKEDIN_CLIENT_SECRET="s3cr#et"\n'
            "LINKEDIN_REDIRECT_URI = http://localhost:9000/cb \n"
        )
        parsed = li._parse_dotenv(self.env_path)
        self.assertEqual(parsed["LINKEDIN_CLIENT_ID"], "abc")
        self.assertEqual(parsed["LINKEDIN_CLIENT_SECRET"], "s3cr#et")
        self.assertEqual(parsed["LINKEDIN_REDIRECT_URI"], "http://localhost:9000/cb")

    def test_bad_line(self):
        self._write("JUST_A_LINE\n")
        with self.assertRaises(li.ConfigError):
            li._parse_dotenv(self.env_path)

    def test_missing_file_is_empty(self):
        self.assertEqual(li._parse_dotenv(Path(self.dir.name) / "nope.env"), {})

    def test_env_overrides_dotenv(self):
        self._write("LINKEDIN_CLIENT_ID=from-file\nLINKEDIN_CLIENT_SECRET=x\n")
        os.environ["LINKEDIN_CLIENT_ID"] = "from-env"
        config = li.Config.from_env(self.env_path)
        self.assertEqual(config.client_id, "from-env")

    def test_missing_credentials(self):
        self._write("LINKEDIN_CLIENT_ID=abc\n")
        with self.assertRaises(li.ConfigError) as ctx:
            li.Config.from_env(self.env_path)
        self.assertIn("LINKEDIN_CLIENT_SECRET", str(ctx.exception))

    def test_bad_api_version(self):
        self._write("LINKEDIN_CLIENT_ID=a\nLINKEDIN_CLIENT_SECRET=b\nLINKEDIN_API_VERSION=2026-05\n")
        with self.assertRaises(li.ConfigError):
            li.Config.from_env(self.env_path)

    def test_http_non_local_rejected(self):
        self._write(
            "LINKEDIN_CLIENT_ID=a\nLINKEDIN_CLIENT_SECRET=b\n"
            "LINKEDIN_REDIRECT_URI=http://example.com/cb\n"
        )
        with self.assertRaises(li.ConfigError):
            li.Config.from_env(self.env_path)

    def test_https_remote_allowed_and_manual(self):
        self._write(
            "LINKEDIN_CLIENT_ID=a\nLINKEDIN_CLIENT_SECRET=b\n"
            "LINKEDIN_REDIRECT_URI=https://example.com/cb\n"
        )
        config = li.Config.from_env(self.env_path)
        self.assertFalse(config.is_local_callback)
        self.assertEqual(config.callback_port, 443)

    def test_redirect_with_query_rejected(self):
        self._write(
            "LINKEDIN_CLIENT_ID=a\nLINKEDIN_CLIENT_SECRET=b\n"
            "LINKEDIN_REDIRECT_URI=http://localhost:1/cb?x=1\n"
        )
        with self.assertRaises(li.ConfigError):
            li.Config.from_env(self.env_path)

    def test_callback_parts(self):
        self._write(
            "LINKEDIN_CLIENT_ID=a\nLINKEDIN_CLIENT_SECRET=b\n"
            "LINKEDIN_REDIRECT_URI=http://127.0.0.1:8123/oauth/cb\n"
        )
        config = li.Config.from_env(self.env_path)
        self.assertTrue(config.is_local_callback)
        self.assertEqual(config.callback_port, 8123)
        self.assertEqual(config.callback_path, "/oauth/cb")


class TestAuthorizeUrl(unittest.TestCase):
    def test_url(self):
        config = li.Config(client_id="cid", client_secret="sec",
                           redirect_uri="http://localhost:8765/callback")
        url = li.build_authorize_url(config, "st4te")
        self.assertTrue(url.startswith(li.AUTHORIZE_URL + "?"))
        self.assertIn("response_type=code", url)
        self.assertIn("client_id=cid", url)
        self.assertIn("state=st4te", url)
        self.assertIn("scope=openid%20profile%20email%20w_member_social", url)
        self.assertIn("redirect_uri=http%3A%2F%2Flocalhost%3A8765%2Fcallback", url)
        self.assertNotIn("sec", url, "client_secret не должен попадать в URL")


class TestCallbackConsumption(unittest.TestCase):
    def _result(self, **kwargs):
        result = li._CallbackResult()
        for key, value in kwargs.items():
            setattr(result, key, value)
        return result

    def test_ok(self):
        self.assertEqual(
            li._consume_callback(self._result(code="c0de", state="s"), "s"), "c0de"
        )

    def test_state_mismatch(self):
        with self.assertRaises(li.AuthError) as ctx:
            li._consume_callback(self._result(code="c", state="other"), "s")
        self.assertIn("CSRF", str(ctx.exception))

    def test_state_missing(self):
        with self.assertRaises(li.AuthError):
            li._consume_callback(self._result(code="c", state=None), "s")

    def test_user_cancelled(self):
        with self.assertRaises(li.AuthError) as ctx:
            li._consume_callback(self._result(error="user_cancelled_login"), "s")
        self.assertIn("отклонили", str(ctx.exception))

    def test_other_error(self):
        with self.assertRaises(li.AuthError):
            li._consume_callback(self._result(error="unauthorized_scope_error"), "s")

    def test_no_code(self):
        with self.assertRaises(li.AuthError):
            li._consume_callback(self._result(state="s"), "s")


class TestCallbackServer(unittest.TestCase):
    """Поднимает реальный локальный сервер и стучится в него."""

    def test_ignores_favicon_then_accepts_callback(self):
        port = _free_port()
        config = li.Config(
            client_id="cid", client_secret="sec",
            redirect_uri=f"http://127.0.0.1:{port}/callback",
        )
        box = {}

        def run():
            try:
                box["result"] = li._wait_for_callback(config, timeout=15)
            except BaseException as exc:  # noqa: BLE001
                box["error"] = exc

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        time.sleep(0.4)

        base = f"http://127.0.0.1:{port}"
        try:
            urllib.request.urlopen(base + "/favicon.ico", timeout=5)
        except urllib.error.HTTPError as exc:
            self.assertEqual(exc.code, 404)

        with urllib.request.urlopen(base + "/callback?code=XYZ&state=st4te", timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            self.assertIn("Готово", resp.read().decode("utf-8"))

        thread.join(timeout=10)
        self.assertNotIn("error", box, str(box.get("error")))
        result = box["result"]
        self.assertEqual(li._consume_callback(result, "st4te"), "XYZ")

    def test_port_busy(self):
        port = _free_port()
        blocker = socket.socket()
        blocker.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        blocker.bind(("127.0.0.1", port))
        blocker.listen(1)
        try:
            config = li.Config(client_id="c", client_secret="s",
                               redirect_uri=f"http://127.0.0.1:{port}/callback")
            with self.assertRaises(li.LinkedInError) as ctx:
                li._wait_for_callback(config, timeout=2)
            self.assertIn("manual", str(ctx.exception))
        finally:
            blocker.close()


class _FlakyServer:
    """Локальный сервер: первые N запросов отдают заданный код, потом 200."""

    def __init__(self, fail_status: int, fail_times: int, retry_after: str = ""):
        self.fail_status = fail_status
        self.fail_times = fail_times
        self.retry_after = retry_after
        self.hits = 0
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.0"

            def _respond(self):
                outer.hits += 1
                if outer.hits <= outer.fail_times:
                    self.send_response(outer.fail_status)
                    if outer.retry_after:
                        self.send_header("Retry-After", outer.retry_after)
                    body = b'{"message":"nope"}'
                else:
                    self.send_response(201)
                    self.send_header("x-restli-id", "urn:li:share:777")
                    body = b'{"ok":true}'
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            do_GET = _respond
            do_POST = _respond

            def log_message(self, *args):
                pass

        self.server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/x"


class TestHttpRequest(unittest.TestCase):
    def setUp(self):
        self._sleep = time.sleep
        time.sleep = lambda _s: None  # не ждём backoff в тестах
        li.time.sleep = lambda _s: None

    def tearDown(self):
        time.sleep = self._sleep
        li.time.sleep = self._sleep

    def test_retries_idempotent_get(self):
        with _FlakyServer(503, 2) as server:
            response = li.http_request("GET", server.url, idempotent=True, max_attempts=4)
            self.assertEqual(response.status, 201)
            self.assertEqual(server.hits, 3)

    def test_does_not_retry_non_idempotent_post(self):
        with _FlakyServer(503, 2) as server:
            response = li.http_request("POST", server.url, idempotent=False, max_attempts=4)
            self.assertEqual(response.status, 503, "POST не должен ретраиться на 5xx")
            self.assertEqual(server.hits, 1)

    def test_gives_up_after_max_attempts(self):
        with _FlakyServer(500, 99) as server:
            response = li.http_request("GET", server.url, idempotent=True, max_attempts=3)
            self.assertEqual(response.status, 500)
            self.assertEqual(server.hits, 3)

    def test_4xx_not_retried(self):
        with _FlakyServer(400, 99) as server:
            response = li.http_request("GET", server.url, idempotent=True, max_attempts=4)
            self.assertEqual(response.status, 400)
            self.assertEqual(server.hits, 1)

    def test_retry_after_parsed(self):
        self.assertEqual(li._retry_after_seconds({"retry-after": "12"}), 12)
        self.assertEqual(li._retry_after_seconds({"retry-after": " 3.7 "}), 3)
        self.assertIsNone(li._retry_after_seconds({"retry-after": "Wed, 21 Oct 2026 07:28:00 GMT"}))
        self.assertIsNone(li._retry_after_seconds({}))

    def test_connection_refused_message_mentions_duplicate_risk(self):
        port = _free_port()  # никто не слушает
        with self.assertRaises(li.LinkedInError) as ctx:
            li.http_request("POST", f"http://127.0.0.1:{port}/x", idempotent=False, max_attempts=2)
        self.assertIn("сетевая ошибка", str(ctx.exception))


class TestExtractUrn(unittest.TestCase):
    def test_from_header(self):
        response = li.HttpResponse(201, {"x-restli-id": "urn:li:share:1"}, b"")
        self.assertEqual(li.LinkedInClient._extract_post_urn(response), "urn:li:share:1")

    def test_from_body(self):
        response = li.HttpResponse(201, {}, b'{"id":"urn:li:ugcPost:2"}')
        self.assertEqual(li.LinkedInClient._extract_post_urn(response), "urn:li:ugcPost:2")

    def test_nothing(self):
        response = li.HttpResponse(201, {}, b"{}")
        with self.assertRaises(li.ApiError):
            li.LinkedInClient._extract_post_urn(response)


class TestDeleteValidation(unittest.TestCase):
    def test_rejects_bad_urn_before_network(self):
        config = li.Config(client_id="c", client_secret="s")
        token = li.Token(access_token="t", expires_at=time.time() + 999, client_id="c")
        client = li.LinkedInClient(config, token)
        for bad in ("urn:li:person:X", "12345", "", "urn:li:share:abc"):
            with self.assertRaises(li.LinkedInError, msg=bad):
                client.delete_post(bad)


class TestClientGuards(unittest.TestCase):
    def test_token_from_other_app_rejected(self):
        config = li.Config(client_id="new-app", client_secret="s")
        token = li.Token(access_token="t", expires_at=time.time() + 999, client_id="old-app")
        with self.assertRaises(li.AuthError) as ctx:
            li.LinkedInClient(config, token)
        self.assertIn("другому приложению", str(ctx.exception))

    def test_headers(self):
        config = li.Config(client_id="c", client_secret="s", api_version="202605")
        token = li.Token(access_token="tok", expires_at=time.time() + 999, client_id="c")
        client = li.LinkedInClient(config, token)
        headers = client._headers(versioned=True, json_body=True)
        self.assertEqual(headers["Authorization"], "Bearer tok")
        self.assertEqual(headers["LinkedIn-Version"], "202605")
        self.assertEqual(headers["X-Restli-Protocol-Version"], "2.0.0")
        self.assertEqual(headers["Content-Type"], "application/json")
        self.assertNotIn("LinkedIn-Version", client._headers(versioned=False))


class TestHelpers(unittest.TestCase):
    def test_redact(self):
        self.assertEqual(li.redact(""), "—")
        self.assertEqual(li.redact("abcd"), "****")
        self.assertTrue(li.redact("a" * 40).startswith("aaaa…"))
        self.assertNotIn("b" * 30, li.redact("b" * 40))

    def test_human_duration(self):
        self.assertEqual(li.human_duration(0), "0 мин.")
        self.assertEqual(li.human_duration(90), "1 мин.")
        self.assertEqual(li.human_duration(3700), "1 ч. 1 мин.")
        self.assertEqual(li.human_duration(90000), "1 дн. 1 ч.")
        self.assertIn("назад", li.human_duration(-100))


class _MockLinkedIn:
    """Локальный сервер, изображающий LinkedIn: token, userinfo, posts."""

    def __init__(self, posts_status: int = 201):
        self.requests = []
        self.posts_status = posts_status
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.0"

            def _record(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b""
                outer.requests.append(
                    {
                        "method": self.command,
                        "path": self.path,
                        "headers": {k.lower(): v for k, v in self.headers.items()},
                        "body": body,
                    }
                )
                return body

            def _send(self, status, payload=b"", extra_headers=()):
                self.send_response(status)
                for key, value in extra_headers:
                    self.send_header(key, value)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                if payload:
                    self.wfile.write(payload)

            def do_POST(self):  # noqa: N802
                body = self._record()
                if self.path.startswith("/oauth/v2/accessToken"):
                    if b"grant_type=authorization_code" not in body:
                        self._send(400, b'{"error":"unsupported_grant_type"}')
                        return
                    self._send(
                        200,
                        json.dumps(
                            {
                                "access_token": "AQV-mock-token",
                                "expires_in": 5184000,
                                "scope": "openid,profile,email,w_member_social",
                            }
                        ).encode(),
                    )
                elif self.path.startswith("/rest/posts"):
                    if outer.posts_status == 201:
                        self._send(201, b"", [("x-restli-id", "urn:li:share:7123456789")])
                    else:
                        self._send(outer.posts_status, b'{"message":"no permission"}')
                elif self.path.startswith("/v2/ugcPosts"):
                    self._send(201, b"", [("x-restli-id", "urn:li:share:legacy1")])
                else:
                    self._send(404, b'{"message":"unknown"}')

            def do_GET(self):  # noqa: N802
                self._record()
                if self.path.startswith("/v2/userinfo"):
                    self._send(
                        200,
                        json.dumps(
                            {"sub": "AbC-1234", "name": "Артём", "email": "a@example.com"}
                        ).encode(),
                    )
                else:
                    self._send(404, b'{"message":"unknown"}')

            def log_message(self, *args):
                pass

        self.server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def find(self, path_prefix: str):
        return [r for r in self.requests if r["path"].startswith(path_prefix)]


class TestEndToEnd(unittest.TestCase):
    """login -> сохранение токена -> userinfo -> публикация, всё на localhost."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self._orig = (li.TOKEN_URL, li.API_BASE, li.secrets.token_urlsafe)
        li.secrets.token_urlsafe = lambda _n=32: "FIXED-STATE"

    def tearDown(self):
        li.TOKEN_URL, li.API_BASE, li.secrets.token_urlsafe = self._orig
        self.dir.cleanup()

    def _config(self, mock) -> li.Config:
        li.TOKEN_URL = mock.base + "/oauth/v2/accessToken"
        li.API_BASE = mock.base
        return li.Config(
            client_id="cid",
            client_secret="sec",
            redirect_uri=f"http://127.0.0.1:{_free_port()}/callback",
            token_path=Path(self.dir.name) / "token.json",
        )

    def _login(self, config) -> li.Token:
        box = {}

        def run():
            try:
                box["token"] = li.login(config, open_browser=False, out=io.StringIO())
            except BaseException as exc:  # noqa: BLE001
                box["error"] = exc

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        time.sleep(0.4)
        url = f"{config.redirect_uri}?code=THE-CODE&state=FIXED-STATE"
        with urllib.request.urlopen(url, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
        thread.join(timeout=10)
        self.assertNotIn("error", box, str(box.get("error")))
        return box["token"]

    def test_full_flow(self):
        with _MockLinkedIn() as mock:
            config = self._config(mock)
            token = self._login(config)

            self.assertEqual(token.access_token, "AQV-mock-token")
            self.assertAlmostEqual(token.seconds_left, 5184000, delta=10)

            exchange = mock.find("/oauth/v2/accessToken")[0]
            self.assertIn(b"code=THE-CODE", exchange["body"])
            self.assertIn(b"client_secret=sec", exchange["body"])
            self.assertIn(
                b"redirect_uri=" + urllib.parse.quote(config.redirect_uri, safe="").encode(),
                exchange["body"],
            )

            store = li.TokenStore(config.token_path)
            store.save(token)
            client = li.LinkedInClient.from_store(config)

            self.assertEqual(client.person_urn(), "urn:li:person:AbC-1234")
            self.assertEqual(store.load().name, "Артём")

            urn = client.create_post("Релиз (v2) — 100% готов", visibility="PUBLIC")
            self.assertEqual(urn, "urn:li:share:7123456789")

            post = mock.find("/rest/posts")[0]
            self.assertEqual(post["headers"]["authorization"], "Bearer AQV-mock-token")
            self.assertEqual(post["headers"]["linkedin-version"], li.DEFAULT_API_VERSION)
            self.assertEqual(post["headers"]["x-restli-protocol-version"], "2.0.0")
            body = json.loads(post["body"].decode("utf-8"))
            self.assertEqual(body["author"], "urn:li:person:AbC-1234")
            self.assertEqual(body["commentary"], r"Релиз \(v2\) — 100% готов")

    def test_legacy_fallback_on_403(self):
        with _MockLinkedIn(posts_status=403) as mock:
            config = self._config(mock)
            token = self._login(config)
            store = li.TokenStore(config.token_path)
            store.save(token)
            client = li.LinkedInClient.from_store(config)

            urn = client.create_post("Текст (со скобкой)")
            self.assertEqual(urn, "urn:li:share:legacy1")
            legacy = json.loads(mock.find("/v2/ugcPosts")[0]["body"].decode("utf-8"))
            commentary = legacy["specificContent"]["com.linkedin.ugc.ShareContent"][
                "shareCommentary"
            ]["text"]
            self.assertEqual(commentary, "Текст (со скобкой)", "в legacy API экранирование не нужно")

    def test_no_fallback_when_disabled(self):
        with _MockLinkedIn(posts_status=403) as mock:
            config = self._config(mock)
            token = self._login(config)
            li.TokenStore(config.token_path).save(token)
            client = li.LinkedInClient.from_store(config)
            with self.assertRaises(li.ApiError) as ctx:
                client.create_post("Текст", allow_legacy_fallback=False)
            self.assertEqual(ctx.exception.status, 403)
            self.assertEqual(mock.find("/v2/ugcPosts"), [])

    def test_expired_token_without_refresh(self):
        with _MockLinkedIn() as mock:
            config = self._config(mock)
            store = li.TokenStore(config.token_path)
            store.save(
                li.Token(access_token="old", expires_at=time.time() - 86400, client_id="cid")
            )
            with self.assertRaises(li.AuthError) as ctx:
                li.LinkedInClient.from_store(config)
            self.assertIn("Marketing Developer Platform", str(ctx.exception))


if __name__ == "__main__":
    unittest.main(verbosity=2)
