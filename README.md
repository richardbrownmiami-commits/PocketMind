# PocketMind

PocketMind is an offline-first Android assistant for Android 11 (API 30) phones with 32-bit ARMv7-A and NEON. Chat, model inference, and conversation memory run on the device. The app uses Kotlin, plain Android Views, a bundled SQLite/FTS5 database, and a CPU-only llama.cpp JNI engine. Live web search is available only when the local model explicitly calls its search tool for a time-sensitive question.

## Build

Requirements: JDK 17, Android SDK platform 35/build-tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1. `compileSdk` is 35 because the pinned AndroidX Core library requires it; `minSdk` and `targetSdk` remain 30 for Android 11. Only `armeabi-v7a` is built.

```sh
./scripts/fetch-llama.sh
./gradlew assembleDebug
```

The GitHub Actions workflow fetches the pinned llama.cpp source, builds the debug APK, and checks its native ABI, app ID, version, API levels, and approved online-tool network contract. The artifact is the regular PocketMind app (`com.pocketmind`) and includes its source commit, checksum, and signing-certificate fingerprint.

## Use PocketMind

Open **Models** to import a compatible GGUF file with Android's system document picker and select it for local inference. Models above 1.2 GB are rejected. A compact Q4/Q5 instruct model (for example, a 0.5B–1B model) is a practical starting point for a low-memory 32-bit handset.

Use the **Chat** screen to talk or enter commands. Common commands are matched by deterministic rules first; other clear action requests can use the local planner. Validated supported actions run directly, and PocketMind reports verified results, Android hand-offs, or failures. Ask explicitly to list recent actions or ask whether anything was done without model tools to query the local action history; ordinary replies do not query it. History distinguishes rule-driven actions from model-planned ones and omits raw tool arguments. For questions explicitly asking about current, latest, recent, future, upcoming, trending, breaking, or live information, the local model can call the read-only `web.search` tool and then answer from its results. The tool returns result titles, snippets, and source URLs only; it does not open result pages. The **Memory** screen lets you review and clear saved memory. Normal **Stop** and **Unload** controls remain available.

## Opt-in remote testing

PocketMind can optionally share a reduced-size live view of its own app window and receive taps, swipes, or text inside PocketMind during a session you start with a fresh 10-character pairing code. Chat requests use PocketMind's normal router and tools, including alarms, volume, flashlight, and app launches; results appear in PocketMind chat. These actions can affect the device. If an action brings another app to the foreground, sharing pauses, the viewer is blanked, and remote input is blocked until PocketMind returns; no other app screen is captured or controlled. Stop blocks not-yet-run actions from this session. A clear in-app banner and ongoing notification include Stop. Stop, screen off/lock, closing the app task, relay disconnect, or the one-hour limit ends the session. No Accessibility service or system-wide screen capture is used. The relay operator can inspect live frames and send in-app requests, so use only a trusted relay and tester. Normal builds have no configured relay; an optional build-only property can prefill an approved endpoint for one APK without changing source defaults. Nothing connects until you explicitly start a session. See [`remote-relay/README.md`](remote-relay/README.md) for deployment, build prefill, and privacy details. A temporary development relay is usable only while its workspace is active.

## Privacy and limits

Chats, facts, recall, action records, and imported models are stored locally. `web.search` sends a query to DuckDuckGo over HTTPS; `api.discover` searches public API metadata; and `api.get` performs a bounded, read-only GET to a public HTTPS API. Those ordinary tools do not open network access for chat or action history. Separately, an explicitly started remote-test session uses WSS only to the relay address you enter. It streams only PocketMind's app window and accepts only in-app control. The configured relay operator can inspect session frames and controls; this is not end-to-end encrypted. There is no background sync or analytics, and cleartext traffic remains disabled. Web/API results are treated as untrusted data. The app never executes model-produced shell commands; tool arguments are validated, and system-only actions are reported as hand-offs.

PocketMind does not use free-RAM checks to reject model loading or generation, automatically stop generation, unload on low-memory callbacks, or idle-unload a model. Android may still reclaim or terminate the process under system memory pressure. Device-specific alarm, torch, performance, and memory behavior should be evaluated on an Android 11 ARMv7 phone.

## Development

llama.cpp is fetched at the immutable commit recorded in `scripts/fetch-llama.sh`; `vendor/` is intentionally ignored. Gradle and Android dependencies are pinned in the project files. See [plan.md](plan.md) for the full specification and milestones.
