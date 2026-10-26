# PocketMind

PocketMind is a strictly offline Android assistant for Android 11 (API 30) phones with 32-bit ARMv7-A and NEON. Chat, model inference, conversation memory, and multi-step agent planning run on the device. The app uses Kotlin, plain Android Views, a bundled SQLite/FTS5 database, and a CPU-only llama.cpp JNI engine. The APK has no Internet permission or network transport.

## Build

Requirements: JDK 17, Android SDK platform 35/build-tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1. `compileSdk` is 35 because the pinned AndroidX Core library requires it; `minSdk` and `targetSdk` remain 30 for Android 11. Only `armeabi-v7a` is built.

```sh
./scripts/fetch-llama.sh
./gradlew assembleDebug
```

The GitHub Actions workflow fetches the pinned llama.cpp source, builds the debug APK, and checks its native ABI, app ID, version, API levels, and no-network contract. The artifact is the regular PocketMind app (`com.pocketmind`) and includes its source commit, checksum, and signing-certificate fingerprint.

## Use PocketMind

Open **Models** to import a compatible GGUF file with Android's system document picker and select it for local inference. Models above 1.2 GB are rejected. A compact Q4/Q5 instruct model (for example, a 0.5B–1B model) is a practical starting point for a low-memory 32-bit handset.

Use the **Chat** screen to talk or enter commands. Common commands are matched by deterministic rules first; other clear action requests can use the local planner. For multi-step requests, PocketMind executes one local action at a time, checks its result, and replans from the original request and observed outcome, with a four-step limit. A failed or handed-off action stops the sequence, and identical tool calls are not repeated. Validated supported actions report verified results, Android hand-offs, or failures. Explicit requests for live web information receive an offline-mode explanation; no query is sent over the network. Ask explicitly to list recent actions or ask whether anything was done without model tools to query the local action history; ordinary replies do not query it. History distinguishes rule-driven actions from model-planned ones and omits raw tool arguments. The **Memory** screen lets you review and clear saved memory. Normal **Stop** and **Unload** controls remain available.

## Privacy and limits

Chats, facts, recall, action records, and imported models are stored locally. The app does not declare `INTERNET`, contains no network transport, and has no background sync or analytics. Cleartext traffic is disabled. The app never executes model-produced shell commands. Tool arguments are validated before execution. There are no risk-based confirmation dialogs; Android-required permission screens remain in place, and success is reported only after verification. Some Android actions can only open a system screen and are reported as handed off.

PocketMind does not use free-RAM checks to reject model loading or generation, automatically stop generation, unload on low-memory callbacks, or idle-unload a model. Android may still reclaim or terminate the process under system memory pressure. Device-specific alarm, torch, performance, and memory behavior should be evaluated on an Android 11 ARMv7 phone.

## Development

llama.cpp is fetched at the immutable commit recorded in `scripts/fetch-llama.sh`; `vendor/` is intentionally ignored. Gradle and Android dependencies are pinned in the project files. See [plan.md](plan.md) for the full specification and milestones.
