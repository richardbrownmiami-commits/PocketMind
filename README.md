# PocketMind

PocketMind is an offline-first Android assistant for Android 11 (API 30) phones with 32-bit ARMv7-A and NEON. Inference and conversation memory run on-device; the first release requests no network access. The app uses Kotlin, plain Android Views, a bundled SQLite/FTS5 database, and a CPU-only llama.cpp JNI engine.

## Build

Requirements: JDK 17, Android SDK platform 35/build-tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1. `compileSdk` is 35 because the pinned AndroidX Core library requires it; `minSdk` and `targetSdk` remain 30 for Android 11. Only `armeabi-v7a` is built.

```sh
./scripts/fetch-llama.sh
gradle testDebugUnitTest assembleDebug
```

The checked-in GitHub Actions workflow installs the pinned Android toolchain, fetches the pinned llama.cpp source, runs JVM tests, builds the APK, and checks the APK's native ABI, app ID, version, API levels, and offline permission contract. Each artifact is tagged with its source commit and includes its APK checksum and signing-certificate fingerprint. The debug artifact is **PocketMind Test** (`com.pocketmind.test`), version `0.1.1` (`versionCode` 2). It installs beside, not over, the regular PocketMind app (`com.pocketmind`) and does not access or change the regular app's local data. The test copy has separate storage, so import a GGUF model into it before testing; its chats, notes, and settings are also separate. The earlier app version remains intact.

Debug builds are signed with the hosted runner's debug key, which can vary between runs. The commit-specific artifact name and embedded build information identify exactly which APK is being tested; Android may not accept a later debug artifact as an in-place update to an earlier **test** copy. This does not affect the separately installed regular PocketMind app.

## In-app tester

Open the prominent **Tester · Run all feature tests** button on the chat screen. This is a diagnostic, not a way to execute every feature: it briefly opens MainActivity's real Chat screen in synthetic-only mode, shows fixed Q&A questions and one synthetic request for every registered feature, then returns only statuses to the report. The normal rule-first/local planner route and arguments are checked; no proposed action is executed. Start does not invoke `ToolRuntime` or run device actions. Use each feature's separate **real-verification** button to exercise its action; the tester displays the exact plan and requires **Confirm** before running it. It also checks each tool schema, routing/rules/planner parsing, memory and follow-up behavior, disposable database persistence/clear semantics, Android/ARM/offline policy, native library, model readiness, and screen registration. The tester validates its own Run/Share state and scrollable result list. Automatic checks are written to Android logs under `PocketMindTester`; use `adb logcat -s PocketMindTester` to see the ordered terminal log.

Automatic database checks use uniquely named temporary databases that are deleted afterward; real chats, notes, facts, and preferences are not read or modified. If a model is loaded, the tester also runs a separate generation smoke test. The visible synthetic Chat transcript is held only in Activity memory and is never written to message history, memory, notes, action logs, Android logs, or the report. If no model is loaded, model-dependent Q&A and planner routes are marked `NOT_RUN` with a reason; deterministic routes can still pass. **Share report** opens Android's normal share chooser only after the user taps it; nothing is sent automatically. The report contains statuses, not chat, note, or fact contents.

Every registered feature has an optional real-verification button. It first asks a synthetic request through transient Chat and the same rule-first/local-model route; only a validated plan proceeds to an exact-action **Confirm** or **Skip** dialog. Low-risk tool checks use a disposable database, never the user's notes. Flashlight, volume, and brightness capture before/after values and offer a separate confirmation before restoring; if a value changed elsewhere, PocketMind does not overwrite it.

Alarm testing asks for a blank, user-entered 24-hour time at least two hours away, routes that exact time through Chat, and creates a real one-time Clock alarm with a unique test label only after confirmation. It checks Android's [next-alarm read-back](https://developer.android.com/reference/android/app/AlarmManager#getNextAlarmClock()), which exposes a trigger timestamp but not the Clock label or alarm ownership. After a matching timestamp, the tester asks separately before requesting [label-scoped dismissal](https://developer.android.com/reference/android/provider/AlarmClock#ACTION_DISMISS_ALARM) and reports timestamp restoration only if the next-alarm value returns to its captured prior state. If read-back or dismissal is inconclusive, the report names the exact label and tells the user to check/delete it in **Clock > Alarms**; no cleanup is claimed. Timer testing asks for an explicit 1–1440 minute duration and starts a real timer only after a separate confirmation. Android's [timer intent](https://developer.android.com/reference/android/provider/AlarmClock#ACTION_SET_TIMER) starts immediately, but API 30 offers PocketMind no reliable running-countdown read-back or unique cancellation handle, so the timer's verification remains user-assisted/`NOT_RUN`; the tester says to check **Clock > Timer** and tap **Stop/Cancel** if it is running. Wi-Fi, Bluetooth, airplane-mode, and location tests open system screens and record Boolean state before/after but do not silently toggle settings. `app.open` verifies only its confirmed launch handoff, not the target app's internal behavior. Confirmed device actions are logged; synthetic Chat prompts/responses are not.

Android memory pressure is reported read-only: PocketMind has no free-RAM gate, automatic generation stop, low-memory unload, or idle unload. Android itself may still reclaim or terminate the process. Importing models, changing model selection, clearing memory, toggling memory preferences, and opening/share-target apps are not automated against real user data or external apps.

PocketMind does not automatically stop generation, unload a model on Android low-memory callbacks, reject model loading/generation based on free RAM, or idle-unload the model. Explicit Stop and Unload controls remain available. Android may still reclaim or terminate the process under memory pressure; the tester only reports the OS's memory status and cannot prevent that. Normal chat confirmations are one-use, expire after five minutes, and bind to the exact plan shown; mutating or state-changing tools are not automatically retried after a failure. If chat recognizes an action request but cannot create a safe valid plan, it now explicitly says that nothing was run or saved instead of passing the request to ordinary free-form chat.

## Import a model

Open **Models**, choose a compatible GGUF file using Android's system document picker, and let PocketMind copy it into its private app files. Models above 1.2 GB are rejected. A compact Q4/Q5 instruct model (for example, a 0.5B–1B model) is the practical starting point for a low-memory 32-bit handset. Model files are not included in this repository.

## Privacy and current limits

Chats, facts, recall, action records, and imported models are stored locally. The app has no `INTERNET` permission and never executes model-produced shell commands. Tool arguments are validated, risk-bearing actions require user confirmation, and the UI only reports success after verification. Some system settings actions can only open a Settings screen and are reported as handed off. Device-specific alarm, torch, performance, and memory behavior must be verified on a real Android 11 ARMv7 phone; no such handset is attached to the build environment.

## Development

llama.cpp is fetched at the immutable commit recorded in `scripts/fetch-llama.sh`; `vendor/` is intentionally ignored. Gradle and Android dependencies are pinned in the project files. See [plan.md](plan.md) for the full specification and milestone acceptance criteria.
