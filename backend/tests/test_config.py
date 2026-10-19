"""Config safety nets.

These pin the two blank-value footguns that a copied `.env.example` used to trigger:
an empty DATABASE_URL crashed engine creation, and an empty WORKSPACE_DIR resolved to
the current directory, which the janitor would then sweep.
"""

from __future__ import annotations


def _settings(**overrides):
    from app.config import Settings

    # Paths built by hand so the test does not depend on the developer's real .env.
    return Settings(**overrides)


def test_blank_database_url_falls_back_to_the_bundled_sqlite_file():
    settings = _settings(database_url="")
    assert settings.database_url.startswith("sqlite+aiosqlite:///")
    assert "resourcetracker.sqlite3" in settings.database_url


def test_blank_workspace_dir_falls_back_to_an_absolute_storage_path():
    settings = _settings(workspace_dir="")
    assert settings.workspace_dir.is_absolute()
    assert settings.workspace_dir.name == "workspace"
    assert settings.workspace_dir != settings.workspace_dir.parent


def test_cors_defaults_to_same_origin_only():
    settings = _settings(cors_origins="")
    assert settings.cors_origin_list == []


def test_cors_splits_and_trims_configured_origins():
    settings = _settings(cors_origins="http://localhost:5173, https://app.example ")
    assert settings.cors_origin_list == ["http://localhost:5173", "https://app.example"]
