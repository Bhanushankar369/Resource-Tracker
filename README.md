# ResourceTracker 📱⚡

[![Platform: Android](https://img.shields.io/badge/Platform-Android%20(API%2026%2B)-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin%202.0-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Backend: FastAPI](https://img.shields.io/badge/Backend-FastAPI%20%2F%20Python%203.10%2B-009688.svg?logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com)
[![UI: Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2F%20Material%203-4285F4.svg?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![AI: Groq](https://img.shields.io/badge/AI-Groq%20LLM%20%26%20Whisper-F55036.svg)](https://groq.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> Turn Instagram Reels, YouTube Shorts, and X (Twitter) videos into actionable study flashcards in seconds via the native Android Share Sheet.

---

## 📑 Table of Contents
1. [Overview & Architecture](#-overview--architecture)
2. [Prerequisites](#-prerequisites)
3. [Quick Start: Download & Run as an Application](#-quick-start-download--run-as-an-application)
   - [Step 1: Clone the Repository](#step-1-clone-the-repository)
   - [Step 2: Start the Python Backend](#step-2-start-the-python-backend)
   - [Step 3: Configure the Android App](#step-3-configure-the-android-app)
   - [Step 4: Build and Install the Android App (APK)](#step-4-build-and-install-the-android-app-apk)
4. [Connecting Android to the Backend](#-connecting-android-to-the-backend)
   - [Scenario A: Android Emulator](#scenario-a-android-emulator-recommended-for-testing)
   - [Scenario B: Physical Android Device via USB (ADB Reverse)](#scenario-b-physical-android-device-via-usb-adb-reverse)
   - [Scenario C: Physical Android Device via Local Wi-Fi (LAN)](#scenario-c-physical-android-device-via-local-wi-fi-lan)
5. [Distributing Pre-built APKs (GitHub Releases)](#-distributing-pre-built-apks-github-releases)
6. [How to Use the Application](#-how-to-use-the-application)
7. [The Thing to Understand First (Architecture & Instagram Reality)](#-the-thing-to-understand-first)
8. [Project Layout](#-project-layout)
9. [Key Design Decisions](#-key-design-decisions)
10. [Testing](#-testing)
11. [Pre-Production Checklist](#-pre-production-checklist)

---

## 🌟 Overview & Architecture

When you stumble on an educational or useful Reel on Instagram, you can share it directly to **ResourceTracker** from Instagram's native Share Sheet. 

The Android app forwards **only the URL** to your private backend service. The server extracts the transcript/captions, runs speech-to-text or OCR if media is accessible, generates structured study flashcards via Groq LLM, and immediately wipes any temporary media or OCR files from the server.

```
Android App                FastAPI Server                  Transcript Provider Chain
┌──────────────────┐       ┌──────────────────────┐       ┌────────────────────────┐
│ Kotlin, Compose, │       │ In-process workers,  │       │ 1. Manual User Paste   │
│ Room SQLite,     │──URL─▶│ Temp Workspace,      │──────▶│ 2. Meta oEmbed API     │
│ Hilt, WorkManager│       │ Groq LLM Summarizer  │       │ 3. Whisper / Audio STT │
└──────────────────┘       └──────────────────────┘       └────────────────────────┘
         ▲                            │
         │                            ▼
         └───── Receives JSON ────────┴── Deletes Temp Files (Guaranteed Cleanup)
```

---

## 📋 Prerequisites

Before installing, ensure you have the following installed on your host computer:

| Dependency | Minimum Version | Note |
|---|---|---|
| **Git** | Any modern version | To clone the repository |
| **Python** | 3.10 or newer | For the FastAPI backend service |
| **Java Development Kit (JDK)** | JDK 17+ | Required by Android Gradle Plugin (AGP) |
| **Android Studio** OR **Android SDK Command-line Tools** | Hedgehog / API 34 SDK | For building and deploying the Android APK |
| **FFmpeg & Tesseract OCR** *(Optional)* | Any modern release | Required only if testing local video frame sampling & OCR |

---

## 🚀 Quick Start: Download & Run as an Application

Follow these 4 steps to get the full application running on your computer and Android device.

### Step 1: Clone the Repository

Open your terminal or PowerShell and clone the codebase:

```bash
git clone https://github.com/<your-username>/ResourceTracker.git
cd ResourceTracker
```

---

### Step 2: Start the Python Backend

The backend processes submitted URLs and synthesizes study cards.

1. **Navigate to the backend directory and set up a virtual environment:**

   **Windows (PowerShell):**
   ```powershell
   cd backend
   python -m venv .venv
   .\.venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```

   **macOS / Linux:**
   ```bash
   cd backend
   python3 -m venv .venv
   source .venv/bin/activate
   pip install -r requirements.txt
   ```

2. **Configure environment variables:**
   ```bash
   # In the backend directory:
   cp .env.example .env
   ```
   Open `backend/.env` in your text editor:
   - **Groq API Key (Recommended):** Add your key from [console.groq.com/keys](https://console.groq.com/keys) to `GROQ_API_KEY`.
   - **Fallback/Offline mode:** If you do not have a Groq key yet, set `TRANSCRIPT_PROVIDER=manual` — the backend will use an extractive deterministic summarizer with zero external dependencies.

3. **Start the backend server:**

   **Windows:**
   ```powershell
   .\.venv\Scripts\python -m uvicorn app.main:app --reload --port 8000
   ```

   **macOS / Linux:**
   ```bash
   python -m uvicorn app.main:app --reload --port 8000
   ```

   Verify it's running by opening [http://127.0.0.1:8000/v1/health](http://127.0.0.1:8000/v1/health) in your browser.

---

### Step 3: Configure the Android App

Return to the project root directory and create the Android build configuration:

```bash
# From the repository root (not inside backend/):
cp .env.example .env
```

Edit the root `.env` to define where your Android device looks for the backend:

```ini
APP_ENV=dev
BACKEND_BASE_URL=http://10.0.2.2:8000/
```
*(See the [Connecting Android to the Backend](#-connecting-android-to-the-backend) section below to select the right URL for your setup).*

> ⚠️ **Important:** Ensure the URL ends with a trailing slash `/` as required by Retrofit.

---

### Step 4: Build and Install the Android App (APK)

You can build and run the application either using **Android Studio** or directly through the **Command Line**.

#### Option A: Using Android Studio (Easiest)
1. Open Android Studio.
2. Select **File > Open...** and choose the `ResourceTracker` root folder.
3. Wait for Gradle Sync to complete.
4. Select your connected physical phone or Android Virtual Device (AVD Emulator) from the top toolbar device selector.
5. Click the green **Run (▶)** button. The APK will compile, install, and open automatically.

#### Option B: Using the Command Line (Gradle Wrapper)

Ensure an emulator is booted or a physical device is connected with **USB Debugging enabled** (`adb devices` should show your device).

**Windows:**
```powershell
# Build the Debug APK
.\gradlew.bat :app:assembleDebug

# Or build and install directly to the connected device/emulator:
.\gradlew.bat :app:installDebug
```

**macOS / Linux:**
```bash
# Make gradlew executable
chmod +x ./gradlew

# Build the Debug APK
./gradlew :app:assembleDebug

# Or build and install directly:
./gradlew :app:installDebug
```

After building, the compiled APK is located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

If you only ran `assembleDebug`, you can manually install the generated APK at any time with:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔌 Connecting Android to the Backend

The Android application needs network access to reach your local FastAPI server. Choose the scenario that matches your setup:

### Scenario A: Android Emulator (Recommended for Testing)
The Android Emulator runs in an isolated virtual network where `10.0.2.2` represents your host PC's `localhost`:
- In root `.env`:
  ```ini
  BACKEND_BASE_URL=http://10.0.2.2:8000/
  ```

### Scenario B: Physical Android Device via USB (ADB Reverse)
If your phone is plugged in via USB with USB debugging enabled, forward the phone's port 8000 directly to your PC:
1. Run in terminal:
   ```bash
   adb reverse tcp:8000 tcp:8000
   ```
2. In root `.env`:
   ```ini
   BACKEND_BASE_URL=http://127.0.0.1:8000/
   ```
3. Re-run `./gradlew installDebug`.

### Scenario C: Physical Android Device via Local Wi-Fi (LAN)
If your phone and PC are on the same Wi-Fi network:
1. Find your computer's local IP address (`ipconfig` on Windows or `ifconfig` / `ip a` on macOS/Linux, e.g. `192.168.1.50`).
2. In root `.env`:
   ```ini
   BACKEND_BASE_URL=http://192.168.1.50:8000/
   ```
3. Rebuild and install the APK.
*(Cleartext HTTP for local IP ranges `192.168.*.*`, `10.*.*.*`, and `127.0.0.1` is pre-configured in `app/src/main/res/xml/network_security_config.xml`).*

---

## 📦 Distributing Pre-built APKs (GitHub Releases)

If you are hosting this project on GitHub and want users to be able to download and install the app without installing Android Studio or building from source:

1. **Build a standalone APK:**
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. **Find the APK:**
   The output APK will be at:
   `app/build/outputs/apk/debug/app-debug.apk`
3. **Publish on GitHub:**
   - Go to your GitHub repository -> **Releases** -> **Draft a new release**.
   - Create a release tag (e.g. `v1.0.0`).
   - Drag and drop `app-debug.apk` into the release assets.
   - Publish the release.
4. **How an end-user installs the download:**
   - The user downloads `app-debug.apk` on their Android phone.
   - Taps the downloaded file to install (allowing "Install from unknown sources" if prompted).
   - *(Note: Ensure the backend is deployed on a publicly accessible URL or on their home network, and specified in the build).*

---

## 📱 How to Use the Application

1. **Ingest via Share Sheet:**
   - In Instagram, tap the **Share** button on any Reel -> select **ResourceTracker** from the app list.
   - The app extracts the URL, assigns a unique client card ID, and submits it to your backend.
2. **Track Processing:**
   - In the **Review** tab, the reel appears immediately in a `PENDING` state with a progress notification.
   - Once the backend synthesizes the card, it auto-refreshes with the title, summary, key takeaways, and action items.
3. **Manual Paste Fallback:**
   - If a Reel cannot be scraped due to privacy restrictions or missing tokens, tap **Add Transcript / Manual Paste** in the app to feed text directly to the AI summarizer.
4. **Daily 6 PM Study Reminder:**
   - The app schedules a local exact alarm (`SCHEDULE_EXACT_ALARM`) to alert you daily at 6:00 PM to review pending flashcards. Reminders persist automatically across phone reboots.

---

## 💡 The Thing to Understand First

**There is no public API that converts an Instagram Reel URL directly into an MP4 video file.**
Instagram's Graph API only exposes media for Business/Creator accounts linked to a Page the caller owns. Scraping HTML for video URLs violates Instagram Terms of Use and leads to Play Store rejections.

ResourceTracker solves this by design:
- `TranscriptProvider` is a pluggable interface. Adding a new provider is a single class and one line in `registry.py`.
- The engine cascades through providers in order (Manual Paste -> oEmbed metadata -> Whisper STT -> OCR).
- When transcript extraction is partial, the LLM is instructed never to hallucinate beyond provided facts.
- Reel content and video samples are treated as ephemeral workspace data and deleted immediately upon card creation.

---

## 📂 Project Layout

```
.
├── app/                                  # Android Client (Kotlin + Jetpack Compose)
│   ├── src/main/java/com/resourcetracker/
│   │   ├── core/                         # Dispatchers, network result wrappers
│   │   ├── data/                         # Room DB, Retrofit API client, repositories
│   │   ├── di/                           # Hilt dependency injection modules
│   │   ├── domain/                       # Core card models, validation
│   │   ├── reminder/                     # AlarmManager 6 PM exact alarms, boot receiver
│   │   ├── ui/                           # Jetpack Compose UI screens (Review, Snoozed, Done)
│   │   │   ├── cards/                    # Card detail & list composables
│   │   │   ├── components/               # Status badges, share sheet intake
│   │   │   └── theme/                    # Material 3 colors, typography, shapes
│   │   └── work/                         # WorkManager background sync workers
│   └── build.gradle.kts                  # Android build config & dependency setup
│
├── backend/                              # FastAPI Backend (Python 3.10+)
│   ├── app/
│   │   ├── providers/                    # TranscriptProvider abstraction & implementations
│   │   │   ├── base.py                   # TranscriptProvider interface & TranscriptResult
│   │   │   ├── registry.py               # Provider resolution & fallback chain
│   │   │   ├── manual_paste.py           # User-supplied transcript provider
│   │   │   ├── oembed.py                 # Meta Graph oEmbed metadata extractor
│   │   │   └── whisper.py                # Audio-to-text transcription via Groq
│   │   ├── pipeline/                     # Synthesis pipeline
│   │   │   ├── runner.py                 # In-process asynchronous job queue
│   │   │   ├── workspace.py              # Ephemeral workspace manager & janitor
│   │   │   ├── ocr.py                    # FFmpeg frame extraction + Tesseract OCR
│   │   │   └── summarizer.py             # Groq LLM summarization with fallback
│   │   ├── services/                     # Database persistence & card operations
│   │   ├── db.py / models.py             # SQLAlchemy 2.0 async engine & tables
│   │   ├── api.py                        # FastAPI routes (/v1/reels, /v1/cards, /v1/health)
│   │   └── config.py                     # Pydantic Settings & environment validation
│   ├── tests/                            # 102 offline unit and integration tests
│   └── requirements.txt                  # Python dependencies
│
├── .env.example                          # Android build environment template
└── README.md                             # Project documentation
```

---

## 📐 Key Design Decisions

- **Groq AI (Fast & Cost-Effective):** Single API key for both high-speed LLM inference (`json_object` structured output) and speech-to-text (`whisper-large-v3`).
- **Guaranteed Cleanup (Double-Enforced):** Reel frames and audio are processed inside a python context manager (`JobWorkspace`). Its `finally` block deletes the temporary directory on job completion. A periodic background `Janitor` sweeps stale directories older than 1 hour as a safety backstop.
- **Client-Generated Card IDs (Offline Resilience):** The Android client generates the UUID for each card locally upon sharing. Submissions never stall waiting for a network handshake, and re-transmissions are idempotent.
- **Single-Tenant Backend:** Simple and secure for personal deployment. No third-party OAuth complexity; requests operate with a local user ID.

---

## 🧪 Testing

The backend includes a comprehensive, 100% deterministic test suite (no active Groq credits or external network calls required):

```bash
cd backend
# Run all 102 tests
.\.venv\Scripts\python -m pytest

# Test the workspace auto-cleanup guarantees specifically
.\.venv\Scripts\python -m pytest tests/test_workspace_cleanup.py -v
```

---

## 🛡️ Pre-Production Checklist

- [ ] Configure `BACKEND_BASE_URL` with your public or LAN address.
- [ ] Ensure `GROQ_API_KEY` is added to `backend/.env`.
- [ ] For Instagram metadata resolution, add `INSTAGRAM_ACCESS_TOKEN` in `backend/.env`.
- [ ] Keep `backend/.env` secure and never commit it to source control.
- [ ] For production multi-user deployment, place an authenticating reverse proxy (e.g. Caddy/Nginx with OAuth/WireGuard) in front of the FastAPI backend.
