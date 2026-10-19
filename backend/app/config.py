"""Application configuration, loaded from backend/.env.

All secrets live here and nowhere else. Nothing in this file is ever returned by an
API response - see the /v1/health endpoint, and note that it reports booleans, never
values.
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

BACKEND_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = BACKEND_DIR.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(REPO_ROOT / ".env", BACKEND_DIR / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ---- general ---------------------------------------------------------
    app_name: str = "ResourceTracker API"
    version: str = "0.1.0"
    env: str = Field(default="dev")

    # Comma separated. Empty means "same origin only", which is what you want in prod.
    # The default is empty rather than "*": the Android client does not use CORS at
    # all, so a wildcard only widens the reachable surface for a browser.
    cors_origins: str = Field(default="")

    # ---- database --------------------------------------------------------
    database_url: str = Field(default=f"sqlite+aiosqlite:///{BACKEND_DIR / 'storage' / 'resourcetracker.sqlite3'}")

    # ---- identity --------------------------------------------------------
    # Authentication has been removed. This is a single-tenant deployment: every
    # request is this one user, and the id is stamped onto every card and job row so
    # the ownership columns and query filters keep working.
    local_user_id: str = Field(default="local-user")

    # ---- abuse limits ----------------------------------------------------
    # Each POST /v1/reels can spend Groq/LLM credit, so the submit endpoint is capped
    # per client. Process-local: the MVP runs one worker; move to Redis to scale out.
    submit_rate_limit_per_minute: int = Field(default=30)

    # ---- Groq LLM -------------------------------------------------------
    groq_api_key: str = Field(default="")
    # Verified against the live Groq model list. llama-3.3-70b-versatile and
    # llama-3.1-8b-instant have been retired and now return 404 model_not_found.
    # Other confirmed options: qwen/qwen3.8-27b, allam-2-7b.
    groq_model: str = Field(default="openai/gpt-oss-120b")
    groq_timeout_seconds: float = Field(default=60.0)
    groq_max_retries: int = Field(default=2)

    # ---- transcript providers -------------------------------------------
    # Which provider handles new jobs. See app/providers/registry.py.
    transcript_provider: str = Field(default="manual")
    # Comma separated allow-list, in priority order. The first available one wins.
    transcript_provider_fallbacks: str = Field(default="")

    # --- whisper provider (optional) ---
    # Speech-to-text also runs on Groq, so this normally reuses GROQ_API_KEY.
    # Confirm the exact model with GET /v1/models before changing it; whisper-1 is
    # an OpenAI model name and does not exist on Groq.
    whisper_api_key: str = Field(default="")
    whisper_model: str = Field(default="whisper-large-v3")
    whisper_max_bytes: int = Field(default=25 * 1024 * 1024)  # provider hard limit

    # --- oembed provider (optional) ---
    oembed_timeout_seconds: float = Field(default=15.0)

    # --- YouTube provider (optional) ---
    # https://console.cloud.google.com/apis/credentials
    # Only needed for richer metadata (description, tags, duration). The YouTube
    # provider works without it using the free oEmbed endpoint (title + author).
    youtube_api_key: str = Field(default="")

    # --- Instagram metadata (optional, but see README) -----------------------
    # Instagram serves no unauthenticated metadata endpoint any more: api.instagram.com
    # /oembed 302s to an HTML page, the ?__a=1 JSON shape answers 500, and /embed/ is a
    # login wall. The only supported route left is the Meta Graph oEmbed endpoint, which
    # requires a Meta app token.
    #
    # Either "<app-id>|<app-secret>" (an app access token) or a user access token from a
    # Meta app that has the Instagram oEmbed permission. Only reels the app is allowed
    # to read come back; see README, "Instagram metadata", for the app setup.
    instagram_access_token: str = Field(default="")
    instagram_graph_version: str = Field(default="v21.0")

    # ---- media extraction ------------------------------------------------
    # Hard cap on a downloaded media file. Reels are short so this is generous.
    max_media_bytes: int = Field(default=200 * 1024 * 1024)
    download_timeout_seconds: float = Field(default=60.0)
    # How many frames to sample for OCR. More frames = better recall, more cost.
    ocr_frame_count: int = Field(default=8)
    ocr_enabled: bool = Field(default=True)
    ffmpeg_binary: str = Field(default="ffmpeg")

    # ---- job runner ------------------------------------------------------
    # In-process worker threads. For real scale move this to Celery/RQ; the job
    # interface (app/pipeline/runner.py) is what would be swapped.
    job_worker_threads: int = Field(default=2)
    job_max_attempts: int = Field(default=2)
    job_timeout_seconds: float = Field(default=600.0)

    # ---- temp storage ----------------------------------------------------
    # Every job gets a private directory under here, deleted when the job ends.
    # A janitor sweep also removes anything left behind by a hard crash.
    workspace_dir: Path = Field(default=BACKEND_DIR / "storage" / "workspace")
    janitor_interval_seconds: int = Field(default=900)
    janitor_max_age_seconds: int = Field(default=3600)

    @field_validator("database_url", mode="before")
    @classmethod
    def _default_database_url(cls, value: object) -> object:
        # `DATABASE_URL=` (blank) in a .env must fall back to the bundled SQLite file,
        # not reach create_async_engine(""), which fails at startup.
        if value is None or (isinstance(value, str) and not value.strip()):
            return f"sqlite+aiosqlite:///{BACKEND_DIR / 'storage' / 'resourcetracker.sqlite3'}"
        return value

    @field_validator("workspace_dir", mode="before")
    @classmethod
    def _expand_workspace(cls, value: object) -> object:
        # A blank WORKSPACE_DIR must NOT become Path(""), which resolves to the
        # current directory - the janitor would then sweep (delete) every directory
        # older than an hour beneath the backend CWD.
        if value is None or (isinstance(value, str) and not value.strip()):
            return BACKEND_DIR / "storage" / "workspace"
        return Path(str(value)).expanduser()

    @property
    def cors_origin_list(self) -> list[str]:
        if not self.cors_origins.strip():
            return []
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]

    @property
    def provider_chain(self) -> list[str]:
        """Active provider first, then any configured fallbacks, de-duplicated.

        `oembed` is always appended as the last resort: it returns public metadata
        (title, caption, creator, hashtags) for the vast majority of reels, so a job
        becomes a metadata-only card instead of a hard failure whenever nothing more
        capable is configured. When `oembed` itself finds nothing the job fails with
        a precise reason.
        """
        chain = [self.transcript_provider]
        chain += [
            name.strip()
            for name in self.transcript_provider_fallbacks.split(",")
            if name.strip()
        ]
        if "oembed" not in chain:
            chain.append("oembed")
        seen: set[str] = set()
        ordered: list[str] = []
        for name in chain:
            if name and name not in seen:
                seen.add(name)
                ordered.append(name)
        return ordered

    @property
    def llm_configured(self) -> bool:
        return bool(self.groq_api_key.strip())

    @property
    def effective_whisper_key(self) -> str:
        """Whisper runs on Groq too, so a separate key is optional."""
        return (self.whisper_api_key.strip() or self.groq_api_key.strip())

    def ensure_directories(self) -> None:
        self.workspace_dir.mkdir(parents=True, exist_ok=True)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    settings = Settings()
    settings.ensure_directories()
    return settings
