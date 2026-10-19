"""Provider that uses the transcript the user pasted in the app.

Always available, needs no credentials, and is the reason the MVP is testable
end-to-end: a user shares a reel, the active provider cannot read it, and the user
pastes the text. It also serves as the deterministic provider in tests.

The override is an instance field, not a module global, so two users' jobs running
at the same time cannot see each other's pasted text. The pipeline constructs this
provider directly with the text and bypasses the registry when an override is
present.
"""

from __future__ import annotations

from pathlib import Path
from typing import ClassVar

from app.providers.base import TranscriptProvider, TranscriptResult


class ManualPasteProvider(TranscriptProvider):
    name: ClassVar[str] = "manual"
    description: ClassVar[str] = "Transcript pasted by the user in the app"

    def __init__(self, override: str = "") -> None:
        self._override = override

    def is_available(self) -> bool:
        return True

    async def fetch(self, reel_url: str, workspace: Path) -> TranscriptResult:
        text = self._override.strip()
        if not text:
            return TranscriptResult.not_available(
                self.name,
                "no transcript was supplied for this job",
            )
        return TranscriptResult(provider=self.name, transcript=text, is_partial=False)
