# PocketMind

PocketMind is an offline-first Android assistant for Android 11 (API 30) phones with 32-bit ARMv7-A and NEON. Inference and conversation memory run on-device; the first release requests no network access. The app uses Kotlin, plain Android Views, a bundled SQLite/FTS5 database, and a CPU-only llama.cpp JNI engine.

## Build

Requirements: JDK 17, Android SDK platform 35/build-tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1. `compileSdk` is 35 because the pinned AndroidX Core library requires it; `minSdk` and `targetSdk` remain 30 for Android 11. Only `armeabi-v7a` is built.

```sh
./scripts/fetch-llama.sh
gradle testDebugUnitTest assembleDebug
```

The checked-in GitHub Actions workflow installs the pinned Android toolchain, fetches the pinned llama.cpp source, runs JVM tests, builds the APK, and checks that the APK contains no non-ARMv7 native libraries.

## In-app tester

Open the prominent **Tester · Run all feature tests** button on the chat screen. The dedicated QA screen runs checks one by one and shows a live status, explanation, and likely cause for every result. It checks every registered tool's argument schema; runs each safe tool; tests the confirmation gate for every state-changing tool; checks rules, planner parsing, memory/follow-up behavior, temporary database persistence/clear semantics, Android/ARM/offline policy, native library, model readiness, and screen registration. The tester also validates its own Run/Share control state and sends synthetic swipe gestures through the real scrollable result list. Each test is written to Android logs under `PocketMindTester`; when a device is attached to a computer, use `adb logcat -s PocketMindTester` to see the ordered terminal log.

All database and note checks use uniquely named temporary databases that are deleted afterward; real chats, notes, facts, action history, and app preferences are not read or modified. If a model is already loaded, the tester runs a short local generation smoke test without adding a chat turn. **Share report** opens Android's normal share chooser only after the user taps it; nothing is sent automatically. The report contains no chat, note, or fact contents.

Alarms, timers, flashlight, volume, brightness, app launches, and Settings changes are never triggered. For each risk-bearing tool, the tester confirms the app stops at its approval gate, then checks system handler/hardware availability without opening an external app or screen. The report marks actual device-action tests `NOT_RUN` and explains why; those require an explicit, separate user-approved manual test. Android memory pressure is reported read-only: PocketMind has no free-RAM gate, automatic generation stop, low-memory unload, or idle unload. Android itself may still reclaim or terminate the process. Importing models, changing model selection, clearing memory, toggling memory preferences, and opening/share-target apps are not automated against real user data or external apps.

PocketMind does not automatically stop generation, unload a model on Android low-memory callbacks, reject model loading/generation based on free RAM, or idle-unload the model. Explicit Stop and Unload controls remain available. Android may still reclaim or terminate the process under memory pressure; the tester only reports the OS's memory status and cannot prevent that.

## Import a model

Open **Models**, choose a compatible GGUF file using Android's system document picker, and let PocketMind copy it into its private app files. Models above 1.2 GB are rejected. A compact Q4/Q5 instruct model (for example, a 0.5B–1B model) is the practical starting point for a low-memory 32-bit handset. Model files are not included in this repository.

## Privacy and current limits

Chats, facts, recall, action records, and imported models are stored locally. The app has no `INTERNET` permission and never executes model-produced shell commands. Tool arguments are validated, risk-bearing actions require user confirmation, and the UI only reports success after verification. Some system settings actions can only open a Settings screen and are reported as handed off. Device-specific alarm, torch, performance, and memory behavior must be verified on a real Android 11 ARMv7 phone; no such handset is attached to the build environment.

## Development

llama.cpp is fetched at the immutable commit recorded in `scripts/fetch-llama.sh`; `vendor/` is intentionally ignored. Gradle and Android dependencies are pinned in the project files. See [plan.md](plan.md) for the full specification and milestone acceptance criteria.
