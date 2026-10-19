# Security notes

Short and specific to this project. There is no bug-bounty programme; if you find a
problem, do not open a public issue with an exploit in it.

## Threat model in one paragraph

ResourceTracker is a **single-tenant** tool: one person, one account, one backend on
localhost or a trusted LAN. Authentication was deliberately removed, so the backend
has **no credential in front of it**. That is the single most important fact here:

> Do not expose the backend to the public internet as is. Anyone who can reach it can
> read and write the one account. Put it behind your own authenticating proxy, a VPN,
> or keep it on localhost.

## Where secrets live

- **Server secrets** (`GROQ_API_KEY`, `WHISPER_API_KEY`, `INSTAGRAM_ACCESS_TOKEN`)
  belong in `backend/.env` only. That file is git-ignored.
- **Never** put a real secret in the repo-root `.env`. Anything there is read at
  build time and **baked into the APK's `BuildConfig`**, where it is trivially
  extractable with `unzip`. Only `BACKEND_BASE_URL` and `APP_ENV` are expected there.
- The API never returns a secret. `GET /v1/health` reports booleans about
  configuration, never values.

If a secret has ever been pasted into a tracked file or a chat, treat it as
compromised and rotate it (Groq console / Meta developer console). `.env` is ignored,
but an exposed value is still exposed.

## Controls that are in place

| Area | Control |
| --- | --- |
| Abuse | `POST /v1/reels` is rate-limited per client (`SUBMIT_RATE_LIMIT_PER_MINUTE`). Process-local: it is a per-worker limit, not a global one. |
| CORS | Defaults to empty (same-origin only). The Android client does not use CORS. Never set `*` in production. |
| Ownership | Even without auth, `user_id` is stamped on every card/job row and enforced in `app/services/cards.py`. Another user's row is indistinguishable from a missing one. |
| Mass assignment | A client may set a review status but may never write card content; unknown statuses are ignored, not applied. |
| Temp media | Transcripts, audio/video and OCR frames live in a per-job workspace that is deleted when the job ends, with a janitor sweep as the crash backstop. Job diagnostics store counters (`ocr_lines:31`), never content. |
| Transport | Cleartext HTTP is allowed only for the local development hosts in `res/xml/network_security_config.xml`. |
| Android backup | Only the Room card cache is backed up; there is no session/token store to leak. |

## Known gaps (accepted for the MVP)

- No authentication (see above). This is the whole posture.
- Rate limiting is per-process. Multiple `uvicorn` workers each get their own window.
- SQLite and the in-process job runner assume a single process.
