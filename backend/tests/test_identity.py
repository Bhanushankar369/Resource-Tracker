"""Tests for the single-user identity.

Authentication has been removed, so the important behaviours are now positive:
every request resolves to the same configured local user, and no credential is
required to reach the API.
"""

from __future__ import annotations

from app.config import Settings
from app.identity import current_user_id


def _settings(**overrides) -> Settings:
    base = {"local_user_id": "test-user", "env": "test"}
    base.update(overrides)
    return Settings(**base)


async def test_current_user_is_the_configured_local_user():
    settings = _settings(local_user_id="household")
    assert await current_user_id(settings=settings) == "household"


async def test_current_user_defaults_to_local_user(monkeypatch):
    monkeypatch.delenv("LOCAL_USER_ID", raising=False)
    settings = Settings(env="test")
    assert await current_user_id(settings=settings) == "local-user"


async def test_no_credential_is_required(client):
    """A request with no Authorization header must succeed. This is the whole point
    of removing the auth layer."""
    response = await client.get("/v1/cards")
    assert response.status_code == 200
