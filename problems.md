# problems.md

Every technical problem hit while building ResourceTracker, what caused it, how it was
solved, and why each technical decision was made.

Written from the actual build log, not reconstructed from a clean design. Roughly half
of these were my own mistakes, which is why the "Cause" column says so where it does.

---

## Table of contents

- [A. Environment and toolchain](#a-environment-and-toolchain)
- [B. Android compile errors](#b-android-compile-errors)
- [C. Android problems that lint and the manifest audit caught](#c-android-problems-that-lint-and-the-manifest-audit-caught)
- [D. Android architecture problems (constraints, not bugs)](#d-android-architecture-problems-constraints-not-bugs)
- [E. Backend problems](#e-backend-problems)
- [F. Test-infrastructure problems](#f-test-infrastructure-problems)
- [G. Flaws in my own tests](#g-flaws-in-my-own-tests)
- [H. Technical decisions and why](#h-technical-decisions-and-why)
- [I. Verification performed](#i-verification-performed)
- [J. Known remaining limitations](#j-known-remaining-limitations)

---

## A. Environment and toolchain

### A1. Gradle build failed immediately â€” JDK too old

**Symptom.** The build failed before compiling anything. The machine's default
`JAVA_HOME` pointed at Java 11.

**Cause.** AGP 8.7 requires JDK 17 or newer. Java 11 is not supported at all, and
Android Studio's bundled JDK is not used automatically by the command line.

**Solution.** Set `org.gradle.java.home` in `gradle.properties` to Android Studio's
JBR, which is JDK 21. This makes the correct JDK the default for both the IDE and any
plain `./gradlew` invocation, so the build does not depend on the shell environment.

```
# gradle.properties
org.gradle.java.home=C\:\\Program Files\\Android\\Android Studio\\jbr
```

**Why not just export `JAVA_HOME`?** Because it only works for the shell that sets it.
A CI job, a new terminal, or a teammate would hit the same wall. Pinning it in the
project makes the requirement self-documenting.

### A2. The default `java` on PATH is 11, so every command needed a preamble

**Cause.** Same root cause as A1, but it kept recurring: every single Gradle command
had to be prefixed with `$env:JAVA_HOME=...`.

**Solution.** Kept `org.gradle.java.home` in `gradle.properties` (so the project is
self-sufficient) and also used the explicit `JAVA_HOME` prefix during the session for
defence in depth. The build works either way now.

### A3. Git repository is rooted one directory above the project

**Symptom.** `git status` from the project folder reported the whole user profile as
the working tree, with no commits.

**Cause.** Android Studio initialised the repository at `C:\Users\bhanu` rather than at
`C:\Users\bhanu\AndroidStudioProjects\ResourceTracker`.

**Solution.** Left it alone but noted it, because "fixing" it means deleting a `.git`
directory in a parent folder that may be tracking other things. Worth deciding
deliberately before the first commit, not as a side effect of a build fix.

### A4. Backend model default was a retired Groq model

**Symptom.** Live smoke test against the real Groq API returned:

```
Error code: 404 - The model `llama-3.3-70b-versatile` does not exist
or you do not have access to it.
```

**Cause.** Groq retires models without a shim. `llama-3.3-70b-versatile` was the value
I had written as a default from memory.

**Solution.** Asked the API what actually exists instead of guessing again:

```bash
curl -H "Authorization: Bearer $GROQ_API_KEY" https://api.groq.com/openai/v1/models
```

The live list contained `openai/gpt-oss-120b`, `openai/gpt-oss-20b`,
`qwen/qwen3.8-27b`, `allam-2-7b`, `whisper-large-v3`, `whisper-large-v3-turbo`. Set
the default to `openai/gpt-oss-120b` and **verified** JSON mode works on it, since the
whole summariser depends on `response_format={"type":"json_object"}`:

```
openai/gpt-oss-120b => OK : {"title":"Consistency Beats Intensity", ...}
qwen/qwen3.8-27b    => OK : {...}
allam-2-7b          => OK : {...}
```

**Why this matters beyond one string.** A retired model fails as a 404 with a helpful
message, but only once per call, retried three times. The README now tells users to
check `GET /v1/models` before changing the model, rather than trusting a default.

---

## B. Android compile errors

### B1. KSP failed: invalid Room query

**Symptom.** Room's compiler rejected a DAO query referencing a column that did not
exist.

**Cause.** A query used `SELECT DISTINCT topic`, but `topics` is stored as a JSON string
in a single column, not as a table. There is no `topic` column to be distinct over.

**Solution.** Removed the query. If topic filtering is needed later it has to be a real
many-to-many table, not a `DISTINCT` over a JSON blob.

**Why it was invisible.** The rest of the file compiled fine, and the error surfaced
only in the KSP task. Room validates queries at compile time, which is a good thing â€”
this class of bug cannot reach a device.

### B2. `GetGoogleIdOption` / `GoogleIdTokenCredential` unresolved

**Symptom.**

```
e: Unresolved reference 'GetGoogleIdOption'
e: Unresolved reference 'GoogleIdTokenCredential'
e: Unresolved reference 'idToken'
```

**Cause.** I assumed Credential Manager's Google ID classes lived in
`androidx.credentials`. They do not â€” that package only has `CredentialManager`,
`GetCredentialRequest`, and the exception types. The Google ID classes ship in a
separate artifact under a completely different package:

```
com.google.android.libraries.identity.googleid.GetGoogleIdOption
com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
```

**Solution.** Added `com.google.android.libraries.identity.googleid:googleid:1.1.1` and
fixed the imports to the real package.

**How it was diagnosed.** First guessed the dependency was simply not resolving, and
checked `:app:dependencies`. It *was* on the classpath:

```
+--- com.google.android.libraries.identity.googleid:googleid:1.1.1
|    +--- androidx.credentials:credentials:1.3.0-beta01 -> 1.3.0 (*)
```

So the dependency was fine and the import was wrong. I then unzipped the AAR and listed
the classes to get the ground truth rather than trying more names.

### B3. Duplicate import left behind by a bad edit

**Symptom.** `Unresolved reference 'converter'` in `di/Modules.kt`, reported at line 17.

**Cause.** My own mistake. An earlier edit *added* the correct import
(`com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory`) at
line 7 but never removed the incorrect original at line 17
(`retrofit2.converter.kotlinx.serialization.asConverterFactory`). Two imports of the
same symbol, one of which does not exist.

**Solution.** Deleted the bad line.

**Worth remembering.** The error pointed at the *wrong* import, not the right one, and
the "fix" I was about to make (switching converters) would have been a wild goose chase
against a stale line. Reading the whole import block first, rather than reacting to one
line number, is what actually solved it.

### B4. `PendingAction.Snoozed` constructor mismatch

**Symptom.**

```
e: Inapplicable candidate(s): constructor(untilEpochDay: Long): PendingAction.Snoozed
```

**Cause.** Half of the codebase was migrated from `Int` to `Long` for epoch days (to
match `java.time.LocalDate.toEpochDay()` and avoid 2038 problems), but the
`PendingAction.decode()` parser still called `toIntOrNull()`. It parsed a `Long` into
an `Int`, so no constructor matched.

**Solution.** `toIntOrNull()` â†’ `toLongOrNull()`.

**Lesson.** A type migration is not done when the type name changes; it is done when
every parse and constructor agrees. Compile errors are the *good* case â€” the dangerous
case is a silent truncation in a value that still type-checks.

### B5. `String?` passed where `String` was required

**Symptom.**

```
e: Argument type mismatch: actual type is 'kotlin.String?', but 'kotlin.String' was expected.
```

**Cause.** `inputData.getString(KEY_CARD_ID)` returns `String?`. It was passed straight
into `buildForegroundInfo(cardId: String, ...)`.

**Solution.** `cardId.orEmpty()`.

**Why `orEmpty()` and not an early return.** This was in `getForegroundInfo()`, which
must return a `ForegroundInfo` and has no sensible "failed" value. An empty card id
degrades the notification label, which is harmless, rather than crashing a foreground
service that WorkManager started.

### B6. Application class and composable collided on one name

**Symptom.** `MainActivity` referenced `ResourceTrackerApp`, which resolved ambiguously
between the `Application` subclass and a top-level composable of the same name.

**Cause.** I named the composable `ResourceTrackerApp` because it is the app's root UI.
The `Application` class is also `ResourceTrackerApp`. Same package, same name, two very
different things.

**Solution.** Moved the composable to `ui/AppRoot.kt` and named it
`ResourceTrackerRoot`, leaving `ResourceTrackerApp` as the `Application` class alone.

**Lesson, and the reason it is called out.** While fixing the *compile* error I removed
the wrong file. The composable went to `ui/AppRoot.kt` correctly, but
`ResourceTrackerApp.kt` â€” the actual `Application` class â€” was never recreated. See C1:
the app compiled, built, and linted "successfully" while being unable to launch. A
rename that resolves a symbol collision needs a check that both symbols still exist.

---

## C. Android problems that lint and the manifest audit caught

These are the dangerous class: no compiler error, no runtime error at build time, and
a broken app. Every one of these was found by running `lintDebug` and diffing the
*merged* manifest, not by reading the code.

### C1. `MissingClass`: the Application class did not exist â€” the app could not launch

**Symptom.** Lint:

```
[Error] MissingClass @ AndroidManifest.xml:35
Class referenced in the manifest, `com.resourcetracker.ResourceTrackerApp`,
was not found in the project or the libraries
```

**Cause.** See B6. The manifest declared `android:name=".ResourceTrackerApp"`, but the
file did not exist. The build, the tests, and the lint *task* all passed â€” only the lint
*report* flagged it.

**Solution.** Recreated `ResourceTrackerApp.kt`:

```kotlin
@HiltAndroidApp
class ResourceTrackerApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

**Why both halves are mandatory.**

- `@HiltAndroidApp` generates the singleton component every `@AndroidEntryPoint` and
  `@HiltWorker` depends on. Without it: crash on launch.
- `Configuration.Provider` supplies WorkManager's configuration. The manifest *removes*
  `androidx.work.WorkManagerInitializer` precisely so this configuration is used. Without
  the `HiltWorkerFactory`, every one of the four `@HiltWorker`s fails to instantiate at
  runtime.

**The real lesson.** `assembleDebug` producing an APK is not evidence that an Android
app works. This APK installed and would have died instantly. The evidence that mattered
was lint's report body, which is easy to ignore because the task exits 0.

### C2. `MissingBinding`: a bare `Context` injection

**Symptom.**

```
error: [Dagger/MissingBinding] android.content.Context cannot be provided
without an @Provides-annotated method.
```

**Cause.** `SessionStore` had:

```kotlin
class SessionStore @Inject constructor(
    private val context: Context,   // no @ApplicationContext
)
```

Hilt can provide `@ApplicationContext Context` but not an unqualified `Context`, so the
graph could not be satisfied. It only surfaced *because* of C1 â€” restoring the
`Application` class made Hilt validate the whole graph for the first time.

**Solution.** `@ApplicationContext private val context: Context`.

**Note.** Every other injection in the project was already qualified. This one slipped
through because `Context` is a plausible-looking constructor parameter that a linter
cannot flag without full graph analysis.

### C3. `FullBackupContent` (Fatal) Ã— 4

**Symptom.** Four fatal lint errors in `backup_rules.xml` and `data_extraction_rules.xml`:
`datastore/session.preferences_pb` and `session.xml` are not in an included path.

**Cause.** The rules had both an `<include>` and an `<exclude>`:

```xml
<include domain="database" path="resourcetracker.db" />
<exclude domain="file" path="datastore/session.preferences_pb" />
```

**Once any `<include>` is present, everything not listed is excluded implicitly.** The
`<exclude>` was therefore redundant, and lint treats a redundant exclude as an error.

**Solution.** Deleted the `<exclude>` elements and documented why in the file:

```xml
<full-backup-content>
    <include domain="database" path="resourcetracker.db" />
</full-backup-content>
```

**Why this matters and is not just pedantry.** The intent â€” "never back up the Google ID
token" â€” is still fully satisfied, because the include-list is exhaustive. A reader
looking for an explicit exclusion would not find one and might "fix" it by adding the
excludes back, reintroducing the fatal errors. The comment is load-bearing.

### C4. `SpecifyForegroundServiceType` (Fatal) â€” uploads would fail on Android 14+

**Symptom.** Missing `dataSync` foreground service type in the manifest.

**Cause.** `IngestReelWorker` calls `setForeground(...)` and the app declares
`FOREGROUND_SERVICE_DATA_SYNC`, but no *service* declared the matching type. On Android
14+ (API 34) starting a foreground service whose type is not declared throws.

**Solution.** Merged the type onto WorkManager's own service:

```xml
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge" />
```

**Why `tools:node="merge"` is needed.** WorkManager's AAR already declares this service.
Declaring it again in the app manifest is a *merge* of attributes, not an addition, and
without the marker the merger treats the higher-priority declaration as an override of
the whole element.

### C5. `NewApi`: `canScheduleExactAlarms` requires API 31, minSdk is 26

**Symptom.** Lint: field requires API level 31 (current min is 26).

**Cause.** `AlarmManager.canScheduleExactAlarms()` is API 31+.

**Solution.** Guarded by version, and â€” importantly â€” the guard returns `true` on older
versions rather than `false`:

```kotlin
get() = alarmManager?.let { am ->
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms()
    else true
} ?: false
```

**Why `true` and not `false`.** Before API 31 an exact alarm needs no special grant at
all. Returning `false` would have made the app silently downgrade to inexact alarms on
every device below Android 12, purely because of an API-level check.

### C6. `InlinedApi`: `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` is API 31+

**Symptom.** Lint: field requires API level 31.

**Cause.** `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` does not exist below API 31. In
`openExactAlarmSettings()` the `Intent` was built unconditionally, inside a
`runCatching`.

**Solution.** Checked the SDK level *before* constructing the intent, not around the
`startActivity` call:

```kotlin
val canRequestDirectly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
val launched = if (canRequestDirectly) { /* try direct */ } else { false }
if (!launched) { /* fall back to app details page */ }
```

**Why `runCatching` was not good enough.** The failure here is reading a *class field
that does not exist* â€” a `NoSuchFieldError` during class verification â€” rather than an
`ActivityNotFoundException` from `startActivity`. Wrapping only the `startActivity`
call would leave the field reference outside the guarded region.

### C7. `MissingPermission` Ã— 2 â€” notifications

**Symptom.** `notify(...)` requires `POST_NOTIFICATIONS`, which is user-revocable.

**Cause.** The permission check existed but lived in a property
(`canPostNotifications`), and lint's dataflow analysis does not follow a property into a
different method.

**Solution.** Extracted a `post()` helper that re-checks the permission inline at the
call site and catches `SecurityException` explicitly, with a targeted
`@SuppressLint("MissingPermission")` and a comment explaining the suppression is
deliberate, not lazy:

```kotlin
@SuppressLint("MissingPermission")
private fun post(id: Int, notification: Notification): Boolean {
    if (!canPostNotifications) return false
    return try {
        NotificationManagerCompat.from(context).notify(id, notification)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Notification permission revoked before posting", e)
        false
    }
}
```

**Why the explicit catch, not `runCatching`.** The original code used `runCatching`,
which catches `Throwable` and made the intent unclear. More importantly: this code runs
inside a `BroadcastReceiver`. An uncaught exception there is swallowed by the system and
the reminder silently never appears â€” the exact failure a user would report as "the app
is broken" with no log to show why.

### C8. `StateFlowValueCalledInComposition` Ã— 2

**Symptom.** `MainActivity.kt:46-47`: "StateFlow.value should not be called within
composition".

**Cause.** Inside `setContent { }`, the code read `sharedText.value` and `startTab.value`
directly.

**Two problems, and the second is the worse one.** It is a correctness bug, not just a
style warning: reading `.value` in composition does not subscribe, so the UI would never
recompose when a share intent arrived. The reel URL would arrive and the add sheet would
never open.

**Solution.**

```kotlin
val incomingSharedText by sharedText.collectAsState()
val requestedTab by startTab.collectAsState()
```

Writes stay in the `onStartTabConsumed` callback, which is correct â€” that is an event,
not a read.

### C9. `ConstantLocale` Ã— 2 â€” dates frozen to the locale at class-load time

**Symptom.** "Assigning `Locale.getDefault()` to a final static field is suspicious."

**Cause.** Two places computed a `DateTimeFormatter` once into a file-level `val` /
object property:

```kotlin
object ReminderTime { val label: String = localTime.format(...Locale.getDefault()) }
internal val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
```

**Consequences.** The locale is captured when the class loads, so a user who changes
their phone's language keeps seeing the old formatting until the process is killed. For
`ReminderTime.label` this is user-visible: it appears in the reminder notification and the
settings screen.

**Solution.** Converted both to per-call computations with a shared explanation, and
switched `ReminderTime` to a properly localised pattern rather than a hard-coded
`"h:mm a"`:

```kotlin
val label: String
    get() = localTime.format(
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    )
```

**Why `ofLocalizedTime` rather than keeping `"h:mm a"`.** The hard-coded pattern forces
12-hour US formatting on every locale. `FormatStyle.SHORT` renders `18:00` in most of
Europe and `6:00 PM` in the US, which is what a user in either place expects.

### C10. `PluralsCandidate` Ã— 2 â€” "%d cards" is not a plural

**Symptom.** Lint on `reminder_title_plural` and `reminder_more`.

**Cause.** Count-bearing strings as `<string>` with an `if (count == 1)` branch in
Kotlin. English needs two forms; Polish, Arabic, and Russian need more.

**Solution.** Converted to `<plurals>` with `quantity="one"`/`"other"`, and used
`resources.getQuantityString(...)` in `ReminderNotifier`. This also removed the
hand-rolled singular/plural branching from Kotlin.

### C11. `ObsoleteSdkInt` and `RedundantLabel`

- `mipmap-anydpi-v26` is unnecessary when `minSdk = 26`. Renamed to `mipmap-anydpi`.
- `android:label` on `MainActivity` duplicated the application label. Removed it.

Trivial, but they were the last two non-dependency warnings, and an empty warning list is
a much better signal than a noisy one.

### C12. `CredentialManagerSignInWithGoogle` â€” verified false positive

**Symptom.** "Use of `:googleid` classes without use of `GoogleIdTokenCredential`."

**Cause.** Lint's heuristic does not recognise a safe cast spread across lines. The code
*does* check it:

```kotlin
val googleCredential = credential as? GoogleIdTokenCredential
    ?: return@withContext AuthResult.Error("Unexpected credential type: ...")
```

**Solution.** `@SuppressLint("CredentialManagerSignInWithGoogle")` with a comment
recording that it was checked manually, so a future reader knows the suppression is
evidence-based rather than an attempt to make a warning disappear.

---

## D. Android architecture problems (constraints, not bugs)

### D1. There is no way to get a reel's video from a URL

**The problem.** Instagram's Graph API exposes media only for Business/Creator accounts
linked to a Page the caller owns, and even then not as a raw mp4. A plain Reel URL
cannot be turned into a video on-device or by a backend, without scraping.

**Options considered.**

1. *Scrape the HTML for `video_url`* â€” violates Instagram's ToS. This is the single most
   common reason apps like this get rejected from the Play Store.
2. *Reuse a logged-in session cookie* â€” same problem, worse, and it breaks constantly.
3. *Ship the app with an authorised source* â€” correct, but requires a real Business
   account and more work than an MVP.

**Decision.** Treat "the URL is not enough" as the *normal case* rather than an error
path, and make the substitution point explicit:

- `TranscriptProvider` is an interface. A real source is a new class plus one registry line.
- The chain takes the first provider that returns *any* content, including partial. A
  caption-only card is far better than an error screen.
- A user can always paste a transcript in the app, which always works.
- When extraction is partial, the prompt is told so and is explicitly forbidden from
  describing visuals or speech it was not given.

**Why the last point is the important one.** The worst possible outcome for this product
is a confident, well-written summary of a reel the backend never actually read. The user
cannot tell it apart from a real summary. So the prompt says, in the system message:
*"If the source material does not contain something, you leave it out rather than
guessing."*

### D2. WorkManager cannot do a 6 PM reminder

**Problem.** `PeriodicWorkRequest` has a hard 15-minute floor and is explicitly *not*
exact. "Every day at 6 PM" is a promise about wall-clock time.

**Decision.** `AlarmManager.setExactAndAllowWhileIdle` for the alarm, handing off to a
`WorkManager` one-shot for the database read and notification.

- The alarm does nothing itself. Broadcast receivers have tight execution limits, so
  keeping the receiver trivial is what makes it reliable.
- `AlarmManager` survives Doze in a way periodic work does not.

### D3. `USE_EXACT_ALARM` is a Play policy problem

**Problem.** There are two exact-alarm permissions. `USE_EXACT_ALARM` is auto-granted
but Google Play restricts it to apps whose core function is an alarm clock or calendar.
A reel summariser is neither, so declaring it risks rejection.

**Decision.** Declare `SCHEDULE_EXACT_ALARM` only, which is user-revocable, and degrade
to `setAndAllowWhileIdle` (still Doze-capable, just inexact) when the user has not
granted it. The manifest comment records the reasoning so nobody "helpfully" adds
`USE_EXACT_ALARM` later.

**Verification.** The merged manifest was checked directly:

```
SCHEDULE_EXACT_ALARM     present
FOREGROUND_SERVICE_DATA_SYNC  present
USE_EXACT_ALARM          ABSENT   â† confirmed, not assumed
```

### D4. Secrets in `BuildConfig` are extractable from the APK

**Problem.** The app needs a backend URL and a Google *web* client ID, both compiled in.
`BuildConfig` is a plaintext constant in the APK, readable by anyone who unzaps it.

**Decision.** Split configuration by sensitivity:

- Root `.env` â€” non-secret build config (`BACKEND_BASE_URL`, `GOOGLE_SERVER_CLIENT_ID`).
- `backend/.env` â€” `GROQ_API_KEY` and everything else, read only by the server process.
- The **Groq key is never in the Android build at all.** The app cannot call Groq
  directly; it only talks to the backend.

**Residual risk, stated honestly.** The Google web client ID *is* in the APK. That is
acceptable: it is a public identifier, and the security of sign-in comes from the
audience check and token verification, not from hiding the client ID. The client secret
is never present anywhere in the app.

### D5. Losing a card because the network was down

**Problem.** If the app inserts a card only after the server accepts it, a bad
connection loses the reel the user just shared.

**Decision.** Offline-first with client-generated IDs:

- The phone generates a UUID and writes the Room row immediately. The card is on screen
  before any network call.
- The server is told `client_card_id` and honours it, so no re-keying when the job
  finishes.
- Local actions are stored in a `pendingAction` column and replayed.
- Merge logic never resurrects a card the user has locally `REVIEWED` or `DELETED`.
- Delete is a soft delete (row kept, status `DELETED`) so a later sync does not bring
  the card back.

### D6. A 404 on a delete is not a failure

**Symptom.** A delete could fail with a 404 if the row was already gone â€” e.g. after a
double tap, or a retry after a partial success.

**Decision.** Treat 404 as "already done" for idempotent actions. The user's intent was
achieved; an error would be a lie.

---

## E. Backend problems

### E1. `new_job_id` imported from the wrong module

**Symptom.** `ImportError: cannot import name 'new_job_id' from 'app.pipeline.runner'`.

**Cause.** It is defined in `app/pipeline/workspace.py`, next to the workspace code that
uses it, and I imported it from `runner.py`.

**Solution.** Fixed the import to point at `workspace.py`. Both are plausible homes for
it, which is why it is worth knowing where it ended up.

### E2. `Request | None` is not a valid FastAPI dependency type

**Symptom.**

```
fastapi.exceptions.FastAPIError: Invalid args for response field!
Hint: check that starlette.requests.Request | None is a valid Pydantic field type.
```

**Cause.** In `auth.py` I annotated a dependency parameter as
`request: Request | None = None`, intending "optional request". FastAPI resolves a bare
`Request` annotation specially, but the `| None` union makes it try to build a response
model from it.

**Solution.** Annotated it as `Request` with no union. FastAPI always injects it, so
"optional" was never needed.

**Bonus.** While fixing this I also deleted a redundant manual `Authorization` header
parse. `HTTPBearer` already reads that header; parsing it twice was dead code that
looked load-bearing.

### E3. A 204 endpoint cannot declare a `-> None` return type

**Symptom.**

```
AssertionError: Status code 204 must not have a response body
```

**Cause.** `async def delete_card(...) -> None` made FastAPI generate a response model
for a status code that forbids a body.

**Solution.** `response_class=Response` and an explicit empty `Response` return.

### E4. Job results were never written to the database

**Symptom.** Jobs reported `queued` forever. The database was never updated. The
warning that gave it away:

```
RuntimeWarning: coroutine 'Runtime.persist' was never awaited
```

**Cause.** `PipelineRunner._finish` is a synchronous `add_done_callback`, but
`Runtime.persist` is `async`. Calling it produced a coroutine that was never awaited, so
the database write simply never happened. No exception, no log â€” a silent total failure
of the feature.

**Solution.**

```python
returned = on_done(outcome)
if inspect.isawaitable(returned):
    persist_task = task.get_loop().create_task(returned, name=f"persist:{outcome.job_id}")
    self._persist_tasks.add(persist_task)
    persist_task.add_done_callback(self._persist_tasks.discard)
```

Plus a `drain()` method so tests (and any caller that needs the card immediately) can
wait for in-flight jobs *and* their persistence.

**Why this is the scariest bug in the project.** Everything else that failed, failed
loudly. This one produced a working-looking API that returned well-formed responses and
silently stored nothing. It was only caught because an end-to-end test asserted on
persisted card content rather than on the HTTP response.

### E5. Sync function calling an async ORM method

**Symptom.**

```
AttributeError: 'coroutine' object has no attribute 'stage'
```

**Cause.** In `services/jobs.py` I wrote `complete()` as a plain `def` but used
`session.get(...)`, which is a coroutine on `AsyncSession`. Every caller got a coroutine
object back.

**Solution.** Made `complete()` async.

**Pattern worth repeating.** Pick one: either make the whole call chain async, or use
`sync_sessionmaker` and a blocking driver. Mixing the two in a helper is how this
happens.

### E6. Referenced a `JobStage` member that does not exist

**Symptom.** `AttributeError: PROCESSING`.

**Cause.** `job_status()` compared against `JobStage.PROCESSING`. The enum is
`QUEUED, FETCHING, EXTRACTING, OCR, SUMMARISING, DONE, FAILED`.

**Solution.** The stage enum records *which step was reached*, which is not the same
thing as whether the job succeeded, so the status is derived from `finished_at` and
`error` instead:

```python
if job.finished_at is None:
    return "queued"
return "failed" if job.error else "succeeded"
```

**Why derived, not stored.** Storing a status column alongside a stage column guarantees
they can drift, and they did: the pipeline set outcome status `succeeded` while the
`Job.stage` was `DONE`, so the API reported `done` where the client expected
`succeeded`. Deriving it from two facts that cannot disagree removes the category of
bug. The unused `mark_running()` helper was deleted rather than left as a trap.

### E7. Every `dev:` token mapped to the same user

**Symptom.** A test asserting another user gets a 404 got a **200**.

**Cause.** The dev-auth escape hatch ignored everything after `dev:` and returned a
single hardcoded `settings.dev_user_id`. So `dev:alice` and `dev:bob` were the same
account.

**Why this is a real bug and not just a bad test.** It made the entire ownership
boundary untestable. Every "can user A read user B's card" test would have passed
vacuously against a single shared user, so the security check could have been completely
absent and the suite would still have been green.

**Solution.** `dev:<id>` now maps to `<id>`:

```python
if settings.allow_dev_auth and token.startswith("dev:"):
    requested = token[len("dev:"):].strip()
    if not requested:
        raise AuthError("A dev token must look like 'dev:<user-id>'")
    logger.warning("DEV AUTH ACCEPTED for user %r. Never enable this in production.", requested)
    return requested
```

Safe precisely because `ALLOW_DEV_AUTH` already grants total access, and the backend
logs a warning on every request that uses it. It is now possible to write a meaningful
cross-user isolation test â€” and one exists.

### E8. A nonce check that a token without a nonce passed

**Symptom.** My own test `test_missing_nonce_claim_is_rejected` failed with
`DID NOT RAISE`.

**Cause.**

```python
if nonce is not None and claims.get("nonce") not in (None, nonce):
    raise AuthError("Token nonce does not match")
```

`claims.get("nonce")` returns `None` when the claim is absent, and `None` was in the
allowed set. A token with **no nonce at all** satisfied the check.

**Solution.**

```python
if nonce is not None and claims.get("nonce") != nonce:
    raise AuthError("Token nonce does not match")
```

**Why this matters.** The nonce is what proves a token was minted for *this* sign-in
attempt. Allowing its absence removes the check entirely while still appearing to
perform it. This is exactly the kind of bug that a "does it return the right status
code" test would never find â€” the test had to assert on the *absence* of a claim.

### E9. `WhisperProvider` could not be constructed, which would crash a job

**Symptom.** Health reported:

```
{"name": "whisper", "available": false,
 "description": "WhisperProvider.__init__() missing 1 required positional argument: 'settings'"}
```

**Cause.** `WhisperProvider.__init__(self, settings: Settings)` required an argument,
but `registry.build_provider(name)` instantiates every provider with no arguments so
it can report availability in `/v1/health`.

**The dangerous part.** `run_provider_chain` caught only `KeyError`:

```python
try:
    provider = build_provider(name)
except KeyError as exc:
```

A `TypeError` from a constructor would not be caught, would propagate, and would
**fail the whole job** â€” even if a perfectly good fallback provider was next in the
chain. A provider listed in a config file that had a small bug would have taken down
every job for users who had a working primary provider.

**Solution.** Two changes:

```python
except Exception as exc:  # noqa: BLE001
    logger.error("Provider %s could not be constructed: %s", name, exc)
    failures.append(f"{name}: could not be constructed ({exc})")
    continue
```

and `def __init__(self, settings: Settings | None = None)` with `settings or get_settings()`.

**Generalised lesson.** In a plugin chain, *any* exception from constructing or running
a plugin is a plugin failure, not a system failure. A chain that only handles the
exceptions you predicted is not a chain.

### E10. A user-facing error was reported as an internal error

**Symptom.** Card showed: `internal error: no provider could produce a transcript...`

**Cause.** `ProviderError` â€” the single most *expected* failure in the whole system, and
the one the UI is designed around â€” was caught by the generic `except Exception` in
`Pipeline.run`, which is reserved for genuine bugs.

**Solution.** Handle it separately, log it without a traceback, and pass the message
through verbatim:

```python
except ProviderError as exc:
    logger.info("[%s] no content could be extracted: %s", request.job_id, exc)
    return JobOutcome(status="failed", stage=JobStage.FAILED, error=str(exc))
```

and the generic handler now emits a message with no internal detail:

```
"Something went wrong while processing this reel. Please try again."
```

**Why.** "Internal error: <exception repr>" leaks implementation detail to a user and
tells them nothing actionable. The provider error ends with "The user can unblock this
by pasting the transcript in the app", which is a real next step.

### E11. Truncation left trailing whitespace in card titles

**Symptom.** A test asserted `coerced["title"] == coerced["title"].strip()` and failed.

**Cause.** `_coerce` stripped *then* sliced to 200 characters. Slicing a string that
ends in a space produces a title ending in a space â€” visible in the UI.

**Solution.** Slice first, then strip:

```python
"title": title[:200].strip(),
```

### E12. A module-global override would have leaked one user's transcript to another

**Problem.** `manual_paste.py` originally stored the pasted transcript in a module-level
`_override` global, so the provider could be constructed with no arguments like the
others.

**Why it is unacceptable.** With `job_worker_threads = 2` and multiple users, user A's
pasted transcript would be visible to user B's concurrent job. The docstring even
claimed the opposite ("Scoped to the job rather than to the process"), which is a
comment that actively hides a bug.

**Solution.** Made the override an instance field and have the pipeline construct the
provider directly, bypassing the registry, when an override is present:

```kotlin
ManualPasteProvider(request.transcript_override)
```

**Lesson.** A convenience that lets one class conform to an interface's construction
contract is not worth weakening a per-request isolation guarantee. And a comment that
claims a property the code does not have is worse than no comment.

### E13. Blocking calls on the event loop

**Problem.** ffmpeg, tesseract, urllib, and the Groq SDK are all synchronous.

**Solution.** `await asyncio.to_thread(...)` for every one of them, so a slow frame
sample cannot stall every other request in the process. The provider chain, OCR, and
summariser are all async for this reason.

### E14. `llm_configured` was `true` in a test environment for the wrong reason

**Symptom.** The health smoke test reported `llm_configured: true` even though the repo
`.env` has no `GROQ_API_KEY` (verified by listing its keys: `APP_ENV`,
`BACKEND_BASE_URL`, `GOOGLE_SERVER_CLIENT_ID`, `ALLOW_DEV_AUTH`).

**Cause.** A `GROQ_API_KEY` was present in the ambient shell environment.

**Why it is worth noting.** The test suite explicitly sets `GROQ_API_KEY = ""` in
`conftest.py` so it stays offline, but `pydantic-settings` reads real environment
variables with lower precedence than explicit init kwargs â€” the explicit value does win.
The ambient variable only bit during the *manual* smoke test. It also meant a live test
was making real billed API calls without that being obvious from the command.

### E15. Looked at the wrong log file

**Problem.** A live `uvicorn` run appeared to show no application logs at all.

**Cause.** Application logs go to **stderr**; `uvicorn`'s access log goes to **stdout**.
I had redirected them to two files and read the stdout one.

**Solution.** Read the `.err` file, which immediately showed the Groq 404 (E4/A4) and
the swallowed coroutine warning (E4). The lesson: when instrumenting a real process,
check both streams before concluding there is no output.

---

## F. Test-infrastructure problems

### F1. Patched the wrong symbol

**Symptom.** A test that monkeypatched `registry.run_provider_chain` had no effect; the
real chain ran.

**Cause.** `runner.py` does `from app.providers.registry import run_provider_chain`, so
the name lives in the **runner** module's namespace. Patching the source module does not
change the already-bound name.

**Solution.** `monkeypatch.setattr("app.pipeline.runner.run_provider_chain", ...)`.

**Worth knowing.** A test that patches the wrong symbol and then asserts a *failure* will
usually still pass, because the unpatched code genuinely fails. Only a test that patches
the wrong symbol and asserts *success* reliably catches it. That is why
`test_workspace_is_deleted_after_a_successful_job` failed loudly while the "job fails"
test passed quietly â€” and why the quiet one was fixed anyway.

### F2. FastAPI cannot be driven by `TestClient` in an async suite

**Solution.** `httpx.AsyncClient` with `ASGITransport`, and the app's `Runtime` injected
directly:

```python
app = create_app()
app.state.runtime = runtime
transport = ASGITransport(app=app)
```

This tests routing, dependency injection, auth, validation, and serialisation for real,
without a network socket or a server process.

### F3. Calling a FastAPI dependency directly

**Symptom.** `AttributeError: 'Depends' object has no attribute 'is_configured'`.

**Cause.** `current_user_id` has `verifier: GoogleVerifier = Depends(get_verifier)`. That
default is a `Depends` marker, not a value. Calling the function directly outside a
request gives you the marker object.

**Solution.** A `_resolve()` test helper that supplies the dependencies explicitly.

### F4. A test fixture that leaked into the wrong scope

**Symptom.** `test_verify_reports_a_bad_token_as_401` failed expecting
`Invalid Google ID token` but got `Server is missing GOOGLE_CLIENT_ID`.

**Cause.** The test used the shared `settings` fixture, which deliberately has
`google_client_id=""`, so `is_configured()` was `False` and it took an earlier branch.

**Solution.** A local `_settings()` helper with a real-looking client ID.

---

## G. Flaws in my own tests

Included because a test that is wrong in a way that still passes is more dangerous than
no test.

### G1. One stub class shared across two provider names

**Problem.** `_Stub` held `available` and `result` as class attributes. Registering the
same class under `"primary"` and `"secondary"` meant configuring one clobbered the
other, so the fall-through tests were not testing fall-through at all.

**Solution.** The `register_stub` fixture now builds a *fresh subclass per name* via
`type()`.

### G2. Class bodies cannot see enclosing function locals

**Symptom.**

```
NameError: name 'name' is not defined
```

**Cause.**

```python
class _Configured(_Stub):
    name = name          # <- NameError
```

A class body does not participate in the enclosing function's closure. In a class body,
`name = name` looks up `name` in the class namespace, then module globals â€” skipping the
function local entirely.

**Solution.** Build the class with a `type()` call, which evaluates its arguments in the
*enclosing* scope:

```python
_Configured = type(
    f"Stub_{name}",
    (_Stub,),
    {"name": name, "description": f"stub {name}", "is_available": _available, "fetch": _fetch},
)
```

### G3. A closure counter that assertions could not read

**Problem.** `calls` was a `nonlocal` counter inside the factory, so the test helper
`_call_count()` could not observe it and always returned `0`.

**Solution.** A module-level `_CALLS: dict[str, int]`, reset by the fixture.

### G4. A test that asserted on a state the object legitimately already had

**Problem.** `test_client_cannot_mark_a_card_pending` asserted
`status != "PENDING"` on a card that was already `PENDING` because the job succeeded.
Ignoring an invalid transition is indistinguishable from applying it when the value is
unchanged.

**Solution.** Move the card to `REVIEWED` first (an allowed transition), *then* attempt
`PENDING` and assert it stayed `REVIEWED`.

### G5. An injection test that injected nothing

**Problem.** `test_client_cannot_set_an_arbitrary_status` posted a PATCH with
`summary: "pwned"` and asserted `"pwned" not in response["summary"]`. The server
correctly ignores unknown fields, so the assertion held â€” but the card had no real
content to protect yet.

**Solution.** Split into two focused tests: one that first creates a card with real
generated content, then asserts the title/summary/reel_url/provider are all unchanged
after a PATCH; and one for the `PENDING` transition.

### G6. A janitor test that never aged anything

**Problem.** `test_janitor_removes_a_stale_workspace` created two directories, both with
current mtimes, and asserted the "stale" one was removed. It failed â€” correctly, because
the janitor was right and the test was wrong.

**Solution.** `os.utime(stale, (old, old))` to actually backdate it, plus a separate
assertion that a fresh directory (a running job) is left alone.

### G7. Asserted on the wrong symbol again

**Problem.** `monkeypatch.setattr("app.providers.oembed._fetch_json", ...)` raised
`AttributeError: module has no attribute '_fetch_json'`.

**Cause.** `_fetch_json` is a method on `OEmbedProvider`, not a module-level function.
The module-level function is `_fetch`.

### G8. Patched a method with a function of the wrong arity

Related: the test supplied `_boom(_url)` where the module function signature is
`_fetch(url, timeout, headers=None)`. Fixed to `_boom(_url, _timeout, _headers=None)`.

---

## H. Technical decisions and why

### H1. `TranscriptProvider` is an interface, not a base class

Chosen because the *entire point* of the backend is that the input source will change.
The alternative â€” writing a `TranscriptProvider` class and hoping one source is good
enough â€” would have meant rewriting the pipeline the moment it wasn't.

The contract a provider author can rely on, each pinned by a test:

- `is_available()` is cheap and must never raise.
- `fetch()` raises `ProviderUnavailable` (skip me) or `ProviderError` (I cannot read
  *this* reel), and the chain handles both.
- Any *other* exception is caught and the chain continues.
- A result with partial content is accepted. Only an entirely empty result moves on.
- `media_path` lives only inside the job workspace and is deleted afterwards.

### H2. Users can paste a transcript

This is what makes the MVP testable end-to-end with no API keys and no Instagram access,
and it is the honest answer to D1: if no provider can read the reel, the user can
provide the text. The error message tells them so.

### H3. `oembed` returns metadata only, and says so

It can read a title and a caption. It cannot read speech or on-screen text. So it
returns `is_partial=True` and a warning, and the prompt tells the model it is looking at
a caption rather than the content. The card it produces is thin but honest.

### H4. No `whisper-1` â€” that model does not exist on Groq

I originally wrote `openai_whisper_model = "whisper-1"`, which is OpenAI's name. Groq's
equivalents are `whisper-large-v3` and `whisper-large-v3-turbo`. Speech-to-text now runs
on the same Groq key, so one credential covers both LLM and STT. The config comment says
to check `GET /v1/models` rather than trusting the name.

### H5. A deterministic extractive fallback when Groq is unconfigured or failing

If there is no API key â€” or the model errors on all three attempts â€” the pipeline still
produces a card by taking the leading sentences. It is not as good, but it means the
app is demonstrable before anyone signs up for an API key, and a transient LLM outage
does not surface to the user as an error screen.

### H6. In-process worker pool, not Celery

Two threads. No Redis, no broker, no second process to supervise. `PipelineRunner.submit`
is the only method that would change to move to a real queue, because the pipeline is a
pure function of a `JobRequest`. Premature Celery is a lot of infrastructure to own for
a throughput problem that does not exist yet.

### H7. Deletion is enforced twice, because either mechanism alone can fail

1. `JobWorkspace` is an async context manager; its `finally` removes the directory even
   when the job raised.
2. A `Janitor` sweeps the workspace root on a timer, deleting anything older than the
   grace period.

`finally` is sufficient for every normal failure. It is **not** sufficient for
`SIGKILL`, a power cut, or a full disk â€” and those are exactly the cases where a temp
directory silently accumulates forever, holding the last several reels' media on disk.
The janitor is the only thing that survives them.

**Grace period exists for a real reason.** A long-running job keeps a recent mtime, so
the janitor will not delete a job that is still being written to.

**Verified on real data, not by inspection.** After a `Stop-Process -Force` the orphaned
directory was still there, and the janitor removed it once backdated. That is the whole
design working as intended.

### H8. `delete_tree` never raises, and handles read-only files

A cleanup failure must not mask the original job error, so it logs and returns a bool.
Extracted frames can inherit odd permissions, so files are chmod'd before removal â€”
otherwise the sweep would silently leave them behind while logging success, which is
strictly worse than logging a failure.

### H9. Job diagnostics store counters, never content

`Job.notes` records `provider:oembed`, `ocr_lines:31`, `temp_bytes:204800`.

Not the transcript, not the caption, not a snippet of OCR text. These strings end up in
logs, and the entire point of the temp-file policy is that reel content does not
persist. A counter is enough to debug; a quote is a copy.

### H10. `Card` has no column for a transcript

There is no `transcript`, `caption`, `ocr_text`, or `media_path` column on the `cards`
table. Not "they are NULL", not "they are encrypted" â€” the columns do not exist, so even
a careless `SELECT *` cannot leak content that should have been deleted. The schema
enforces the policy rather than relying on discipline.

### H11. Google `sub`, not email, as the user id

An email can change and can be recycled by someone else. `sub` is stable and is Google's
subject identifier. It is the ownership boundary for every card, so it is the wrong
place to cut a corner.

### H12. Google sign-in checks, in order

1. Signature and expiry against Google's keys.
2. `aud` must equal the configured web client ID.
3. `iss` must be `accounts.google.com` (parametrised â€” an exact match, not a `contains`).
4. `email_verified` must be true. Skipping this lets someone with a fresh, unverified
   account impersonate a user in the sign-in flow.
5. Nonce must match â€” and **absent also fails** (E8).

There is also a `tokeninfo` fallback that asks Google directly. It is safe because
Google does the verification, but it is a network round trip per request, so it is not
the default.

### H13. A client may set a review status but may never write card content

`UpdateCardRequest` only has `status` and `snooze_until_epoch_day`. `PATCH` with
`summary` or `title` in the body is ignored â€” pydantic does not error on unknown fields
by default, and silently ignoring is friendlier to an older app build than a 422.

`apply_update` accepts only `REVIEWED`, `SNOOZED`, `DELETED`. Any other requested status
is logged and ignored, so a client cannot talk the server into marking a card `PENDING`
or wiping its summary.

### H14. Another user's card is indistinguishable from a nonexistent one

`owned_card()` returns `None` for both, and routes raise 404 for both. Returning 403
would confirm that a guessed card ID is real. Card IDs are client-generated and travel
in URLs, so they are guessable enough that this matters.

### H15. The dev-auth escape hatch exists, in one place, loudly

`ALLOW_DEV_AUTH` is needed because a test device may have no Google Play services, which
is common on degoogled and emulator images. It is:
- gated behind one branch, not scattered;
- logged as a **warning on every request** that uses it;
- an explicit startup **error** when `ENV=prod`;
- documented as "any client can read and write any account";
- restricted in the README to dev only.

### H16. Card status on the server is `PENDING` when the job finishes

The job completes, the card exists and is readable â€” it just has not been *reviewed*.
`PENDING` is the state that drives the review queue and the 6 PM reminder, so that is
what a successful job produces.

### H17. The Groq prompt is built from a `TranscriptResult` method, not in the summariser

`to_prompt_block()` lives on the result object, so every provider contributes to the
prompt in exactly the same shape. A new provider gets transcript, captions, metadata,
and OCR into the prompt without touching the summariser.

### H18. Retry, then degrade

The summariser retries twice with exponential backoff (capped at 10s), then falls back to
the extractive card. A model outage produces a slightly worse card, not an error screen.
Tests assert the retry count, so the behaviour cannot silently disappear.

### H19. The 6 PM time lives in exactly one file

`domain/ReminderTime.kt`. The scheduler reads it, the notification title reads it, the
settings screen reads it. Changing the reminder time is a one-line change that cannot
leave the app internally inconsistent about what time it notifies at.

---

## I. Verification performed

| Check | Result |
| --- | --- |
| `assembleDebug` from `clean` | SUCCESS, 60.89 MB APK |
| `lintDebug` | 0 errors, 0 fatals; 78 warnings, all version-bumps or unused template resources |
| Merged manifest audit | Application class present, `dataSync` FGS type present, `WorkManagerInitializer` removed, `SCHEDULE_EXACT_ALARM` present, `USE_EXACT_ALARM` **absent** |
| Backend `pytest` | 102 passed, offline and deterministic |
| Live `uvicorn` boot | `/v1/health`, `POST /v1/reels`, `GET /v1/jobs/{id}` all correct |
| Real Groq API end-to-end | Produced a real card from a real transcript |
| Temp cleanup on real data | Workspace empty after a good job and after a crashing job; janitor reclaimed a `SIGKILL` orphan |
| Groq model availability | Live `/v1/models` queried; JSON mode verified on 3 models |

**The single most important verification was the merged manifest, not the build.** A
successful `assembleDebug` coexisted with an app that could not launch (C1) and one that
could not upload on Android 14+ (C4).

---

## J. Known remaining limitations

Honest list, not a wishlist.

1. **12 unused resources** remain in `colors.xml`/`strings.xml` â€” boilerplate from the
   original template. Dead but harmless; deleting them risks nothing and gains little.
2. **No rate limiting on `POST /v1/reels`** â€” *resolved since this log was written.*
   See K4. (Historically: it enqueues LLM calls, so it is a direct path to someone's bill.)
3. **`oembed` uses unauthenticated public reads.** Deliberate and visible, but it can
   break when Instagram changes its page shape. That is what the provider chain and the
   paste-a-transcript path exist for.
4. **SQLite by default.** Fine for one process; `DATABASE_URL` is the only change needed
   for Postgres, but nothing has been load-tested against it.
5. **The in-process runner does not survive a multi-process deployment.** Two `uvicorn`
   workers would each run their own threads and their own janitor. Sharding by worker or
   moving to a real queue is required before scaling out.
6. **`whisper` cannot read a plain Reel URL** because no free endpoint exposes a direct
   media URL. It is wired, tested for availability, and ready for a real resolver â€” but
   it is not a working provider for arbitrary Reels today.
7. **No Android instrumentation tests.** The app was verified by build, lint, manifest
   audit, and a live backend â€” not by on-device UI automation.


---

## K. Authentication removed, and a bug-fix pass (later change)

The sections above describe Google ID-token verification (H11-H15). That entire layer
was later removed on purpose. This section records the removal and the problems that
were fixed alongside it, so the log stays honest about what the code does now.

### K1. Auth removed in favour of a fixed local user

**Decision.** This is a single-tenant deployment. Rather than ship an unused sign-in
surface, authentication was removed end to end:

- Backend: `app/auth.py`, the bearer dependency and the `ALLOW_DEV_AUTH` escape
  hatch are gone. `app/identity.py` supplies one configured `local_user_id`
  (default `local-user`), keeping the `Depends(...)` shape so routes and the
  `user_id` ownership columns are unchanged.
- Android: the sign-in screen, Google Credential Manager, the auth interceptor, the
  JWT helper and the session DataStore are gone, along with the `credentials` and
  `googleid` dependencies.

**Why.** The verifier was the riskiest and least exercised code in the project, and
the ownership columns it justified are still enforced by the service layer - so the
boundary survives if authentication is ever reintroduced. See `test_identity.py`.

**Consequence, stated plainly.** With no credential in front of it, the backend must
not be exposed to the internet as is.

### K2. A live secret was sitting in a local .env

**Symptom.** `.env` contained a real `INSTAGRAM_ACCESS_TOKEN`.

**Fix.** The value was scrubbed from `.env` and the key now belongs in
`backend/.env` only. `.env` is git-ignored and the repo had no commits, so nothing
reached history - but the token itself must be rotated.

### K3. A blank `DATABASE_URL`/`WORKSPACE_DIR` was destructive

**Symptom.** `backend/.env.example` shipped `DATABASE_URL=` and `WORKSPACE_DIR=`
with no explanation. Copying it verbatim made `create_async_engine("")` fail, and
`Path("")` resolves to the current directory - so the janitor's `sweep()` would
delete every directory older than an hour beneath the backend CWD.

**Fix.** Validators turn a blank value into the bundled default. Pinned by
`test_config.py`, and the example file now explains both.

### K4. Rate limiting on submit

**Fix.** `POST /v1/reels` is capped per client with a small in-process sliding
window (`app/ratelimit.py`, `SUBMIT_RATE_LIMIT_PER_MINUTE`). It is process-local;
it becomes a per-worker limit, not a global one, if the API is ever scaled out.

### K5. CORS defaulted to `*`

**Fix.** The default is now empty (same-origin only); the Android client does not use
CORS at all, so a wildcard only widened the browser-reachable surface. `*` was also
removed from `.env.example`. `allow_headers` is back to `Content-Type` only.

### K6. A client-supplied id could 500 the submit endpoint

**Symptom.** `client_card_id` is the primary key. Two cases produced an
`IntegrityError` -> HTTP 500: a card row that existed for this user with no job row,
and an id already owned by a different user.

**Fix.** A card with no job is reused (a new job is attached) instead of re-inserted;
an id owned by another user returns `409`. Covered by two new `test_api.py` tests.

### K7. Whisper deleted the file OCR needed

**Symptom.** `whisper.py` deleted the downloaded audio in a `finally` block, then
returned that same path as `media_path`. The OCR step checks `media_path.exists()`,
so OCR was silently skipped for every whisper job.

**Fix.** The provider no longer deletes the file; the per-job workspace owns cleanup,
which happens after OCR has consumed the media.

### K8. Android: a duplicate-reminder race, and four smaller fixes

- The exact alarm and the periodic backstop could both read "not sent today" and
  post. `ReminderDispatcher` now serialises the check-and-post behind a `Mutex`.
- `ReminderWorker` retried on any exception forever; it is now capped.
- `fallbackToDestructiveMigration()` could wipe unsynced review actions; it is now
  debug-only, so a missing release migration fails loudly instead.
- "Open in Instagram" called `startActivity` with an unvalidated URL, so a malformed
  value or a missing handler was an `ActivityNotFoundException` crash. It now
  validates the scheme and catches the failure.
- The Settings "scheduled/next reminder" values were plain getters, so they never
  updated after scheduling; they are now observable state.

### K9. Test-suite flake exposed a leak

**Symptom.** The auth-removal rewrite replaced a 401 test with one that actually
submits a job. It did not drain the in-process runner, so a background persist task
outlived the test and failed `test_full_lifecycle` non-deterministically.

**Fix.** Every test that submits now drains the runner. A latent settings divergence
was also fixed: the app resolved `get_settings` from a process-wide cache while the
tests injected their own `Settings`, so the API used a different `local_user_id`
than the fixtures; the test client now overrides that dependency.
