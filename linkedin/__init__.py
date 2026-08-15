"""Интеграция с официальным LinkedIn API (OAuth 2.0 + Posts API)."""

from .linkedin_api import (  # noqa: F401
    ApiError,
    AuthError,
    Config,
    ConfigError,
    LinkedInClient,
    LinkedInError,
    RateLimitError,
    Token,
    TokenStore,
    escape_little_text,
    login,
)

__version__ = "1.0.0"
