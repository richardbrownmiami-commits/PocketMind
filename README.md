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

Open **Tester** from the chat screen and tap **Run tests**. It checks Android/ARM compatibility, the installed offline permission, native library loading, model-file readiness, rule/planner/validator logic, safe clock/math/note/battery tools, and memory-store persistence/clear behavior. It reports Android's current memory-pressure flag and available/total RAM but does not stop generation or unload the model based on those readings. Database checks use a uniquely named temporary database and delete it afterward. If a model is already loaded, the tester runs a short generation smoke test without saving a chat turn. **Share report** opens Android's normal share chooser; nothing is sent automatically. The report excludes chat and note contents.

Actions that could change the phone (alarms, timers, flashlight, volume, brightness, app launches, and settings changes) are never triggered by the tester. It preflights available system handlers/capabilities and marks real state-changing tests as not run; those still need manual device verification.

PocketMind does not automatically stop generation, unload a model on Android low-memory callbacks, reject model loading/generation based on free RAM, or idle-unload the model. Explicit Stop and Unload controls remain available. Android may still reclaim or terminate the process under memory pressure; the tester only reports the OS's memory status and cannot prevent that.

## Import a model

Open **Models**, choose a compatible GGUF file using Android's system document picker, and let PocketMind copy it into its private app files. Models above 1.2 GB are rejected. A compact Q4/Q5 instruct model (for example, a 0.5B–1B model) is the practical starting point for a low-memory 32-bit handset. Model files are not included in this repository.

## Privacy and current limits

Chats, facts, recall, action records, and imported models are stored locally. The app has no `INTERNET` permission and never executes model-produced shell commands. Tool arguments are validated, risk-bearing actions require user confirmation, and the UI only reports success after verification. Some system settings actions can only open a Settings screen and are reported as handed off. Device-specific alarm, torch, performance, and memory behavior must be verified on a real Android 11 ARMv7 phone; no such handset is attached to the build environment.

## Development

llama.cpp is fetched at the immutable commit recorded in `scripts/fetch-llama.sh`; `vendor/` is intentionally ignored. Gradle and Android dependencies are pinned in the project files. See [plan.md](plan.md) for the full specification and milestone acceptance criteria.
