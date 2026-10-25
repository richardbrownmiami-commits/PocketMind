# PocketMind – Build Guide for an AI Coding Agent

A new, from-scratch Android project: a private AI assistant that runs on an old 32-bit phone, remembers conversations, and can choose and run phone functions by itself and then check that they worked.

This document says what to use and how to build it. Follow it in order.

---

## 1. The idea in one paragraph

You talk or type. A **fast rule layer** handles simple commands instantly (time, alarm, timer, notes, flashlight). Anything else goes to a **small local language model** that either answers in conversation (with memory) or picks a **tool** from a list the app gives it. The app **validates** the choice, **runs** it, **verifies** the result, and only then tells you "done". Everything stays on the phone.

## 2. Target hardware and platform (fixed)

| Item | Value |
|---|---|
| CPU ABI | `armeabi-v7a` (ARMv7-A, 32-bit, NEON) |
| Android | 11 |
| API level | 30 for everything: `minSdk = 30`, `targetSdk = 30`, `compileSdk = 30` |
| NDK platform | `-DANDROID_PLATFORM=android-30` |
| RAM assumption | 2–3 GB total, often under 1 GB free |
| Network | offline by default; INTERNET is used only by an explicit model-planned `web.search` request to DuckDuckGo over HTTPS; result pages are not opened |

Notes:
- `minSdk = 30` means no legacy support code and `java.time` works without desugaring.
- If a newer Android Gradle Plugin refuses `compileSdk = 30`, raise only `compileSdk` (for example 34) and keep `minSdk`/`targetSdk` at 30.
- Build only `armeabi-v7a`. Do not add `arm64-v8a`, `x86` or other ABIs.

## 3. What to use (stack)

| Need | Use | Why |
|---|---|---|
| Language | Kotlin (JDK 17) | Standard for Android |
| Build | Gradle + AGP, CMake 3.22.1, NDK r27 (or newer) | armv7 is still supported |
| Model runtime | **llama.cpp** (vendored at a pinned commit, built with CMake) | Runs GGUF models on CPU, supports 32-bit ARM with NEON, supports grammar-constrained output |
| Model files | Small quantized GGUF (about 0.3–1.5B parameters, Q4 or Q5, file at most about 1.2 GB) | Must fit 32-bit address space and low RAM. Example models: Qwen2.5-0.5B/1.5B-Instruct, Llama-3.2-1B-Instruct, SmolLM2-360M/1.7B-Instruct |
| Database | `com.github.requery:sqlite-android` | Bundles SQLite with FTS5 so it does not depend on the phone's system SQLite |
| UI | Plain Android Views: Activity, RecyclerView, Material components | Compose is heavy on low-RAM 32-bit phones |
| Async | Kotlin coroutines (one single-threaded queue for the model) | Safe native access |
| Speech (optional, later) | Android `TextToSpeech` and `SpeechRecognizer` | No extra libraries |
| Scripts / plugins (later) | QuickJS (small C library) | Tiny, runs on armv7 |
| Settings | SharedPreferences | Enough |

Avoid: GPU backends (Vulkan/OpenCL), OpenMP, arm64-only optimizations (dotprod, i8mm, fp16 arithmetic, KleidiAI), large ML SDKs.

## 4. Architecture

```
UI (chat, models, memory screens)
        │
   AgentController  ── one serialized queue, owns the model
        │
   Router ──► 1. Rules (fast path)        ─► Tool runtime ─► Verifier
          ├─► 2. Planner (model + grammar)─►      ▲               │
          └─► 3. Chat (model + memory)            └───────────────┘
        │
   Memory: messages · topic · summary · facts · action log   (SQLite)
        │
   Engine: llama.cpp via JNI (armv7 NEON)
```

Package layout (`com.pocketmind`):

```
engine/   LlamaEngine, ModelStore
core/     AgentController, Router, PromptBuilder, TokenBudget, RuntimePolicy
memory/   MessageStore, ConversationRecall, TopicState, Summary, FactStore, ActionLog
tools/    ToolRegistry, ToolValidator, ToolRuntime, Verifier, tools/*.kt
ui/       MainActivity, ModelsActivity, MemoryActivity, ChatAdapter
```

## 5. Build setup (exact)

**Gradle (`app/build.gradle.kts`)**
- `compileSdk = 30`, `minSdk = 30`, `targetSdk = 30`, `ndkVersion` = installed NDK r27+
- `ndk { abiFilters += "armeabi-v7a" }`
- `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1"; arguments += listOf("-DANDROID_PLATFORM=android-30", "-DANDROID_STL=c++_shared", "-DANDROID_ARM_NEON=TRUE") } }`
- `packaging { jniLibs { useLegacyPackaging = true } }`
- Dependencies: kotlinx-coroutines-android, androidx.core-ktx, androidx.appcompat, material, recyclerview, requery sqlite-android (jitpack, restrict to group `com.github.requery`).

**llama.cpp**
- `scripts/fetch-llama.sh` clones `https://github.com/ggml-org/llama.cpp` into `vendor/llama.cpp` and checks out one **pinned commit** (choose a recent stable commit, record it in the script, never track a moving branch). `vendor/` is git-ignored.
- `CMakeLists.txt` must fail with a clear message if the ABI is not `armeabi-v7a` or if `vendor/llama.cpp` is missing.

**CMake options (all forced)**
```
GGML_NATIVE=OFF          GGML_OPENMP=OFF         GGML_LLAMAFILE=OFF
GGML_CPU_KLEIDIAI=OFF    GGML_CPU_ALL_VARIANTS=OFF  GGML_BACKEND_DL=OFF
GGML_CUDA/VULKAN/METAL/OPENCL/SYCL/BLAS/RPC=OFF
GGML_CPU_ARM_ARCH=armv7-a
BUILD_SHARED_LIBS=OFF
LLAMA_BUILD_COMMON/TESTS/TOOLS/EXAMPLES/SERVER=OFF
```
Compile flags: `-O3 -mfpu=neon -mfloat-abi=softfp`. Build one shared library `libpocketmind.so` (JNI bridge) linked to `llama`, `log`, `android`.

**Android 11 manifest facts**
- Add `<queries>` entries (launcher intent, settings intents) so app search/open works under package visibility rules, or request `QUERY_ALL_PACKAGES` only if truly needed.
- Declare the foreground service used during generation (`foregroundServiceType` is introduced at API 30; use `dataSync` or `specialUse` as fits) plus a notification channel.
- Storage: use the system file picker (SAF) to import models and copy them into the app's private files directory. Do not request broad storage permission.
- Permissions to add as features arrive: `SET_ALARM` (normal, auto-granted), `WRITE_SETTINGS` (special, user grants), `POST_NOTIFICATIONS` is not needed on API 30.

## 6. Engine rules (`engine/`)

- JNI API: `load(path, nCtx, nBatch, nUbatch, threads)`, `generate(messages, maxTokens, grammar?, onToken)`, `countTokens(text)`, `cancel()`, `unload()`, `stats()`.
- One engine instance, called only from the single model queue.
- Context: default 512; any context change is user-selected, not based on a free-RAM gate. Threads: 2 by default (user adjustable, max 4).
- Use `mmap` loading. Reject model files above the size limit before loading.
- Do not gate model loading or generation on free RAM, automatically stop generation, or automatically unload on memory callbacks or after idle time. Keep Stop and Unload as explicit user controls.
- Support **grammar-constrained generation** (GBNF) so the planner can only output valid JSON. This is the key trick that makes tiny models usable for tool calling.

## 7. The router (`core/Router`)

Order of attempts for each user message:

1. **Rules**: keyword/regex matcher for the common commands. Instant, no model. Handles negation ("don't set an alarm").
2. **Planner**: only if rules fail and the message looks like an action request. Send the model the user text plus a **short list of the most relevant tools** (top 5 by keyword/FTS score, not all tools) and force JSON output with a grammar:
   ```
   {"steps":[{"tool":"alarm.set","args":{"time":"06:30","label":"Wake up"}}]}
   ```
   or `{"steps":[]}` when no tool fits.
3. **Chat**: normal conversation with the memory context from Section 8.

If the planner returns an empty plan, fall through to chat. Cap at 8 steps per plan.

## 8. Memory (`memory/`) – what makes follow-ups work

All in one SQLite database. Create tables with `CREATE TABLE IF NOT EXISTS`; keep a feature version so derived data can be rebuilt.

1. **Recent turns window**: always put the last 3–4 turns in the prompt, trimmed oldest-first to the token budget. This alone fixes most "what about him?" failures.
2. **Topic tracker**: table `topic_state(id=1, entity, kind, last_question, updated_at)`. Extract the main entity with simple rules after each turn; add one `Topic: …` line to the prompt; expire after 30 idle minutes.
3. **Follow-up resolver (no model)**: rewrites short pronoun/wh follow-ups ("why did he do it?") into standalone questions using the topic. Handle he, she, it, they, him, her, this, that, and common Hinglish forms. If unsure, do not rewrite. Never rewrite tool commands.
4. **Rolling summary**: table `conversation_summary`, at most about 80 tokens, extractive at first; covers turns that left the window.
5. **Long-term facts**: table `facts(key, value, tags, importance, source, last_used)`. Triggers: "remember…", "my name is…", "I like…", "forget…". Put the top 3 relevant facts (about 60 tokens) in the prompt. User can view and delete facts in `MemoryActivity`.
6. **Recall**: FTS5 over past messages plus a small hashed-feature vector (256 dims) for ranking; return at most 2 snippets (about 700 characters), never the current message.
7. **Action log**: table `action_log(id, request, tool, args, outcome, message, created_at)`, last 200 rows. Lets the bot answer "why did you do that?" from real records, without the model guessing.

**Prompt budget at nCtx = 1024** (scale down proportionally for 512): instructions 60, facts 60, summary 80, topic 15, recent turns 250, question 100, answer 128. Drop in this order when short: recalled snippets, summary, facts, oldest turns. Never drop the question.

## 9. Tools and the verify loop (`tools/`)

**Registry entry:** `id`, description, argument schema (name → type, range), `risk` (low/medium/high), required permission, and a **verifier**.

**Pipeline for every plan step:** validate (schema, ranges, allowed keys) → check required Android permission → run directly after validation (no risk-based confirmation dialog) → **verify** → retry only safe failures → record in `action_log` → report.

**Result statuses:** `VERIFIED`, `HANDED_OFF` (opened a system screen or permission screen the user must finish), and `FAILED`. The assistant may say "done" **only** for `VERIFIED`. For anything else it says exactly what happened ("I opened the Wi-Fi panel; please toggle it").

**Starter tools and what is realistic on Android 11 (API 30):**

| Tool | How | Verified by | Notes |
|---|---|---|---|
| clock.time / clock.date | `java.time` | the value itself | automatic |
| math.calculate | small own parser | the value itself | automatic |
| note.save / list / search | SQLite + file | read-back | automatic |
| alarm.set | `AlarmClock.ACTION_SET_ALARM` with `EXTRA_SKIP_UI = true` (needs `SET_ALARM`) | `AlarmManager.getNextAlarmClock()` if it is the next alarm, else `HANDED_OFF` | or own `setAlarmClock()` alarm for full control |
| timer.set | `AlarmClock.ACTION_SET_TIMER` with `EXTRA_SKIP_UI = true` | usually `HANDED_OFF` | |
| flashlight | `CameraManager.setTorchMode` | torch callback state | normally needs no camera permission; confirm on device |
| volume | `AudioManager.setStreamVolume` | read back stream volume | Do Not Disturb can block changes |
| brightness | `Settings.System.putInt` (needs `WRITE_SETTINGS` granted by user) | read back | |
| battery / storage / RAM status | system services | the value itself | automatic |
| app.open / app.search | `PackageManager` + launch intent (needs `<queries>`) | launch resolved | `HANDED_OFF` |
| wifi / bluetooth / airplane / location | open settings or `Settings.Panel` | none | **Android 11 apps cannot silently toggle Wi-Fi or airplane mode.** Bluetooth enable may work for apps targeting API 30; verify on device. Otherwise `HANDED_OFF` |

Full silent control of the last row needs an Accessibility service (Phase 3) or root/Shizuku, which are optional and opt-in.

## 10. Safety rules

- The model never runs raw shell commands. Only registry tools, only validated arguments.
- Medium and high risk tools always ask the user first.
- Treat model output as untrusted input: strict JSON via grammar, then schema validation again in code.
- No claim of success without verification. No invented memories.
- Never log message text; log ids, tool names, counts and timings.
- "Clear memory" must wipe recall data, summary, topic, auto-created facts and the action log.

## 11. UI (keep small)

- Chat screen: message list, input, streaming text, Stop button, status line (model state, "action verified / opened for you").
- Models screen: import GGUF, choose, load/unload, context and threads.
- Memory screen: toggles, facts list with delete, "Clear memory".

## 12. Milestones

1. **Skeleton and engine**: project builds for `armeabi-v7a` API 30; a small model loads and generates in the Chat screen.
2. **Chat**: streaming chat, stop, model management, database, memory-free.
3. **Memory**: Section 8 complete (window, topic, resolver, summary, facts, recall).
4. **Tools and rules**: registry, validator, verify loop, starter tools, action log.
5. **Planner**: grammar-constrained JSON planning with top-5 tool selection; valid plans execute directly after schema validation, with Android permission hand-offs preserved.
6. **Polish**: foreground service stability, settings, error messages.
7. **Optional**: Accessibility control, text-to-speech/speech input, QuickJS plugins, character personas.

## 13. Rules for the agent

- Work from an empty repository on feature branches; `main` always builds.
- One commit per sub-step, each passing the Android build.
- Pin every external dependency (llama.cpp commit, library versions).
- Add nothing outside Section 3 without a written reason.
- Do not claim device behaviour you did not run; list unverified items in the PR.
- Keep `README.md` current: how to fetch llama.cpp, build, and import a model.

## 14. Definition of done (first release)

- APK contains only `armeabi-v7a` libs and installs on Android 11 (API 30).
- Model imports, loads, chats with streaming and can be stopped.
- Facts survive restart; clear-memory works.
- At least alarm, timer, notes, flashlight and battery work through the verify loop with honest statuses.
- A 15-minute mixed session on a real 32-bit phone runs without a crash or out-of-memory stop.

## 15. Known risks

- Small models are weak at reasoning; that is why rules, grammar-constrained JSON, short tool lists and deterministic follow-up rewriting exist.
- Speed on 32-bit CPUs is low; measure it and tune context, threads and model size before promising anything.
- 32-bit address space limits model size and context; keep models small.
- Phone vendors (for example MIUI) change Clock, settings and battery behaviour; verification may return `HANDED_OFF` on some devices, and that is acceptable.
