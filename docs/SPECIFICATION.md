# Technical Specification: Jandal AI — Local-First Android AI Assistant

> **Last updated:** 2026-04-13
>
> This is the authoritative technical specification for Jandal AI. For feature status and
> delivery timeline, see [`ROADMAP.md`](./ROADMAP.md).

---

## 1. Executive Summary

Jandal AI is a privacy-centric, on-device mobile assistant for Android. All inference runs
entirely on-device via Google AI Edge (LiteRT) — no cloud APIs, no telemetry, no data
leaving the device. The system uses a **Resident Agent Architecture** with three tiers of
intent routing, a local RAG memory pipeline, and a modular skill framework for extensibility.

**Design principles:**
- Local-first: all inference, memory, and skill execution on-device
- Resident model: Gemma-4 stays loaded on GPU — no cold-start per query
- Deterministic fast path: simple device actions handled by regex (<5ms), not ML
- Context-window managed: recursive summarisation, never truncate history
- Security-first: explicit intents only, Wasm sandboxing, no prompt-injection exfiltration

---

## 2. System Architecture

The assistant is built on a **Brain–Memory–Action** triad, orchestrated centrally in Kotlin.

```
┌──────────────────────────────────────────────────────────┐
│                     User Input (text/voice)               │
└────────────────────────────┬─────────────────────────────┘
                             │
                    ┌────────▼────────┐
                    │  Tier 1          │  Wake word (future: Picovoice Porcupine)
                    │  "Hey Jandal"   │  Always-on, ~0MB, triggers Tier 2
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │  Tier 2          │  QuickIntentRouter (Kotlin regex)
                    │  Fast Actions   │  ~0MB, <5ms — torch, timer, alarm,
                    │                 │  DND, bluetooth, wifi, time, battery
                    └────────┬────────┘
                             │ no match
                    ┌────────▼────────┐
                    │  Tier 3          │  Gemma-4 E-4B / E-2B on GPU
                    │  Deep Reasoning │  RAG context + native tool calling
                    │                 │  TTFT ~2.3s, resident in memory
                    └─────────────────┘
```

### 2.1 Module Structure

```
:app                  Entry point, Hilt DI, navigation, splash screen
:core:inference       LiteRtInferenceEngine, ModelConfig, LlmDispatcher, hardware tier detection
:core:memory          sqlite-vec JNI bridge, EmbeddingGemma, RAG pipeline, Room entities
:core:skills          SkillRegistry, SkillExecutor, QuickIntentRouter, native Kotlin skills
:core:wasm            Chicory Wasm host runtime, bridge functions, resource limiting
:core:ui              Shared Compose components, Material 3 theme
:feature:chat         ChatScreen, ChatViewModel, ActionsScreen, ActionsViewModel
:feature:settings     Memory management, model settings, persona config
:feature:onboarding   First-launch model download flow
```

### 2.2 Model Inventory

| Model | Role | Size | Backend | Loading |
|-------|------|------|---------|---------|
| Gemma-4 E-4B | Reasoning, tool calling | ~3.4GB | GPU (OpenCL) | Eager at startup |
| Gemma-4 E-2B | Reasoning (8GB devices) | ~1.5GB | GPU (OpenCL) | Eager at startup |
| EmbeddingGemma-300M | Semantic embeddings (768-dim) | <200MB | CPU | Lazy on first RAG query |

> **FunctionGemma-270M deprecated (Apr 2026):** Its 289MB footprint causes lmkd to
> terminate the process during Gemma-4's GPU kernel compilation peak (~4–5GB transient).
> After testing 5 loading strategies (#219), simple device actions were migrated to a
> zero-memory `QuickIntentRouter`; complex tool calls use Gemma-4 natively (#218, #219, #220).

### 2.3 Hardware Tiering

| Component | Performance Tier (12GB+ RAM) | Compatibility Tier (8GB RAM) |
|-----------|------------------------------|------------------------------|
| Reasoning | Gemma-4 E-4B | Gemma-4 E-2B |
| Embeddings | EmbeddingGemma-300M (768-dim) | EmbeddingGemma-300M (256-dim Matryoshka) |
| GPU backend | OpenCL (Adreno 740) | OpenCL |
| Context window | 4,000 tokens | 2,000 tokens |
| RAM (resident) | ~3.4GB (E4B) + ~200MB (EG) | ~1.5GB (E2B) + ~200MB (EG) |

> **Token alignment guard:** Exact powers of 2 (4096, 8192) are avoided for `maxNumTokens`.
> `safeTokenCount()` nudges values down by ~2.4% (e.g. 4096→4000) to prevent a LiteRT GPU
> `reshape::Eval` buffer-alignment bug on Adreno GPUs.

### 2.4 LlmDispatcher

All LiteRT operations run on a single-threaded `LlmDispatcher` (`Executors.newSingleThreadExecutor`,
thread name `llm-inference`). This prevents concurrent native API calls on the non-thread-safe
LiteRT engine. All coroutines doing inference use `withContext(LlmDispatcher)`.

### 2.5 GPU OOM Protection

`InferenceLoadingService` is a foreground service (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`) that
starts during Gemma-4 GPU initialisation (~20s). It keeps `oom_score_adj` at 0, preventing
Samsung lmkd from demoting the process to cached (adj ~700) during the GPU kernel compilation
peak. The service stops automatically once `InferenceEngine.isReady` becomes true.

---

## 3. Memory Architecture

### 3.1 Short-Term (Session) Memory

- **Implementation:** LiteRT-LM KV Cache
- **Window:** 4,000 tokens (Performance) / 2,000 tokens (Compatibility)
- **Proactive reset:** At ~75% capacity, `ChatViewModel` injects history-aware system prompt
  and resets the conversation, preserving selected turns
- **Token estimation:** `ContextWindowManager.estimateTokens()` (~4 chars/token heuristic)

### 3.2 Prompt Assembly Order

```
[System Prompt]          ← persona, date/time, runtime info
[User Profile]           ← always injected (structured identity fields)
[Core Memories]          ← permanent facts (CORE_MAX_DISTANCE=0.55, topK=10)
[Episodic Memories]      ← distilled conversation summaries (EPISODIC_MAX_DISTANCE=0.40, topK=3)
[Message History]        ← semantically relevant messages from current conversation (MAX_DISTANCE=0.40, topK=5)
[Conversation Window]    ← selected recent turns (75% token budget)
[Current User Message]   ← with RAG context prepended
```

All three memory sections are conditionally included — omitted entirely if no results meet their distance threshold. Token budget is allocated sequentially: Core → Episodic → Message History.

### 3.3 Long-Term (Semantic) Memory

- **Vector store:** sqlite-vec (compiled via NDK for arm64-v8a), bundled as `libkernelvec.so`
- **Embedding model:** EmbeddingGemma-300M — 768-dim vectors (256-dim on 8GB via Matryoshka)
- **Three vec0 tables:**
  - `core_memories_vec` — permanent facts about the user (visible in Settings → Core Memories)
  - `episodic_memories_vec` — distilled conversation summaries from `EpisodicDistillationUseCase` (visible in Settings → Episodic Memories)
  - `message_embeddings` — per-message vectors for intra-conversation fuzzy recall (visible in Settings → Message History (RAG))
- **Retrieval:** `vec_distance_cosine()` similarity search per query; top results injected into prompt
- **Separate databases:** Room (`kernel_db.db`) for relational data; native SQLite (`kernel_vectors.db`) for vectors (Room doesn't support vec0 virtual tables)
- **TTL & pruning:** `prune()` runs on every write with two independent passes:
  1. **TTL pass** — deletes episodic memories where both `createdAt` and `lastAccessedAt` are older than 30 days. Accessing a memory resets `lastAccessedAt`, keeping it alive past the 30-day TTL for as long as it remains in use.
  2. **LRU overflow pass** — if count still exceeds 500 after TTL pass, evicts the least-recently-accessed entries (ordered by `lastAccessedAt ASC`). `lastAccessedAt` is updated on every RAG retrieval, so frequently recalled memories survive overflow eviction even if old.
  - Core memories: no TTL, capped at 200 entries, evicted by `createdAt` order (no LRU — core memories are considered permanent).

### 3.4 Episodic Distillation

On conversation close, `EpisodicDistillationUseCase` uses Gemma-4 to summarise the conversation
into 3–5 episodic memory sentences, stored in `episodic_memories_vec`. This runs fire-and-forget
on `Dispatchers.IO` from `ChatViewModel.onCleared()`.

---

## 4. Skill & Tool Framework

### 4.1 Tier 2: QuickIntentRouter

A pure-Kotlin regex/keyword matcher with zero ML overhead. Deterministically maps user input
to one of ~8 supported device actions. Executes the matched action directly via Android OS APIs.

| Pattern | Action | OS API |
|---------|--------|--------|
| "turn on/off torch/flashlight" | Torch toggle | `CameraManager.setTorchMode()` |
| "set a timer for X minutes" | Timer | `AlarmClock.ACTION_SET_TIMER` + `EXTRA_SKIP_UI` |
| "set an alarm for X:XX" | Alarm | `AlarmClock.ACTION_SET_ALARM` |
| "turn on/off do not disturb/dnd" | DND | `NotificationManager.setInterruptionFilter()` |
| "turn on/off bluetooth" | Bluetooth | `BluetoothAdapter.enable()/disable()` |
| "turn on/off wifi" | Wi-Fi | `WifiManager.setWifiEnabled()` |
| "what time is it / what's the date" | Time/Date | `LocalDateTime.now()` |
| "battery level / how much battery" | Battery | `BatteryManager.getIntProperty()` |

If no pattern matches → query falls through to Tier 3 (Gemma-4).

**Design constraints:**
- No ML model loaded — available at ~0ms from app start
- Deterministic: same input always produces same result
- Testable: pure function, no Android dependencies in unit tests (via interface)

### 4.2 Tier 3: E4B Native Tool Calling

Gemma-4 emits JSON function-call blocks when it determines a tool should be used:
```json
{"name": "get_weather", "arguments": {"location": "Auckland"}}
```

`ChatViewModel.tryExecuteToolCall()` detects this pattern in the model's output, hands it to
`SkillExecutor`, which parses the JSON, looks up the skill in `SkillRegistry`, validates required
parameters, and calls `Skill.execute(call)`.

**Skill interface:**
```kotlin
interface Skill {
    val name: String
    val description: String
    val schema: SkillSchema       // JSON Schema for parameter validation
    suspend fun execute(call: SkillCall): SkillResult
}
```

**Registered skills:**

| Skill name | Description | Status |
|------------|-------------|--------|
| `get_weather` | Current weather via Open-Meteo (free, no API key). Uses device GPS (`current`) or geocodes a city name. Returns temp, feels-like, humidity, wind speed, precipitation chance. | ✅ Wired |
| `get_system_info` | Device/model/backend/battery stats | ✅ Wired |
| `save_memory` | Persist a note/fact to Room | ⚠️ Partial |
| `set_timer` | Set a countdown timer | ⚠️ Needs AlarmManager wire-up |
| `run_intent` | OS intent dispatch (SET_ALARM, SEND_EMAIL, etc.) | ✅ Wired |

Tool definitions (name, description, parameter schema) are injected into the system prompt via
`SkillRegistry.buildFunctionDeclarationsJson()` so Gemma-4 knows available tools and expected output format.

### 4.3 Extensible Skills (WebAssembly)

Community-extensible skills run sandboxed via **Chicory** (pure JVM Wasm runtime, v1.0+).

- **Sandboxing:** Wasm modules have no direct OS access; all capabilities via explicit Kotlin bridge functions
- **Resource limits:** 5s wall-clock timeout (coroutine), 16MB memory cap, 1MB output limit
- **Authoring:** Rust → Wasm via `wasm-pack` / `cargo-component`
- **Network:** Domain-scoped bridge functions only (e.g. `fetchHomeAssistant(path)` with URL allowlist) — never a generic `fetch()`
- **Sideloading:** Import section audit + mandatory "Accept Risk" dialog for unsigned skills

---

## 5. Security & Trust Model

| Area | Requirement |
|------|-------------|
| **Intents** | Explicit intents only — never implicit intents for SMS, email, etc. |
| **Wasm sandboxing** | Non-negotiable: no direct OS capabilities in Wasm modules |
| **Network in Wasm** | Domain-scoped bridge functions with URL allowlist validation |
| **Email exfiltration guard** | `EXTRA_EMAIL` never populated from LLM output — user enters recipient manually |
| **Prompt injection** | FunctionGemma output always validated against SkillRegistry schema before execution |
| **Sideloaded skills** | Wasm import section audited; user must acknowledge "Accept Risk" |
| **LeakCanary** | Integrated from day one — model weight leaks caught early |

---

## 6. UI/UX

- **Framework:** Jetpack Compose, Material 3 Dynamic Color
- **Theme:** Dark default (AMOLED-friendly), supports light/dark toggle
- **Navigation:** Bottom nav bar — Chats tab (conversations list) + Actions tab (quick commands)
- **Chat:** Streaming token display, thinking mode indicator, markdown rendering, multi-conversation
- **Actions tab:** History list, FAB (⚡) for new commands, bottom sheet input, Room-persisted history
- **Voice:** Tap-to-toggle with auto-stop on silence (future: "Hey Jandal" wake word)
- **Skill results:** Inline rich cards in the conversation stream
- **Persona:** Friendly, concise, slightly playful (Kiwi-flavoured) — future: configurable

---

## 7. Development Prerequisites

| Requirement | Detail |
|-------------|--------|
| Machine RAM | 32GB minimum (LiteRT builds + Android Emulator) |
| Android Studio | Ladybug (2024.2.1) or newer |
| Android NDK | Required for sqlite-vec JNI bridge |
| JDK | 21 (Homebrew OpenJDK) |
| Min SDK | API 35 (Android 15) |
| Target SDK | 36 |
| Test device | Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2, 12GB RAM, Android 16) |

**Backend note:** `Build.SOC_MANUFACTURER` on S23 Ultra returns `"QTI"` (not `"Qualcomm"`),
so `hasQualcommNpu = false` and the backend falls through to GPU (OpenCL / Adreno 740).
The NPU path requires `"Qualcomm"` string match — this is a known device quirk.

---

## 8. Build & Test

```bash
./gradlew assembleDebug              # Build debug APK
./gradlew test                       # Unit tests (JUnit 5 + MockK)
./gradlew testDebugUnitTest          # Unit tests, debug variant only
./gradlew connectedDebugAndroidTest  # Compose UI tests (requires connected device)
./gradlew lint                       # Android lint
./gradlew installDebug               # Build + install on connected device
./gradlew :core:inference:test       # Single-module test
```

**CI:** Runs lint + unit tests + debug build. No real model inference in CI — `InferenceEngine`
is behind an interface and mocked in all tests. Models (~3GB) are never downloaded in CI.

---

## 9. Roadmap Summary

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Core LiteRT-LM chat + GPU/NPU + GPU alignment fixes + OOM protection | ✅ Complete |
| 2 | sqlite-vec RAG + EmbeddingGemma + episodic distillation + memory UI | ✅ Complete |
| 3 | Resident Agent Architecture: QuickIntentRouter + E4B tool calling + Voice I/O | 🔄 In Progress |
| 4 | Dreaming Engine (overnight distillation) + Semantic Cache + Self-Healing Identity | ⬜ Planned |
| 5 | Chicory Wasm Runtime + GitHub Skill Store | ⬜ Planned |
| 6 | 8GB device optimisation (dynamic weight loading, E2B auto-select) + wake word | ⬜ Planned |

See [`ROADMAP.md`](./ROADMAP.md) for full task-level detail.
