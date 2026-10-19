"""Provider registry and the fallback chain.

The chain is what makes a missing provider a non-event: the active provider is tried
first, and if it reports itself unavailable or has nothing to give, the next one is
tried. Only when every provider in the chain has been exhausted does the job fail.
"""

from __future__ import annotations

import logging
from pathlib import Path

from app.providers.base import (
    ProviderError,
    ProviderUnavailable,
    TranscriptProvider,
    TranscriptResult,
)
from app.providers.manual_paste import ManualPasteProvider
from app.providers.oembed import OEmbedProvider
from app.providers.whisper import WhisperProvider
from app.providers.youtube import YouTubeProvider, is_youtube_url
from app.providers.x_twitter import XTwitterProvider, is_x_url

logger = logging.getLogger(__name__)

_PROVIDER_TYPES: dict[str, type[TranscriptProvider]] = {
    ManualPasteProvider.name: ManualPasteProvider,
    OEmbedProvider.name: OEmbedProvider,
    WhisperProvider.name: WhisperProvider,
    YouTubeProvider.name: YouTubeProvider,
    XTwitterProvider.name: XTwitterProvider,
}


def available_provider_names() -> list[str]:
    return sorted(_PROVIDER_TYPES)


def build_provider(name: str) -> TranscriptProvider:
    """Instantiate a provider by name.

    Raises:
        KeyError: unknown provider name. This is a configuration error and should
            surface loudly at startup rather than silently degrading.
    """
    try:
        provider_type = _PROVIDER_TYPES[name]
    except KeyError as exc:
        raise KeyError(
            f"Unknown transcript provider {name!r}. Known providers: "
            f"{', '.join(available_provider_names())}"
        ) from exc
    return provider_type()


def describe_providers() -> list[dict[str, object]]:
    """Health payload for every known provider, including its availability."""
    described: list[dict[str, object]] = []
    for name in available_provider_names():
        try:
            provider = build_provider(name)
        except Exception as exc:  # pragma: no cover - defensive
            described.append({"name": name, "available": False, "description": str(exc)})
            continue
        try:
            available = provider.is_available()
        except Exception as exc:  # is_available must never raise
            logger.warning("is_available() raised for provider %s: %s", name, exc)
            available = False
        described.append({**provider.describe(), "available": available})
    return described


def auto_route_chain(chain: list[str], url: str) -> list[str]:
    """Reorder the provider chain based on the URL's platform.

    The configured chain is the default, but a YouTube URL should try the
    YouTube provider first, and an X/Twitter URL should try X first, even
    when the user has not configured them as the primary provider. The
    platform-specific provider is prepended and deduped.
    """
    preferred: str | None = None
    if is_youtube_url(url):
        preferred = YouTubeProvider.name
    elif is_x_url(url):
        preferred = XTwitterProvider.name

    if preferred is None or (bool(chain) and preferred == chain[0]):
        return chain

    # Prepend the platform provider, remove it from its original position.
    reordered = [preferred] + [name for name in chain if name != preferred]
    return reordered


async def run_provider_chain(
    chain: list[str],
    reel_url: str,
    workspace: Path,
) -> TranscriptResult:
    """Try each provider in order and return the first usable result.

    A result is accepted when it has content, even a partial one - a caption-only
    summary is far better than an error screen. Only an entirely empty result moves
    on to the next provider.

    The chain is auto-routed: a YouTube URL tries the YouTube provider first, an
    X/Twitter URL tries the X provider first, regardless of the configured primary.
    """
    if not chain:
        raise ProviderError(
            "No transcript provider is configured. Set TRANSCRIPT_PROVIDER in backend/.env"
        )

    failures: list[str] = []

    effective_chain = auto_route_chain(chain, reel_url)
    for name in effective_chain:
        try:
            provider = build_provider(name)
        except Exception as exc:  # noqa: BLE001
            # A misconfigured or broken provider must not take down a job that a
            # later entry in the chain could handle. Includes a provider whose
            # constructor raises, which would otherwise escape as a job crash.
            logger.error("Provider %s could not be constructed: %s", name, exc)
            failures.append(f"{name}: could not be constructed ({exc})")
            continue

        try:
            if not provider.is_available():
                logger.info("Provider %s is unavailable, skipping", name)
                failures.append(f"{name}: not configured")
                continue
        except Exception as exc:
            logger.warning("is_available() raised for %s: %s", name, exc)
            failures.append(f"{name}: availability check failed")
            continue

        try:
            result = await provider.fetch(reel_url, workspace)
        except ProviderUnavailable as exc:
            logger.info("Provider %s unavailable at call time: %s", name, exc)
            failures.append(f"{name}: {exc}")
            continue
        except ProviderError as exc:
            logger.warning("Provider %s could not read this reel: %s", name, exc)
            failures.append(f"{name}: {exc}")
            continue
        except Exception as exc:  # noqa: BLE001 - a provider must not kill the job
            logger.exception("Provider %s raised an unexpected error", name)
            failures.append(f"{name}: unexpected error: {exc}")
            continue

        if result.has_content:
            if failures:
                result.warnings.extend(failures)
            return result

        logger.info("Provider %s returned nothing usable", name)
        failures.append(f"{name}: no content extracted")

    raise ProviderError(
        "No provider could produce a transcript. Tried: "
        + "; ".join(failures)
        + ". The user can unblock this by pasting the transcript in the app."
    )
