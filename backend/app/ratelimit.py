"""A tiny in-process rate limiter for the submit endpoint.

Each POST /v1/reels can spend Groq/LLM credit, and the endpoint has no credential in
front of it any more, so an unbounded caller is a billing risk. This keeps a sliding
window of timestamps per client key.

It is deliberately process-local: the MVP runs a single uvicorn worker, so a shared
store (Redis) would be overkill. If the API is ever scaled to multiple workers, this
stops being a global limit and should be replaced.
"""

from __future__ import annotations

import time
from collections import defaultdict, deque
from threading import Lock


class RateLimiter:
    def __init__(self, limit: int, window_seconds: float = 60.0) -> None:
        self.limit = max(1, limit)
        self.window = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = Lock()

    def check(self, key: str) -> bool:
        """Record a hit for `key` and return False when it is over the limit."""
        now = time.monotonic()
        with self._lock:
            bucket = self._hits[key]
            cutoff = now - self.window
            while bucket and bucket[0] < cutoff:
                bucket.popleft()
            if len(bucket) >= self.limit:
                return False
            bucket.append(now)
            return True
