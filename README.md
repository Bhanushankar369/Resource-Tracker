# ResourceTracker

Save an Instagram Reel from the share sheet. The app sends only the URL to a backend
that turns the reel into a study card. Nothing is downloaded onto the phone, and
transcripts, media, and OCR output are deleted server-side as soon as the card exists.

Two halves:

```
Android app  ──POST /v1/reels──▶  FastAPI  ──▶  TranscriptProvider chain
(Kotlin, Room,                      │                │
 WorkManager)                        │                ├─ manual   (user pasted it)
                                    │                ├─ oembed   (public metadata)
                                    │                └─ whisper  (audio → text)
                                    │
                                    ├─ frame sample + OCR      (temp workspace)
                                    ├─ Groq → KnowledgeCard
                                    └─ delete the workspace   ← always
```

---

## The thing to understand first

**There is no public API that turns an Instagram Reel URL into a video file.**
Instagram's Graph API only exposes media for Business/Creator accounts linked to a
Page the caller owns, and even then not as a raw mp4. Scraping the HTML for
`video_url`, or reusing a logged-in session, violates the Instagram Terms of Use and
is the single most common reason apps like this get rejected from the Play Store.

So the URL alone is often not enough, and this codebase treats that as the normal
case rather than pretending otherwise:

- `TranscriptProvider` is an interface. Adding a real source is a new class plus one
  line in a registry.
- The chain tries providers in order and takes the first one with content, including
  a partial one. A caption-only card beats an error screen.
- A user can always unblock a job by pasting the transcript in the app.
- When extraction is partial, the LLM is told so and is instructed to stay within what
  it was actually given. A confident summary of a reel the backend never read is the
  worst possible outcome for this product.

If you have a legitimate source — your own Instagram Business account via the Graph
API, or a media resolver you are authorised to use — implement `TranscriptProvider`
and nothing else changes.

---

## Layout

```
.
├── app/                                  Android app (com.resourcetracker)
├── backend/                              FastAPI service
│   ├── app/
│   │   ├── providers/                    ← the abstraction
│   │   │   ├── base.py                     TranscriptProvider + TranscriptResult
│   │   │   ├── registry.py                 chain + fallback
│   │   │   ├── manual_paste.py
│   │   │   ├── oembed.py
│   │   │   └── whisper.py
│   │   ├── pipeline/
│   │   │   ├── runner.py                   provider → OCR → Groq → card
│   │   │   ├── workspace.py                temp dirs + guaranteed deletion + janitor
│   │   │   ├── ocr.py                      ffmpeg frame sampling + tesseract
│   │   │   └── summarizer.py               Groq call + extractive fallback
│   │   ├── services/                       persistence + ownership rules
│   │   ├── identity.py                     single-user id (auth removed)
│   │   ├── db.py  models.py  api.py  main.py
│   │   └── config.py
│   └── tests/                              102 tests
├── .env                                   Android build config (no secrets)
└── .env.example
```

---

## Running it

### 1. Backend

```bash
cd backend
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt      # Windows
# .venv/bin/pip install -r requirements.txt        # macOS / Linux

cp .env.example .env                               # then edit it
.venv\Scripts\python -m uvicorn app.main:app --reload --port 8000
```

Minimum to get a working card with no API keys at all:

```
TRANSCRIPT_PROVIDER=manual
```

For real summaries, add a key from <https://console.groq.com/keys> and check the
model name against the live list — Groq retires models and a retired name returns
`404 model_not_found` rather than a helpful error:

```bash
curl -H "Authorization: Bearer $GROQ_API_KEY" https://api.groq.com/openai/v1/models
```

`GET /v1/health` tells you which providers are available and whether the LLM is
configured, and nothing else. It reports booleans, never secret values.

### 2. Android

```bash
# repo-root .env
BACKEND_BASE_URL=http://10.0.2.2:8000     # 10.0.2.2 is the emulator's view of your host
```

```bash
./gradlew :app:assembleDebug              # or open in Android Studio and press Run
```

Requires JDK 17+. `gradle.properties` points `org.gradle.java.home` at Android
Studio's JBR, so a default `JAVA_HOME` of 11 will not break the build.

**Emulator vs device:** `10.0.2.2` only works on the emulator. On a physical device
use your machine's LAN IP (`http://192.168.x.x:8000`) and keep the app and server on
the same network. Cleartext HTTP is allowed only for the local development hosts
listed in `res/xml/network_security_config.xml`.

### Identity (no sign-in)

Authentication has been removed. This is a single-tenant deployment: there is no
sign-in screen, no Google client id, and no bearer token. Every request operates as
the one configured local user (`LOCAL_USER_ID`, default `local-user`) via
`app/identity.py`, and that id is still stamped onto every card and job row so the
ownership columns and query filters keep working.

Because there is no credential, do not expose this backend to the public internet as
is: anyone who can reach it can read and write the one account. Keep it on localhost,
a LAN, or behind your own authenticating proxy.

`POST /v1/reels` is rate-limited per client (`SUBMIT_RATE_LIMIT_PER_MINUTE`) because
each submission can spend LLM credit.

---

## Testing

```bash
cd backend
.venv\Scripts\python -m pytest                        # 102 tests, no network
.venv\Scripts\python -m pytest tests/test_workspace_cleanup.py -v
```

The suite is offline and deterministic: no Groq calls, no frame sampling, an isolated
SQLite file and workspace per test.

What the tests are actually for, beyond coverage:

| Area | Why |
| --- | --- |
| `test_workspace_cleanup.py` | A cleanup regression is silent. Nothing crashes and the app keeps working; the only symptom is reels accumulating on a disk forever. These assert on the filesystem. |
| `test_providers.py` | Pins the contract a future provider author relies on: unavailable providers are skipped, not fatal; one failure does not mask a working provider; partial-but-nonempty is accepted. |
| `test_identity.py` | Authentication is gone, so a request with no credential must succeed, and the single configured local user must be the id stamped onto every row. |
| `test_config.py` | A blank `DATABASE_URL` or `WORKSPACE_DIR` in a copied `.env` must fall back to safe defaults rather than an empty engine URL or the current directory. |
| `test_api.py` | Ownership is still the boundary the service layer enforces, and card ids travel in URLs. Another user's card must be indistinguishable from one that does not exist. Also: a client may set a review status but may not write card content, and submit is rate-limited. |
| `test_summarizer.py` | A bad model response must not produce a broken card, and a missing key must degrade to something honest rather than erroring. |

### On a device

1. Start the backend, then `adb reverse tcp:8000 tcp:8000` and use
   `BACKEND_BASE_URL=http://127.0.0.1:8000`.
2. Share a Reel to ResourceTracker from the Instagram app.
3. Watch it: the card appears as `PENDING` with a progress notification, then fills in.
4. Review it, or snooze it to another day, or delete it.
5. Check the reminder: 6 PM daily, rescheduled after reboot.
6. Confirm the temp policy: `backend/storage/workspace/` is empty after every job.

If a job fails because no provider could read the reel, the error says so and tells
the user to paste a transcript. That is the designed path, not a gap.

---

## Design decisions worth arguing with

**Groq, not Gemini/Claude.** One key, an OpenAI-compatible API, `json_object` response
mode, and speech-to-text on the same account. The model name is configuration, not
code.

**An in-process worker pool, not Celery.** Two threads is plenty for an MVP and
avoids a Redis dependency. `PipelineRunner.submit` is the only thing to reimplement to
scale; the pipeline is a pure function of a `JobRequest`.

**SQLite by default.** `DATABASE_URL` is the only thing to change for Postgres.

**Deletion is enforced twice.** `JobWorkspace` is a context manager whose `finally`
removes the directory, and a `Janitor` sweeps anything older than an hour. Either
alone can fail: a `SIGKILL`, a power cut, or a full disk skips the `finally`. The
grace period exists so a long-running job is not deleted underneath itself. Job
diagnostics deliberately store counters, never content — `Job.notes` records things
like `ocr_lines:31`, because strings end up in logs and the point is that reel
content does not persist.

**A client-generated card id.** The phone inserts locally and reconciles later, so
sending a URL never depends on a network round trip, and a retry after a dropped
response does not create a duplicate. Soft delete keeps the row, so a local delete
survives a later sync instead of the card reappearing.

**`SCHEDULE_EXACT_ALARM`, not `USE_EXACT_ALARM`.** `USE_EXACT_ALARM` is granted only
to alarm-clock and calendar apps and gets you a Play policy prompt. `SCHEDULE_EXACT_ALARM`
is user-revocable, which is the right trade for a reminder, with `setAndAllowWhileIdle`
as the fallback when the user declines. WorkManager is not used for the 6 PM alarm —
periodic work has a 15-minute floor and is not exact.

---

## Before this ships

- [ ] Decide how this single-tenant backend is kept private. There is no credential in
      front of it, so do not expose it to the public internet without an authenticating
      proxy in front.
- [ ] Move SQLite to Postgres and set a real `CORS_ORIGINS` allow-list (the default is
      empty = same-origin only).
- [x] Add rate limiting on `POST /v1/reels` — it enqueues LLM calls, so it is a
      direct path to someone else's bill. (`SUBMIT_RATE_LIMIT_PER_MINUTE`, process-local.)
- [ ] Implement a real `TranscriptProvider` for your actual authorised media source.
- [ ] Confirm the Play policy position on the OCR/media pipeline before submitting.
- [ ] Rotate the Groq key that has been sitting in this shell's environment.
- [ ] Rotate the Meta `INSTAGRAM_ACCESS_TOKEN` that was committed to a local `.env`;
      put the new one in `backend/.env` only.
