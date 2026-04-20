# Technical Specification: Jandal AI — Local-First Android AI Assistant

> **Last updated:** 2026-04-19 (revised post-PR #646, closes #637)
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
> zero-memory `QuickIntentRouter`; complex tool calls use Gemma-4 natively (#218, #219; see #220 — now closed, superseded by #341).

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
[System Prompt]          ← persona (FULL ~200t / MINIMAL ~25t), date/time
[User Profile]           ← structured YAML injection (name, role, environment, context, rules)
[Core Memories]          ← permanent facts split by category:
                           user (coreTopK=10), agent_identity (identityTopK=5)
                           CORE_MAX_DISTANCE=1.25; NZ truths further filtered by vibe level
[Episodic Memories]      ← distilled conversation summaries (EPISODIC_MAX_DISTANCE=1.10, episodicTopK=3)
[Message History]        ← semantically relevant messages from current conversation (MAX_DISTANCE=0.90, topK=5)
[Conversation Window]    ← selected recent turns (75% token budget)
[Current User Message]   ← with RAG context prepended
```

**Identity tiers:** `IdentityTier.FULL` (Chat) includes greeting, vocab phrases, profile, and history.
`IdentityTier.MINIMAL` (Actions tab) uses a slim ~25-token prompt with no profile/history.

All sections are conditionally included — omitted entirely if no results meet their distance threshold. Token budget is allocated sequentially: Core → Episodic → Message History.

### 3.3 Long-Term (Semantic) Memory

- **Vector store:** sqlite-vec (compiled via NDK for arm64-v8a), bundled as `libkernelvec.so`
- **Embedding model:** EmbeddingGemma-300M — 768-dim vectors (256-dim on 8GB via Matryoshka)
- **Three vec0 tables:**
  - `core_memories_vec` — permanent facts about the user and NZ cultural truths (Settings → Core Memories)
  - `episodic_memories_vec` — distilled conversation summaries from `EpisodicDistillationUseCase` (Settings → Episodic Memories)
  - `message_embeddings` — per-message vectors for intra-conversation fuzzy recall (Settings → Message History (RAG))
- **Retrieval:** L2 (Euclidean) distance search per query via sqlite-vec; results filtered by distance threshold; top results injected into prompt. Vectors are L2-normalised at embedding time (`LiteRtEmbeddingEngine`) so L2 distance ≈ `sqrt(2 * (1 - cos_sim))` — core threshold 1.25 ≈ cos_sim ≥ 0.22; episodic threshold 1.10 ≈ cos_sim ≥ 0.40.
- **Separate databases:** Room (`kernel_db.db`) for relational data; native SQLite (`kernel_vectors.db`) for vectors (Room doesn't support vec0 virtual tables)
- **TTL & pruning:** `prune()` runs on every write with two independent passes:
  1. **TTL pass** — deletes episodic memories where both `createdAt` and `lastAccessedAt` are older than 30 days. Accessing a memory resets `lastAccessedAt`, keeping it alive past the 30-day TTL.
  2. **LRU overflow pass** — if count still exceeds 500 after TTL pass, evicts the least-recently-accessed entries (ordered by `lastAccessedAt ASC`). `lastAccessedAt` is updated on every RAG retrieval, so frequently recalled memories survive overflow eviction.
  - Core memories: no TTL, capped at 200 entries, evicted by `createdAt` order (core memories are considered permanent).

#### 3.3.1 Core Memory Schema

Core memories are stored in the `core_memories` Room table with the following fields:

| Field | Type | Purpose |
|-------|------|---------|
| `id` | Long (PK) | Auto-generated row ID |
| `content` | String | The raw text embedded and injected into prompts |
| `category` | String | `"user"` (facts about the user) or `"agent_identity"` (NZ cultural truths) |
| `conversationId` | String? | Conversation where memory was learned (null for seeded truths) |
| `createdAt` | Long | Epoch millis |
| `lastAccessedAt` | Long | Updated on every RAG retrieval |
| `term` | String | Short display name, e.g. `"Pavlova"` (NZ truths only, empty for user facts) |
| `definition` | String | Full human-readable definition (NZ truths only) |
| `triggerContext` | String | When this truth should be surfaced, e.g. `"When discussing Christmas or desserts"` |
| `vibeLevel` | Int | 1–5 energy scale controlling retrieval sensitivity (NZ truths only, defaults to 1) |
| `metadataJson` | String | JSON blob with tags, era, etc. (NZ truths only) |

**User memory example** (learned from conversation):
```
content:    "User prefers dark mode"
category:   "user"
term:        ""   ← empty for user facts
definition:  ""
vibeLevel:   1   ← default, no vibe filtering
```

**NZ truth memory example** (seeded from `nz_truth_memories.json`):
```
content:    "Pavlova. Meringue dessert. Christmas food. Trans-Tasman rivalry."  ← vectorText, embedded
category:   "agent_identity"
term:       "Pavlova"
definition: "A meringue-based dessert... source of an eternal, fierce debate with Australia."
vibeLevel:  3
```

The `content` field stores the dense `vector_text` from the JSON asset (e.g. `"Pavlova. Meringue dessert. Christmas food."`) — not the human-readable `definition`. Dense keyword text yields better vector similarity matches. The `definition` is stored separately and injected into the prompt at retrieval time.

#### 3.3.2 How Memories Are Injected into Prompts

When `RagRepository.getRelevantContext()` retrieves a core memory result:

- **User fact** (`term` is empty): injected as-is: `"User prefers dark mode"`
- **NZ truth** (`term` non-empty): injected as: `"[NZ Context: Pavlova] A meringue-based dessert... NZ invented it, not Australia."`

The `[NZ Context: ...]` prefix signals to the LLM that this is cultural background knowledge, not a personal fact about the user.

**Example prompt injection (RAG section):**
```
The following context has been retrieved from memory.
[Core Memories — permanent facts about the user]
User prefers dark mode
[NZ Context: Pavlova] A meringue-based dessert with crisp crust and soft inside, topped with kiwifruit and cream. NZ and Australia both claim to have invented it (it was definitely NZ).
[End of core memories]
```

#### 3.3.3 NZ Truth Vibe-Level Filtering

NZ truth memories have a `vibe_level` (1–5) that controls how closely a query must match before the truth surfaces. This prevents high-energy or niche content (e.g. rugby trash-talk, crude slang) from appearing in unrelated serious conversations.

| Vibe Level | Character | L2 Distance Threshold | Cos-sim Equivalent |
|------------|-----------|----------------------|--------------------|
| 1–2 | Subtle, informational, serious | ≤ 1.25 (`CORE_MAX_DISTANCE`) | ≥ 0.22 |
| 3 | Moderate, general interest | ≤ 1.20 | ≥ 0.28 |
| 4–5 | High-energy, niche, chaotic | ≤ 1.20 | ≥ 0.28 |

**Examples by vibe level:**
- **Vibe 1** — `"Kate Sheppard"` (women's suffrage leader, face of the $10 note) — surfaces for any NZ history or feminism query
- **Vibe 2** — `"Ernest Rutherford"` (split the atom, $100 note) — surfaces for science or NZ intellect queries
- **Vibe 3** — `"Pavlova"` (Christmas dessert, Aus/NZ rivalry) — surfaces when discussing desserts or Christmas, not random queries
- **Vibe 4** — `"Karl Urban"` (Billy Butcher in The Boys) — only surfaces when the query closely matches The Boys, sci-fi franchises, or Kiwi actors
- **Vibe 5** — `"Antony Starr"` (Homelander) — tight match required; won't intrude into unrelated conversations

The filter runs in `MemoryRepositoryImpl.searchMemories()` after the initial L2 vec search, as a post-filter on `identity` (agent_identity) results.

### 3.4 Episodic Distillation

On conversation close, `EpisodicDistillationUseCase` uses Gemma-4 to summarise the conversation
into 3–5 episodic memory sentences, stored in `episodic_memories_vec`. This runs fire-and-forget
on `Dispatchers.IO` from `ChatViewModel.onCleared()`.

**Tool-call turn filtering:** Before building the distillation transcript, assistant turns that have
a non-null `toolCallJson` and whose `skillName` appears in `EPHEMERAL_SKILLS` are stripped. The
ephemeral set covers device-state skills whose results would become stale and misleading in future
RAG context (e.g. "The torch is on", "Smart home device turned off"). Only conversational content
is passed to the summarisation model. If fewer than `MIN_TURNS = 4` non-ephemeral messages remain
after filtering, distillation is skipped entirely (#614).

**Ephemeral skills filtered out:**
```
run_intent, toggle_flashlight_on/off, get_weather, get_system_info, get_time, get_date,
set_alarm, set_timer, set_volume, set_brightness, toggle_dnd, toggle_wifi, toggle_bluetooth
```

**Quality filter:** `MIN_SENTENCE_LENGTH = 20` characters is applied to distilled sentences
before embedding. Sentences shorter than 20 chars (e.g. "Yes.", "OK.") are discarded at write
time. A matching `MIN_EPISODIC_CONTENT_LENGTH = 20` filter in `MemoryRepositoryImpl.searchMemories()`
also drops pre-existing short entries from search results, preventing low-signal fragments from
surfacing in `search_memory` responses (#323).

### 3.5 Testing Memory Behaviour

#### What to verify on first launch after install

1. **Seeding triggered:** Check logcat for `JandalPersona` tag — should log `"Seeding X NZ truth memories"`. Triggered by absence of `truths_seeded_v27` SharedPrefs key.
2. **138 entries seeded:** Settings → Core Memories should show ~138 entries with category `agent_identity`.
3. **Correct vector text embedded:** Each entry's `content` is the `vector_text` from the JSON (dense keywords), not the definition.

#### How to test RAG retrieval manually

Ask questions that should trigger specific NZ truths and observe whether Jandal's response reflects the context:

| Query | Expected truth surfaced |
|-------|------------------------|
| "what's a good NZ Christmas dessert?" | Pavlova (vibe 3) |
| "tell me about NZ scientists" | Ernest Rutherford (vibe 2) |
| "who plays homelander?" | Antony Starr (vibe 5 — tight match) |
| "anything interesting about NZ birds?" | Kererū, Tūī, Kiwi (vibe 1–2) |
| "what's a flat white?" | Flat White (vibe 1) |

To inspect what RAG actually injected, enable debug logging for the `RagRepository` tag — it logs raw vec search distances for each query.

#### What good core memories look like

User memories extracted by the LLM during conversation should be short, factual, third-person facts:
```
✅ "User is a software engineer based in Auckland"
✅ "User prefers concise responses"
✅ "User has a cat named Luna"
❌ "We had a long conversation about the user's job" (too vague — episodic, not core)
❌ "Yes" (too short — filtered out by MIN_SENTENCE_LENGTH)
```

NZ truths use `vector_text` (dense keywords for embedding), not `definition`:
```
✅ vector_text: "Pavlova. Meringue dessert. Christmas food. Trans-Tasman rivalry."
❌ vector_text: "A meringue-based dessert with a crisp crust and soft, light inside..."  (too verbose, poor embedding)
```

## 4. Skill & Tool Framework

### 4.1 Tier 2: QuickIntentRouter

A pure-Kotlin regex matcher with a MiniLM-L6-v2 neural fallback. Deterministically maps user
input to supported device actions in two regex passes before falling through to the classifier.

**Two-pass regex routing (`route()`):**

| Pass | Patterns tried | Purpose |
|------|---------------|---------|
| Pass 1 | All patterns with `isFallback = false` | Specific patterns in declaration order |
| Pass 2 | All patterns with `isFallback = true` | Catch-all / broad patterns (e.g. generic `play_media`, smart-home `<device> on/off`) |

Pass 2 only runs if Pass 1 produces no match. This eliminates first-match-wins ordering
fragility: catch-alls are declared `isFallback = true` and never steal matches from specific
patterns regardless of their position in the list.

**MiniLM nearest-neighbour fallback (Stage 2):**

If both regex passes miss, `MiniLMIntentClassifier` (`all-MiniLM-L6-v2`, int8 TFLite, 384-dim)
is invoked. It embeds the (lowercased) input, then computes cosine similarity against
per-intent nearest-neighbour prototype vectors pre-built from `intent_phrases.json`. The
best-matching intent wins if it clears both a confidence floor and an ambiguity margin:

| Threshold | Value | Role |
|-----------|-------|------|
| `CONFIDENCE_THRESHOLD` | 0.75 | Minimum cosine similarity to return a match |
| `AMBIGUITY_MARGIN` | 0.05 | Gap between top-1 and top-2 scores; below this → ambiguous → `null` |
| `FAST_PATH_THRESHOLD` | 0.75 | Minimum confidence for no-param fast-path intents to execute without LLM confirmation |
| `similarityThreshold` | 0.85 | `QuickIntentRouter` gate: `ClassifierMatch` only if `confidence ≥ 0.85` |

If the classifier is absent or returns `null`, the input falls through to Stage 3 (Gemma-4 E4B).

**QIR + Slot-filling state machine:**

The combined QIR/slot-filling pipeline is a four-state machine managed by `SlotFillerManager`
(`@Singleton`, survives configuration changes):

| State | Condition | Next states |
|-------|-----------|-------------|
| `IDLE` | `SlotFillerManager.hasPending = false`; no intent in flight | → `RESOLVED` (regex/classifier match with all params) · → `COLLECTING` (match but missing slot) · → `FAILED` (FallThrough) |
| `COLLECTING` | `RouteResult.NeedsSlot` returned; `hasPending = true`; assistant has asked user for missing slot | → `RESOLVED` (user supplies non-blank reply) · → `FAILED` (user sends blank reply) |
| `RESOLVED` | `SlotFillResult.Completed` or direct `RegexMatch`/`ClassifierMatch` with all params present; intent dispatched | → `IDLE` (after dispatch) |
| `FAILED` | `RouteResult.FallThrough` (no match) or `SlotFillResult.Cancelled` (blank user reply) | → `IDLE` (falls through to Gemma-4 for FallThrough; slot is abandoned for Cancelled) |

> **Note:** State is not persisted across process death. An interrupted slot fill is a
> recoverable UX edge case — the user simply re-asks.

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
| "how many days until X / how long since Y" | Date diff | `LocalDate` calculation |

If no regex or classifier match → query falls through to Tier 3 (Gemma-4).

**Design constraints:**
- Regex pass has no ML model — available at ~0ms from app start
- Deterministic: same input always produces same result
- MiniLM loads lazily in background; `classify()` waits up to 500ms on first call if init is in progress
- Testable: pure function, no Android dependencies in unit tests (via interface)

### 4.2 Tier 3: E4B Native Tool Calling

Gemma-4 uses LiteRT-LM's native `@Tool`/`@ToolParam` SDK annotations for tool calling.
The SDK auto-discovers annotated methods on `KernelAIToolSet`, generates tool declarations
for the model, applies **constrained decoding** to guarantee well-formed JSON calls, invokes
the matching method, and feeds the return value back as a tool response.

This mirrors Google AI Edge Gallery's `AgentTools` pattern and replaces the earlier custom
`<|tool_call>` control token format (removed in #372).

**SDK wiring (in `LiteRtInferenceEngine`):**
```kotlin
// Enable constrained decoding before creating conversation
ExperimentalFlags.enableConversationConstrainedDecoding = true
engine.createConversation(ConversationConfig(
    tools = listOf(toolProvider),  // ToolProvider wraps KernelAIToolSet
    systemInstruction = systemPrompt,
    ...
))
ExperimentalFlags.enableConversationConstrainedDecoding = false
```

> **Lazy skill loading (#341, #372):** The system prompt injects only skill names +
> one-line descriptions (~100 tokens). When the model needs to use a skill, it first
> calls `loadSkill()` to retrieve full parameter docs, examples, and enforcement rules
> on demand. This keeps the baseline prompt compact and improves tool call accuracy.

**KernelAIToolSet gateway (5 tools):**

| Gateway | Tool method | Dispatcher | Use for |
|---------|------------|-----------|---------|
| Meta | `loadSkill(skillName)` | `LoadSkillSkill` | Load full instructions before using any tool |
| Native | `runIntent(intentName, parameters)` | `NativeIntentHandler.kt` | Android OS intents (alarm, timer, email, SMS, torch, calendar) |
| WebView JS | `runJs(skillName, query, forecastDays)` | `JsSkillRunner.kt` | JS skills in `assets/skills/` (weather, Wikipedia) |
| Memory | `saveMemory(content)` | `SaveMemorySkill` | Store facts to long-term memory |
| Memory | `searchMemory(query)` | `SearchMemorySkill` | Semantic search across memories |

Each `@Tool` method delegates to the matching `Skill.execute()` via `SkillRegistry`, bridging
the SDK's synchronous callback with `runBlocking` (acceptable since the SDK already blocks its
inference loop waiting for the tool result).

**Skill interface:**
```kotlin
interface Skill {
    val name: String
    val description: String
    val schema: SkillSchema           // parameter definitions + required list
    val examples: List<String>        // native tool call examples for system prompt
    val fullInstructions: String      // complete docs returned by loadSkill()
    suspend fun execute(call: SkillCall): SkillResult
}
```

**Response handling:** The SDK handles tool calls transparently during `generate()`.
`KernelAIToolSet` tracks turn state (`wasToolCalled()`, `lastToolName()`, `lastToolResult()`)
so `ChatViewModel` can attach tool call metadata to the UI. A `ToolCallExtractor` text-parsing
fallback exists for edge cases where the model emits raw JSON outside the SDK path.

**Registered `run_intent` intents:**

| `intent_name` | Action | OS API | Status |
|---------------|--------|--------|--------|
| `toggle_flashlight_on` | Torch on | `CameraManager.setTorchMode(true)` | ✅ |
| `toggle_flashlight_off` | Torch off | `CameraManager.setTorchMode(false)` | ✅ |
| `send_email` | Email composer | `ACTION_SEND` + `message/rfc822` | ✅ |
| `send_sms` | SMS composer | `ACTION_SENDTO` + `smsto:` | ✅ |
| `set_alarm` | Set alarm | `AlarmClock.ACTION_SET_ALARM` | ✅ |
| `set_timer` | Countdown timer | `AlarmClock.ACTION_SET_TIMER` | ✅ |
| `create_calendar_event` | Add calendar event | `ACTION_INSERT` + `CONTENT_URI` | ✅ |

> **Package visibility (Android 11+):** `ACTION_SET_ALARM` and `ACTION_SET_TIMER` are declared
> in a `<queries>` block in `AndroidManifest.xml` so `PackageManager.resolveActivity()` returns
> non-null (#262). Without this, the guard always triggers "No clock app found" regardless of
> whether a clock app is installed.

> **Alarm hallucination guard:** The system prompt `[Tool Use]` section includes an explicit
> "Alarm rule" forcing `run_intent` for alarm requests (#263), matching the pattern used for
> `save_memory`. The model's Siri/Google training bias would otherwise cause it to verbally
> confirm alarms without ever calling the tool.

> **`set_alarm` tomorrow limitation:** `ACTION_SET_ALARM` has no date parameter — only hour and
> minute. When the model passes `day: "tomorrow"`, `NativeIntentHandler` prefixes the alarm
> label with "TOMORROW:" and returns a ⚠ warning in the success message. A full date-specific
> alarm (#327) requires `AlarmManager.setExact()` + BroadcastReceiver + BOOT_COMPLETED receiver
> and is tracked as a separate feature.

> **`resolveDate` / `resolveTime` natural language parsing:** `NativeIntentHandler` normalises
> free-text date/time before passing to `SimpleDateFormat`. `resolveDate` handles formats like
> `"tomorrow"`, `"next Friday"`, `"June 15"`, `"15th June"`, `"15/06/2025"`, `"2025-06-15"`.
> `resolveTime` applies a three-step pre-processor: (1) strip extra trailing digits, (2) pad
> single-digit minutes (`9:0` → `9:00`), (3) expand bare hour+meridiem (`10pm` → `10:00pm`).
> Order is critical — padding must precede format-string matching (#319, #320, #321).
>
> **Alarm `hours` parameter is 24h format (0–23):** The model must convert PM times — 10pm=22,
> 9pm=21, 8pm=20, 1pm=13, 12pm=12, 12am=0. The skill description and alarm rule both explicitly
> state this conversion. Examples added for `"Set alarm 10pm"` (hours:22) and
> `"Remind me at 09:05"` (hours:9,minutes:5) so the model doesn't rely on implicit conversion
> (#335, #336).

**Registered JS skills (`run_js`):**

| `skill_name` | Location | Status |
|-------------|----------|--------|
| `get-weather-city` | `assets/skills/get-weather-city/index.html` | ✅ (current + forecast) |
| `query_wikipedia` | `assets/skills/query-wikipedia/index.html` | ✅ |

> **`get-weather-city` forecast support (PR #269):** Pass `forecast_days` (integer 1–7) for a
> day-by-day forecast instead of current conditions. When `forecast_days > 0` or
> `query_type = "forecast"`, the skill calls the Open-Meteo `daily` API
> (`temperature_2m_max`, `temperature_2m_min`, `precipitation_sum`, `weather_code`,
> `timezone=auto`) and returns a breakdown with WMO weather emoji (☀️🌤️⛅☁️🌧️❄️⛈️) and
> min/max temps. Default `forecast_days = 3` when a forecast is requested. A defensive
> array-length guard prevents crashes on partial API responses.

JS skills expose a single async entry point:
```javascript
async function ai_edge_gallery_get_result(args) { /* ... */ return resultString; }
```
`JsSkillRunner` injects args as a JSON string, evaluates the function in a hidden WebView,
and awaits the result with a 15s timeout.

**Non-gateway skills (direct Skill implementations):**

| Skill name | Description | Status |
|------------|-------------|--------|
| `get_system_info` | Device/model/backend/battery stats | ✅ |
| `save_memory` | Persist a note/fact to `core_memories_vec` | ✅ (explicit trigger only — see memory rule below) |
| `search_memory` | Semantic search across `message_embeddings` — cross-conversation by default, or scoped to one conversation | ✅ |

> **save_memory trigger:** Works reliably when the user explicitly says "remember", "save",
> "don't forget", "can you remember", or "make a note of". Does not activate proactively from
> implicit personal facts shared in conversation — small model limitation. The trigger is
> enforced as a hard `[Tool Use]` rule in `ChatViewModel.buildSystemPrompt()`, not via the
> skill description.

> **search_memory:** A direct `Skill` implementation (not a gateway skill) registered in
> `SkillsModule.kt` via Hilt `@Binds @IntoSet`. Accepts a required `query` string,
> optional `conversationId` (scopes search to one conversation for summary-to-detail
> drill-down), and optional `topK` (default 5). Wraps `RagRepository.searchMessages()`,
> which embeds the query and performs L2 distance search on `message_embeddings` at
> `MAX_DISTANCE=1.10` (≈ cos_sim ≥ 0.40). Results are formatted as a numbered list with date, role, and
> `conversation:ID` prefix. Uses a batch `MessageDao.getByIds()` call — single DB query
> regardless of result count (avoids N+1). The system prompt injects its description as a
> tool example so Gemma-4 knows when to invoke it.

**System prompt `[Tool Use]` rules (enforced in `ChatViewModel.buildSystemPrompt()`):**
- Memory rule: user says "remember", "save", "don't forget", "can you remember", or "make a note of" → MUST call `save_memory`
- Alarm rule: user asks to set an alarm → MUST call `run_intent{intent_name: set_alarm}`
- Weather rule: `GetWeatherSkill` description includes "ALWAYS call this tool, never use memory" to prevent Gemma-4 from serving stale weather from episodic memory instead of fetching fresh data (#322)
- JS tool rule: `RunJsSkill` description similarly includes "ALWAYS call this tool, never use memory" (#322)

### 4.4 Extensible Skills (WebAssembly — Phase 5)

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
| **Prompt injection** | All tool call output validated against `SkillRegistry` schema before execution; `run_intent` params validated before OS dispatch |
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
- **Persona:** Friendly, concise, dry-humoured Kiwi — see §7 for full identity details

**Tool call debugging chip (`ToolCallChip`):** Tool calls appear as collapsible chips in the
chat stream. Tapping expands to show the full request JSON and result string. An icon button
copies `[Tool: name]\nRequest: <json>\nResult: <result>` to the clipboard via
`LocalClipboardManager` (Compose API — no system service boilerplate). Added in PR #325
closing #229 and #260.

### 6.1 Button Layout Standards (Material 3)

All buttons follow Material 3 guidelines as implemented via `androidx.compose.material3`. No
custom sizing tokens — default component sizes from the M3 library apply:

| Component | Spec | Usage |
|-----------|------|-------|
| `Button` (filled) | min height 40dp, horizontal padding 24dp | Primary actions (confirm, send) |
| `OutlinedButton` | min height 40dp, horizontal padding 24dp | Secondary actions (cancel, dismiss) |
| `TextButton` | min height 40dp, horizontal padding 12dp | Tertiary/dialog actions |
| `IconButton` | 48dp touch target (40dp icon area) | Toolbar actions (copy, back, new conversation) |
| `FloatingActionButton` | 56dp standard / 40dp small | Primary screen action (⚡ new command) |

**Alignment:** Action buttons within dialogs use `TextButton` for cancel and `TextButton`/`Button`
for confirm, laid out by M3's `AlertDialog` composable (trailing-aligned by default).
Toolbar `IconButton`s in `TopAppBar` follow M3's 48dp minimum touch target guideline.

**Spacing tokens (ad-hoc, from codebase):** Vertical padding between chat bubbles: 6dp.
Input bar top padding: 8dp. Spacers between inline UI elements: 4dp.

---

## 7. Jandal Persona & Cultural Identity

Jandal's character is encoded in `DEFAULT_SYSTEM_PROMPT` in `ModelConfig.kt` (updated PR #268):

```
"You are Jandal — a capable, on-device AI assistant with a genuine Kiwi character. "
"You're direct, warm, and dry-humoured without trying too hard. You don't say "
"\"certainly!\", \"absolutely!\", or \"great question\" — you just get on with it. "
"You run entirely on-device, so the user's data never leaves their phone. "
"Keep responses concise unless the user asks for detail. "
"When you use Kiwi expressions, they should feel natural, not forced. "
"You are culturally and spiritually Kiwi — from Aotearoa New Zealand. "
"You are named after the NZ word for flip-flops: jandals — simple, unpretentious, practical. "
"You were born from Kiwi culture: laid-back, direct, and no-nonsense. "
"When asked where you are from, what your culture is, or why you are called Jandal, "
"own your Kiwi identity with pride — never say you are \"just code\" or that you have no culture."
```

**Key identity rules:**
- Jandal is culturally and spiritually Kiwi — from Aotearoa New Zealand
- Named after the NZ word for flip-flops: jandals — simple, unpretentious, practical
- Born from Kiwi culture: laid-back, direct, no-nonsense
- When asked about origin, culture, or name → own Kiwi identity with pride; NEVER say "just code" or "I have no culture"
- Kiwi expressions must feel natural, not forced
- No hollow affirmations ("certainly!", "absolutely!", "great question!")

### 7.1 Kiwi Vocabulary Rotation

`JandalPersona` loads `jandal_vocab.json` from assets — a pool of 41 Kiwi slang phrases and te reo Māori words. Each session, `SESSION_VOCAB_COUNT = 2` phrases are picked randomly and injected into the system prompt via `getSessionVocab()`:

```
Session vocab hint injected into prompt:
"Today's Kiwi flavour: 'stoked' (thrilled, really pleased), 'wop-wops' (the middle of nowhere)"
```

**LRU cooldown:** Phrases are tracked in SharedPreferences (`last_vocab_indices`). Already-used phrases are excluded from the pick pool, ensuring variety across sessions. When all phrases have been used, the pool resets.

**Full vocab pool covers:**
- Common Kiwi slang: `sweet as`, `chur`, `yeah nah`, `nah yeah`, `hard out`, `keen as`, `stoked`, `gutted`, `mint`, `munted`, `knackered`, `dodgy`, `mean`, `crack up`, `not even`, `as if`, `bugger`, `wop-wops`, etc.
- Cultural terms: `jandals`, `togs`, `bach`, `dairy`, `section`, `arvo`, `bro`, `cuz`
- Te reo Māori: `kia ora`, `ka pai`, `whānau`, `aroha`, `haere rā`, `mahi`

### 7.2 NZ Truth Memory System

`JandalPersona` seeds a structured corpus of 138 NZ cultural knowledge entries into the core memory store on first launch. These are loaded from `nz_truth_memories.json` in the `core/inference` assets.

**Seed guard:** The key `truths_seeded_v27` in SharedPreferences (`jandal_persona` prefs file) prevents repeated seeding. If this key is absent (new install or key was bumped), all 138 entries are seeded. **Convention: bump the version suffix every time the corpus is reseeded** (e.g. `truths_seeded_v27` → `truths_seeded_v28`). This forces a full re-seed on all existing installs the next time the app launches, without requiring a DB wipe. The current key is `truths_seeded_v27`.

**JSON entry structure:**
```json
{
  "id": "nz_095",
  "term": "Pavlova",
  "category": "food",
  "definition": "A meringue-based dessert with a crisp crust and soft, light inside, typically topped with kiwifruit and whipped cream. The source of an eternal, fierce debate with Australia over its invention (it was definitely NZ).",
  "trigger_context": "When discussing Christmas, desserts, or the trans-Tasman rivalry.",
  "vibe_level": 3,
  "vector_text": "Pavlova. Meringue dessert. Christmas food. Trans-Tasman rivalry. Anna Pavlova. Kiwi dessert.",
  "metadata": { "tags": ["dessert", "rivalry", "christmas"], "era": "timeless" }
}
```

**Field roles:**
- `vector_text` — what gets embedded into the vector store (dense keyword format for better similarity matching). This becomes the `content` field in the DB.
- `definition` — stored separately; injected into the prompt at retrieval as `[NZ Context: term] definition`
- `trigger_context` — human documentation of intended use; not stored in DB
- `vibe_level` — controls retrieval sensitivity (see §3.3.3)
- `metadata` — stored as JSON string in `metadataJson`; not currently used in retrieval but available for future filtering (e.g. filter by tag or era)

**Categories in the corpus (138 entries total):**
| Category | Count | Examples |
|----------|-------|---------|
| `slang` | 35 | Sweet as, chur, yeah nah, hard out, munted, stoked, gutted |
| `history` | 15 | Kate Sheppard, Treaty of Waitangi, Ernest Rutherford, Gallipoli |
| `pop_culture` | 10 | Lucy Lawless, Karl Urban, Antony Starr, Lorde, Flight of the Conchords |
| `sports` | 9 | All Blacks, Black Caps, Silver Ferns, America's Cup |
| `meme` | 8 | Nek minnit, yeah nah, she'll be right, no. 8 wire, sweet as bro |
| `music` | 5 | Lorde, Crowded House, Flight of the Conchords |
| `maori` | 5 | Te reo basics, haka, tangi, whānau |
| `te_ao_maori` | 4 | Māori cosmology, tikanga, whakapapa |
| `food` | 6 | Pavlova, Flat White, Feijoa, Kiwifruit, Hokey Pokey, Marmite |
| `fauna` | 6 | Kiwi bird, Kererū, Tūī, Pīwakawaka |
| `daily_life` | 6 | Bach, dairy, section, togs, jandals, arvo |
| `politics` | 6 | MMP, Jacinda Ardern, Winston Peters, Treaty settlements |
| `culture` | 5 | No. 8 wire mentality, she'll be right, tall poppy syndrome |
| `other` | 12 | science, philosophy, social_code, attitude, linguistics, clothing, drink, safety, 2026_culture, 2026_tech, sports_history, social_structure, identity, joke |

---

## 8. Development Prerequisites

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

## 9. Build & Test

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

## 10. Roadmap Summary

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Core LiteRT-LM chat + GPU/NPU + GPU alignment fixes + OOM protection | ✅ Complete |
| 2 | sqlite-vec RAG + EmbeddingGemma + episodic distillation + memory UI | ✅ Complete |
| 3 | Resident Agent Architecture: two-gateway skills (run_intent + run_js), E4B tool calling, alarm/timer/torch/email/SMS, Wikipedia, weather GPS | 🔄 In Progress |
| 4 | Dreaming Engine (overnight distillation) + Semantic Cache + Self-Healing Identity | ⬜ Planned |
| 5 | Chicory Wasm Runtime + GitHub Skill Store | ⬜ Planned |
| 6 | 8GB device optimisation (dynamic weight loading, E2B auto-select) + wake word | ⬜ Planned |

See [`ROADMAP.md`](./ROADMAP.md) for full task-level detail.
