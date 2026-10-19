"""End-to-end API tests.

Covers the path the phone actually takes: submit a reel, poll the job, list cards,
then review / snooze / delete. Authentication has been removed, so these tests run
with no credentials at all. Ownership is still the security boundary maintained
inside the service layer; it is exercised in the service-level test below.
"""

from __future__ import annotations

import uuid

import pytest

from app.providers.oembed import OEmbedProvider

TRANSCRIPT = (
    "Three things I wish I knew before starting to run. First, go slowly for the first "
    "two weeks. Second, buy shoes that fit now, not shoes that fit later. Third, run "
    "easy most days and hard twice a week."
)


async def _settle(runtime, timeout: float = 10.0) -> None:
    """Wait for submitted jobs to finish and their results to be persisted."""
    await runtime.runner.drain(timeout=timeout)


async def test_health_reports_configuration_without_leaking_secrets(client):
    response = await client.get("/v1/health")
    assert response.status_code == 200

    body = response.json()
    assert body["status"] == "ok"
    assert isinstance(body["llm_configured"], bool)
    assert "auth_configured" not in body
    assert "allow_dev_auth" not in body

    raw = response.text
    for secret_marker in ("api_key", "groq_api_key", "client_secret", "password"):
        assert secret_marker not in raw


async def test_submit_works_without_any_credential(client, runtime):
    """Authentication is gone, so a bare submit must be accepted."""
    response = await client.post(
        "/v1/reels",
        json={"client_card_id": str(uuid.uuid4()), "reel_url": "https://instagram.com/reel/x/"},
    )
    assert response.status_code == 202
    await _settle(runtime)


async def test_submit_rejects_a_non_http_url(client):
    response = await client.post(
        "/v1/reels",
        json={"client_card_id": str(uuid.uuid4()), "reel_url": "javascript:alert(1)"},
    )
    assert response.status_code == 422


async def test_full_lifecycle(client, runtime, reel_url):
    card_id = str(uuid.uuid4())

    accepted = await client.post(
        "/v1/reels",
        json={"client_card_id": card_id, "reel_url": reel_url, "transcript_override": TRANSCRIPT},
    )
    assert accepted.status_code == 202
    job_id = accepted.json()["job_id"]
    assert accepted.json()["card_id"] == card_id

    await _settle(runtime)

    job = await client.get(f"/v1/jobs/{job_id}")
    assert job.status_code == 200
    body = job.json()
    assert body["status"] == "succeeded"

    card = body["card"]
    assert card is not None
    assert card["status"] == "PENDING"
    assert card["reel_url"] == reel_url
    assert card["title"]
    assert card["summary"]

    listed = await client.get("/v1/cards")
    assert listed.status_code == 200
    assert [c["id"] for c in listed.json()] == [card_id]

    reviewed = await client.patch(f"/v1/cards/{card_id}", json={"status": "REVIEWED"})
    assert reviewed.status_code == 200
    assert reviewed.json()["status"] == "REVIEWED"
    assert reviewed.json()["reviewed_at"] is not None

    snoozed = await client.patch(
        f"/v1/cards/{card_id}", json={"status": "SNOOZED", "snooze_until_epoch_day": 20340}
    )
    assert snoozed.json()["status"] == "SNOOZED"
    assert snoozed.json()["snooze_until_epoch_day"] == 20340

    deleted = await client.delete(f"/v1/cards/{card_id}")
    assert deleted.status_code == 204

    after = await client.get("/v1/cards")
    assert after.json() == [], "a deleted card must not be listed"


async def test_a_shared_reel_with_no_transcript_still_becomes_a_readable_card(
    client, runtime, reel_url, monkeypatch
):
    """The share-sheet path: no transcript, so only the public reel metadata.

    Pins what the card detail screen renders for that case - the creator's handle,
    the caption as the description, the hashtags, and the reel link the "Open in
    Instagram" button resolves.
    """
    caption = "Three ways to finish a side project #buildinpublic #focus"
    provider = OEmbedProvider(
        settings=runtime.settings, access_token="token"
    )
    monkeypatch.setattr(
        provider,
        "_fetch_json",
        lambda _url: {
            "title": caption,
            "author_name": "Mark S",
            "author_url": "https://www.instagram.com/marks/",
            "thumbnail_url": "https://example.invalid/thumb.jpg",
        },
    )
    monkeypatch.setattr("app.providers.registry.build_provider", lambda name: provider)

    card_id = str(uuid.uuid4())
    accepted = await client.post(
        "/v1/reels",
        json={"client_card_id": card_id, "reel_url": reel_url},
    )
    assert accepted.status_code == 202

    await _settle(runtime)

    listed = await client.get("/v1/cards")
    card = next(c for c in listed.json() if c["id"] == card_id)

    assert card["status"] == "PENDING"
    assert card["reel_url"] == reel_url
    assert card["creator_username"] == "marks"
    assert card["description"] == caption
    assert card["hashtags"] == ["buildinpublic", "focus"]
    assert card["provider"] == "oembed"
    assert card["thumbnail_url"] == "https://example.invalid/thumb.jpg"
    assert card["title"], "a metadata-only card must still be named"


async def test_resubmitting_the_same_card_id_is_idempotent(client, runtime, reel_url):
    card_id = str(uuid.uuid4())
    payload = {"client_card_id": card_id, "reel_url": reel_url, "transcript_override": TRANSCRIPT}

    first = await client.post("/v1/reels", json=payload)
    assert first.status_code == 202

    second = await client.post("/v1/reels", json=payload)
    assert second.status_code == 200, "a duplicate submit should not be a fresh job"
    assert second.json()["job_id"] == first.json()["job_id"]

    listed = await client.get("/v1/cards")
    assert len(listed.json()) == 1
    await _settle(runtime)


async def test_client_cannot_inject_content_or_an_arbitrary_status(client, runtime, reel_url):
    """A client may set a review status. It may not write card content."""
    card_id = str(uuid.uuid4())
    await client.post(
        "/v1/reels",
        json={"client_card_id": card_id, "reel_url": reel_url, "transcript_override": TRANSCRIPT},
    )
    await _settle(runtime)

    original = (await client.get(f"/v1/cards/{card_id}")).json()

    response = await client.patch(
        f"/v1/cards/{card_id}",
        json={
            "status": "REVIEWED",
            "summary": "pwned",
            "title": "pwned",
            "reel_url": "https://evil.example/reel/1/",
            "provider": "forged",
        },
    )
    assert response.status_code == 200

    body = response.json()
    assert body["status"] == "REVIEWED"
    assert body["summary"] == original["summary"], "summary was overwritten from the client"
    assert body["title"] == original["title"]
    assert body["reel_url"] == original["reel_url"]
    assert body["provider"] == original["provider"]


async def test_client_cannot_mark_a_card_pending(client, runtime, reel_url):
    card_id = str(uuid.uuid4())
    await client.post(
        "/v1/reels",
        json={"client_card_id": card_id, "reel_url": reel_url, "transcript_override": TRANSCRIPT},
    )
    await _settle(runtime)

    # Move it to a state the client is allowed to set, so a later PENDING attempt is
    # observable. A card that is already PENDING could not show the difference.
    reviewed = await client.patch(f"/v1/cards/{card_id}", json={"status": "REVIEWED"})
    assert reviewed.json()["status"] == "REVIEWED"

    response = await client.patch(f"/v1/cards/{card_id}", json={"status": "PENDING"})
    assert response.status_code == 200
    assert response.json()["status"] == "REVIEWED", "a client talked the card back to PENDING"


async def test_ownership_is_still_enforced_in_the_service_layer(runtime):
    """There is only one API user now, but the ownership columns and filters remain.

    A row owned by a different user id must stay invisible to the service layer, so
    the boundary is preserved if authentication is ever reintroduced.
    """
    from app.db import Card
    from app.models import CardStatus
    from app.services import cards as card_service

    other_id = str(uuid.uuid4())
    async with runtime.session_factory() as session:
        session.add(
            Card(
                id=other_id,
                user_id="someone-else",
                reel_url="https://instagram.com/reel/other/",
                title="Other",
                summary="Other",
                status=CardStatus.PENDING,
            )
        )
        await session.commit()

        assert await card_service.owned_card(session, other_id, "test-user") is None
        assert await card_service.owned_card(session, other_id, "someone-else") is not None
        assert await card_service.list_cards(session, "test-user") == []


async def test_unknown_card_and_job_are_404(client):
    assert (await client.get(f"/v1/cards/{uuid.uuid4()}")).status_code == 404
    assert (await client.get(f"/v1/jobs/{uuid.uuid4()}")).status_code == 404


async def test_unreadable_reel_still_produces_a_usable_card(client, runtime, reel_url, monkeypatch):
    """No provider can read the reel and nothing was pasted: the card must survive.

    This is the normal Instagram case, so it must not end as FAILED. A card the user
    can see, review and open is what the MVP needs; an error screen is not.
    """

    # The oembed fallback would try a live Instagram fetch for the fake shortcode,
    # which is exactly what these tests must not do. Short-circuiting the network
    # keeps the chain deterministic: every provider is now a dead end.
    def _no_network(_url, _timeout, _headers=None):
        from app.providers.base import ProviderError as PE

        raise PE("network unreachable in tests")

    monkeypatch.setattr("app.providers.oembed._fetch", _no_network)

    card_id = str(uuid.uuid4())
    accepted = await client.post(
        "/v1/reels",
        json={
            "client_card_id": card_id,
            "reel_url": reel_url,
            "description": "A reel about building habits slowly #focus",
            "creator": "@some.page",
        },
    )
    job_id = accepted.json()["job_id"]
    await _settle(runtime)

    job = (await client.get(f"/v1/jobs/{job_id}")).json()
    assert job["status"] == "succeeded"
    assert job["error"] is None

    card = job["card"]
    assert card["status"] == "PENDING"
    assert card["last_error"] is None
    # The caption and the creator are the whole point of the fallback.
    assert card["creator_username"] == "some.page"
    assert card["hashtags"] == ["focus"]
    assert card["title"]
    assert card["summary"]
    assert card["reel_url"] == reel_url


async def test_status_filter(client, runtime, reel_url):
    for index in range(2):
        await client.post(
            "/v1/reels",
            json={
                "client_card_id": str(uuid.uuid4()),
                "reel_url": reel_url,
                "transcript_override": f"transcript number {index}",
            },
        )
    await _settle(runtime)

    pending = await client.get("/v1/cards", params={"status": "PENDING"})
    assert pending.status_code == 200
    assert isinstance(pending.json(), list)


async def test_submit_is_rate_limited(client, runtime, reel_url, monkeypatch):
    """The submit endpoint spends LLM credit, so an unbounded caller must be stopped."""
    from app.ratelimit import RateLimiter

    runtime.submit_limiter = RateLimiter(limit=2)

    def _payload():
        return {"client_card_id": str(uuid.uuid4()), "reel_url": reel_url}

    assert (await client.post("/v1/reels", json=_payload())).status_code == 202
    assert (await client.post("/v1/reels", json=_payload())).status_code == 202

    third = await client.post("/v1/reels", json=_payload())
    assert third.status_code == 429
    await _settle(runtime)


async def test_resubmit_reuses_a_card_that_has_no_job(client, runtime, reel_url):
    """A card committed without its job row must not cause a primary-key crash."""
    from app.db import Card
    from app.models import CardStatus

    card_id = str(uuid.uuid4())
    async with runtime.session_factory() as session:
        session.add(
            Card(
                id=card_id,
                user_id="test-user",
                reel_url="https://instagram.com/reel/orphan/",
                title="Orphan",
                summary="Orphan",
                status=CardStatus.QUEUED,
            )
        )
        await session.commit()

    response = await client.post(
        "/v1/reels",
        json={"client_card_id": card_id, "reel_url": reel_url, "transcript_override": TRANSCRIPT},
    )
    assert response.status_code == 202, "an existing card with no job should get a new job"
    assert response.json()["card_id"] == card_id
    await _settle(runtime)

    card = (await client.get(f"/v1/cards/{card_id}")).json()
    assert card["reel_url"] == reel_url, "the reused card should point at the new reel"


async def test_submit_card_id_owned_by_another_user_is_a_conflict(client, runtime, reel_url):
    """A client-supplied id that collides with another owner's row must not 500."""
    from app.db import Card
    from app.models import CardStatus

    card_id = str(uuid.uuid4())
    async with runtime.session_factory() as session:
        session.add(
            Card(
                id=card_id,
                user_id="someone-else",
                reel_url="https://instagram.com/reel/other/",
                title="Other",
                summary="Other",
                status=CardStatus.PENDING,
            )
        )
        await session.commit()

    response = await client.post(
        "/v1/reels", json={"client_card_id": card_id, "reel_url": reel_url}
    )
    assert response.status_code == 409
