# Technical Specification: Jandal AI — Local-First Android AI Assistant

> **Last updated:** 2026-05-12 (homescreen Glance widget #617/PR #847; TTS speech normalisation: aye pronunciation #843/PR #845)
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
:feature:widget       Glance homescreen widget, VoiceCommandActivity, WidgetTextInputActivity, WidgetNavigator
:feature:settings     Memory management, model settings, persona config
:feature:onboarding   First-launch model download flow
```

### 2.2 Model Inventory

| Model | Role | Size | Backend | Loading |
|-------|------|------|---------|---------|
| Gemma-4 E-4B | Reasoning, tool calling | ~3.4GB | GPU (OpenCL) | Eager at startup; stored in external shared storage to survive reinstalls (#20, PR #57) |
| Gemma-4 E-2B | Reasoning (8GB devices) | ~1.5GB | GPU (OpenCL) | Eager at startup; stored in external shared storage to survive reinstalls |
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
- **Cancel generation:** Tapping cancel clears the stuck spinner and resets the LiteRT
  conversation state (`#28`). Without this fix, a cancelled generation would leave the UI
  in a perpetual loading state with stale LiteRT conversation context.

### 3.2 Prompt Assembly Order

```
[System Prompt]          ← persona (FULL / HALF / BORING; MINIMAL for tool turns), date/time
[Runtime Context]        ← model name, backend (GPU/NPU/CPU), device info (`#81`, `#82`)
[User Profile]           ← structured YAML injection (name, role, environment, context, rules)
[Core Memories]          ← permanent facts split by category:
                            user (coreTopK=10), agent_identity (identityTopK=5)
                           CORE_MAX_DISTANCE=1.25; NZ truths further filtered by vibe level
[Episodic Memories]      ← distilled conversation summaries (EPISODIC_MAX_DISTANCE=1.10, episodicTopK=3)
[Message History]        ← semantically relevant messages from current conversation (MAX_DISTANCE=0.90, topK=5)
[Conversation Window]    ← selected recent turns (75% token budget)
[Current User Message]   ← with RAG context prepended; tool instructions prepended only for tool-like turns
```

**Identity tiers:** `IdentityTier.FULL` (Chat) includes greeting, vocab phrases, profile, and history.
`IdentityTier.MINIMAL` (Actions tab / tool-like turns) uses a slim prompt with no profile/history.

**Persona modes:** `PersonaMode.FULL`, `HALF`, and `BORING` are user-selectable from Model Management.
New installs default to **Half a Jandal**. Full mode keeps the strongest Kiwi flavour, Half mode keeps
the tone but reduces random slang and trims kiwi-memory injection, and Boring mode disables Kiwi vocab
and kiwi RAG entirely.

**Per-turn tool injection:** The `[Tool Use]` block is no longer baked into every chat system prompt.
`ChatViewModel` injects it only when the current turn is considered tool-like:

- `QuickIntentRouter.RouteResult.FallThrough` has a non-null `bestGuess`, or
- `looksLikeToolQuery(text)` matches explicit tool-oriented phrasing such as "search Wikipedia",
  "look up", "set alarm", "remember", or "add to my list"

This keeps ordinary chat lean while preserving tool guidance for explicit tool requests. When adding a
new skill whose user phrasing is not obviously tool-like, the corresponding router coverage or
`looksLikeToolQuery(...)` patterns should be updated so the skill remains reachable.

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

1. **Seeding triggered:** Check logcat for `JandalPersona` tag — should log `"Seeding X NZ truth memories"`. Triggered by absence of the current `truths_seeded_v29` SharedPrefs key.
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

**Actions-tab deterministic slot-fill loop:**

For quick actions, pending slot state lives inside `ActionsViewModel`, not a shared
`SlotFillerManager` singleton. `executeAction()` reacts to `QuickIntentRouter.RouteResult`
like this:

1. `RegexMatch` / `ClassifierMatch` with complete params → execute immediately.
2. `NeedsSlot` → call `primePendingSlot(...)` and show the missing-slot prompt.
3. `FallThrough` → hand off to chat.

When a slot reply arrives, `onSlotReply()` merges the reply into the accumulated params,
calls `quickIntentRouter.nextMissingSlot(intentName, mergedParams)`, and then either:

- prompts for the next missing required slot if one remains
- clears pending state and executes only when all required slots are present
- cancels on a blank reply via `cancelSlotFill()`

Current deterministic slot-fill coverage in this sprint is:

| Intent | Required slots |
|--------|----------------|
| `set_alarm` | `time` |
| `set_timer` | `duration_seconds` |
| `open_app` | `app_name` |
| `navigate_to` | `destination` |
| `find_nearby` | `query` |
| `send_sms` | `contact`, `message` |
| `send_email` | `contact`, `subject`, `body` |
| `add_to_list` | `item`, `list_name` |
| `make_call` | `contact` |
| `save_memory` | `content` |
| `create_list` | `list_name` |

`add_to_list` has no implicit default shopping-list fallback in this slot contract.
This sprint explicitly excludes weather/forecast, `create_calendar_event`,
`remove_from_list`, and zero-slot toggles/media controls from multi-turn slot
continuation. Weather/forecast stays single-turn because the existing weather path already
has safe defaults, including current-location behavior and optional forecast fields.

| Pattern | Action | OS API |
|---------|--------|--------|
| "turn on/off torch/flashlight" | Torch toggle | `CameraManager.setTorchMode()` |
| "set a timer for X minutes" | Timer | App-owned clock scheduler + notification pipeline |
| "set an alarm for X:XX" | Alarm | App-owned clock scheduler + notification pipeline |
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

**KernelAIToolSet gateway tools:**

| Gateway | Tool method | Dispatcher | Use for |
|---------|------------|-----------|---------|
| Meta | `loadSkill(skillName)` | `LoadSkillSkill` | Load full instructions before using any tool |
| Native | `runIntent(intentName, parameters)` | `NativeIntentHandler.kt` | Android OS intents and deterministic local actions (alarm, timer, DND, media, navigation, date diff, lists, etc.) |
| WebView JS | `runJs(parameters)` | `JsSkillRunner.kt` | JS skills in `assets/skills/` (currently Wikipedia and get-weather-city); parameters is a JSON string with `skill_name` and `data` |
| Native | `getWeather(location, forecastDays)` | `GetWeatherUnifiedSkill.kt` | Unified weather entry point (GPS + explicit city + indirect-location resolution) |
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

> This is the broader native-tool inventory. The current deterministic quick-action
> slot-fill sprint only locks `set_alarm`, `set_timer`, `open_app`, `navigate_to`,
> `find_nearby`, `send_sms`, `send_email`, `add_to_list`, `make_call`, `save_memory`,
> and `create_list`.

| `intent_name` | Action | OS API | Status |
|---------------|--------|--------|--------|
| `toggle_flashlight_on` | Torch on | `CameraManager.setTorchMode(true)` | ✅ |
| `toggle_flashlight_off` | Torch off | `CameraManager.setTorchMode(false)` | ✅ |
| `send_email` | Email composer | `ACTION_SEND` + `message/rfc822` | ✅ |
| `send_sms` | SMS composer | `ACTION_SENDTO` + `smsto:` | ✅ |
| `make_call` | Dialer / call handoff | `ACTION_DIAL` | ✅ |
| `set_alarm` | Set alarm | `ClockRepository.scheduleAlarm()` | ✅ |
| `set_timer` | Countdown timer | `ClockRepository.scheduleTimer()` | ✅ |
| `create_calendar_event` | Add calendar event | `ACTION_INSERT` + `CONTENT_URI` | ✅ |
| `toggle_dnd_on/off` | Do Not Disturb | `NotificationManager.setInterruptionFilter()` | ✅ |
| `toggle_wifi` / `toggle_bluetooth` | Connectivity toggles | Settings / adapter bridge | ✅ |
| `toggle_airplane_mode` / `toggle_hotspot` | Settings handoff | Settings intent / guarded flow | ✅ |
| `set_volume` | Media volume | `AudioManager` | ✅ |
| `play_media` family | Media playback / app-specific launches | Media session + explicit app intents | ✅ |
| `navigate_to` / `find_nearby` / `open_app` | Navigation / nearby / launcher intents | Explicit Android intents | ✅ |
| `get_battery` / `get_time` / `get_date` | Deterministic device info | Android APIs / `LocalDateTime` | ✅ |
| `get_date_diff` | Deterministic date arithmetic | `LocalDate` calculation | ✅ |
| `convert_units` | Deterministic unit conversion (length, mass, volume, temperature, speed) | `UnitConversionEvaluator` via `NativeIntentHandler` | ✅ |
| `add_to_list` / `bulk_add_to_list` / `create_list` / `get_list_items` / `remove_from_list` | Room-backed list management | `NativeIntentHandler` + Room DAOs | ✅ |
| `important_dates` | Taught dates + calendar birthday integration via Calendar Provider | `NativeIntentHandler` + `ContentResolver` query on `CalendarContract.Events` | ✅ — PR #797 |
| `world_clock` | Timezone lookup and world clock display | `ZoneId` / `ZonedDateTime` with timezone database | ✅ — PR #743 |


> **`convert_units` deterministic routing and reply contract:** `QuickIntentRouter` matches direct phrasing (`convert 5 miles to km`, `60 mph in m/s`), reversed phrasing (`how many cups in 2 L`), mixed-target phrasing (`convert 189 cm to feet and inches`), and spoken-STT variants (`convert 100 km an hour to metres a second`). The router normalises aliases before execution, including uppercase short forms like `L`/`mL`, natural spoken speed phrases (`km an hour`, `metres a second`), and mixed feet/inches input (`6 feet 2 inches` → total inches). `UnitConversionEvaluator` enforces same-category conversion only, rejects unsupported units and invalid physical values (for example below absolute zero), and marks non-terminating or mixed-unit results as approximate. Display text keeps full precision; spoken/TTS output uses `spokenSummary` rounded to 2 decimal places for verbose scalar results and 1 decimal place for the inches component of mixed feet/inches replies so speech stays concise without hiding exact on-screen values.

#### `convert_units` supported capabilities

**Supported conversion families and units**

| Family | Supported units |
|--------|------------------|
| Mass | `mg`, `g`, `kg`, `oz`, `lb` |
| Distance | `mm`, `cm`, `m`, `km`, `in`, `ft`, `yd`, `mi` |
| Volume | `mL`, `L`, `tsp`, `tbsp`, `fl oz`, `cup`, `pt`, `qt`, `gal` |
| Speed | `m/s`, `km/h`, `mph` |
| Temperature | `celsius`, `fahrenheit`, `kelvin` |

**Accepted phrasing patterns**

- Direct phrasing: `convert 5 miles to km`, `60 mph in m/s`, `100m to yards`
- Reversed phrasing: `how many cups are in 2 liters`, `how many cups in 2 L`, `how many yards in 350 m`
- Mixed target output: `convert 189 cm to feet and inches`
- Mixed source input: `convert 6 feet 2 inches to cm`
- Spoken/STT-friendly variants: `convert 100 km an hour to metres a second`

**Alias and normalization behavior**

- Metric spelling variants: `meter`/`metre`, `liter`/`litre`, `kilometer`/`kilometre`
- Short forms: `L`, `mL`, `km`, `cm`, `mm`, `kg`, `oz`, `lb`, `yd`, `mi`, `ft`, `in`
- Speed synonyms: `km/h`, `kmh`, `kph`, `m/s`, `mps`, `mph`
- Spoken speed phrases normalized before evaluation:
  - `km a hour`, `km an hour` → `kilometers per hour`
  - `meter(s) a second`, `meter(s) an second` → `meters per second`
  - `metre(s) a second`, `metre(s) an second` → `metres per second`
- Mixed feet/inches input is normalized to total inches before deterministic evaluation
- Mixed `feet and inches` output is produced by converting to inches first, then formatting a feet + inches breakdown for reply text

**Behavior and guardrails**

- Only same-category conversions are allowed; cross-category conversions fail clearly
- Unsupported units fail clearly instead of guessing
- Invalid physical values fail clearly, including temperatures below absolute zero
- Negative non-temperature values are rejected
- Display replies keep full precision
- Spoken/TTS replies are rounded for readability:
  - scalar results: 2 decimal places when a shorter spoken form differs from the displayed exact value
  - mixed feet/inches replies: inches rounded to 1 decimal place in speech
- Approximate wording is used for non-terminating decimal results and mixed-unit breakdown replies

**Explicit non-goals in this implementation**

- No ingredient-aware cooking density conversions
- No currency conversion or exchange-rate lookup
- No cross-category interpretation such as inferring mass from volume without material context

> **Alarm/timer ownership:** Core alarm and timer behavior now stays inside the app-owned
> clock backend. If exact alarms are unavailable, the action fails truthfully instead of
> bouncing the user into a system clock app that Jandal cannot track.

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
| `get-weather-city` | `assets/skills/get-weather-city/index.html` | ✅ (legacy city-weather path; unified `getWeather()` is preferred) |
| `query-wikipedia` | `assets/skills/query-wikipedia/index.html` | ✅ |

> **Colloquial + indirect weather routing (`#608`, `#663`, PR #667):** The QuickIntentRouter
> includes dedicated patterns for colloquial weather phrases (e.g. "how's the weather",
> "what's it like outside", "weather forecast") so these queries are routed directly to the
> weather skill instead of falling through to the LLM. Indirect-location resolution uses
> Nominatim geocoding to resolve city names and landmarks to coordinates when the user
> specifies a location by name rather than GPS.
>
> **Multi-day weather forecast card (PR #710, `#697`):** The weather skill returns a
> day-by-day forecast with WMO weather emoji (☀️🌤️⛅☁️🌧️❄️⛈️), min/max temps, and
> precipitation summaries. This renders as a rich card inline in the chat stream.
> Forecast is triggered by `forecast_days > 0` or `query_type = "forecast"` in the
> `get-weather-city` JS skill (legacy path) and via the unified `getWeather()` skill entry.
>
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

**Backing `Skill` implementations:**

| Skill name | Description | Status |
|------------|-------------|--------|
| `get_system_info` | Device/model/backend/battery stats | ✅ |
| `query_wikipedia` | Public Wikipedia lookup skill; loads focused instructions, then calls `run_js` with `skill_name="query-wikipedia"` | ✅ |
| `save_memory` | Persist a note/fact to `core_memories_vec` | ✅ (explicit trigger only — see memory rule below) |
| `search_memory` | Semantic search across core memories, episodic memories, and `message_embeddings` | ✅ |
| `get_weather_gps` / `get_weather` | Weather retrieval with GPS, explicit locations, and indirect-location resolution | ✅ |
| `important_dates` | Taught dates + calendar birthday integration via Calendar Provider | ✅ — PR #797 |
| `world_clock` | Timezone lookup and world clock display | ✅ — PR #743 |

> **save_memory trigger:** Works reliably when the user explicitly says "remember", "save",
> "don't forget", "can you remember", or "make a note of". Does not activate proactively from
> implicit personal facts shared in conversation — small model limitation. The trigger is
> enforced via the per-turn tool-instruction context assembled in `ChatViewModel`, not via the
> skill description alone.

> **search_memory:** Exposed to the model via `KernelAIToolSet.searchMemory()` and backed by
> `SearchMemorySkill`. It merges explicit memories (`MemoryRepository.searchMemories()`) with
> raw message-history retrieval (`RagRepository.searchMessages()`). Message-history lookups use
> sqlite-vec L2 distance on `message_embeddings` at `MAX_DISTANCE=0.90`, while core/episodic
> memory retrieval uses the wider calibrated thresholds in `MemoryRepositoryImpl`
> (`CORE_MAX_DISTANCE=1.25`, `EPISODIC_MAX_DISTANCE=1.10`). Results are returned as a numbered
> direct reply with dates and conversation prefixes, bypassing LLM rephrasing to preserve detail.

**Per-turn `[Tool Use]` rules (injected only for tool-like turns by `ChatViewModel`):**
- Injection gate: `QuickIntentRouter` fall-through with non-null `bestGuess`, or `looksLikeToolQuery(...)`
- Memory rule: user says "remember", "save", "don't forget", "can you remember", or "make a note of" → MUST call `save_memory`
- Alarm rule: user asks to set an alarm → MUST call `run_intent{intent_name: set_alarm}`
- Weather rule: `GetWeatherSkill` description includes "ALWAYS call this tool, never use memory" to prevent Gemma-4 from serving stale weather from episodic memory instead of fetching fresh data (#322)
- Wikipedia rule: `query_wikipedia` is the public skill surface for encyclopedia lookups; it loads focused instructions and then executes the internal `run_js` gateway with `skill_name="query-wikipedia"`

> **Adding new skills:** Registering a skill automatically makes it available in the dynamically
> generated tool list. If the skill serves requests that do not already look obviously tool-like,
> update `looksLikeToolQuery(...)` and/or `QuickIntentRouter` best-guess coverage so the per-turn
> `[Tool Use]` block is injected for those requests.

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
- **Voice:** Quick Actions push-to-talk with offline STT, spoken QIR responses, and streaming spoken chat replies; wake word remains future work

**Chat TTS speech normalisation (`ChatTextUtils.normalizeChatTextForSpeech`):** Before TTS
input, the text passes through a chain of `SpeechPronunciationRule` entries that rewrite
non-standard spellings into phonetic forms the TTS engine can pronounce correctly:

| Pattern | Replacement | Purpose |
|---------|-------------|---------|
| `\bKia ora\b` → `Keeorah` | Māori greeting pronunciation |
| `\bm(?:ō|o)rena\b` → `moh-reh-nah` | Māori greeting pronunciation |
| `(?<![a-zA-Z-])aye(?![a-zA-Z-])` → `A` | Standalone "aye" → letter A (#843, PR #845) |

The aye rule uses negative lookbehind/lookahead to avoid rewriting "aye" when it's part of
a hyphenated compound ("aye-aye") or a plural ("ayes"). Only standalone occurrences are
normalised to the spoken letter "A".
- **Skill results:** Inline rich cards in the conversation stream, with expandable list previews and link surfacing for fallback/plain-text results
- **Persona:** Friendly, concise, dry-humoured Kiwi — see §7 for full identity details

**Tool call debugging chip (`ToolCallChip`):** Tool calls appear as collapsible chips in the
chat stream. Tapping expands to show the full request JSON and result string. An icon button
copies `[Tool: name]\nRequest: <json>\nResult: <result>` to the clipboard via
`LocalClipboardManager` (Compose API — no system service boilerplate). Added in PR #325
closing #229 and #260.

**Smart chat titles (`#15`, PR #80/#83):** Conversations are auto-titled using the
`generateOnce()` API with a directive system prompt that instructs the model to produce
a concise, descriptive title from the conversation content. The title is captured via
`.lines().first()` cleanup with a mutex leak fix. Titles are editable by the user.

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

### 6.2 Voice Interface

The voice system covers two distinct interaction modes: **Quick Actions** (push-to-talk for
device commands) and **Chat Voice** (conversational push-to-talk for dialog). Both use
Sherpa-ONNX for speech-to-text (STT) and text-to-speech (TTS).

#### 6.2.1 Speech-to-Text (STT)

**Current stack:** Sherpa-ONNX CTC models for offline STT. The app also supports the Android
native on-device recognizer as a fallback path (`#717`, PR #718), which was hardened for
reliability on Samsung Galaxy S23 Ultra.

**STT fallback-path issues** (e.g. appointment QIR bug `#773`) are tracked separately from
the Sherpa primary path.

**Remaining STT research:** Sherpa-ONNX / Sherpa-ncnn STT + VAD evaluation (`#821`),
Parakeet CTC (`#700`), Whisper.cpp vs Vosk (`#703`).

#### 6.2.2 Text-to-Speech (TTS)

**Engine:** Sherpa-TTS with VITS-based voices, replacing the Android native TTS engine
(`#729`, PR #804). This provides significantly improved conversational voice quality.

**Multi-speaker support:**
- **VCTK** (`#782`, PR #805): Multiple English speakers for Quick Actions responses
- **Semaine** (`#817`, PR #818): 4 speakers — Prudence, Spike, Obadiah, Poppy — selected
  via Settings → Voice. Note: Semaine exposes different *speakers*, not emotional variants;
  the emotion detection approach was abandoned (`#781` closed).

**Streaming TTS pipeline (`#755`, PR #780):** Two-coroutine producer/consumer pattern
(`runStreamingPlayback()`) enables incremental, low-latency spoken responses during LLM
generation. The TTS begins playback as soon as the first sentence is available, rather
than waiting for the full response.

**TTS quality fixes (PR #780):**
- URL colon preservation in `cleanTextForSpeech()` — prevents URLs like
  `https://example.com:8080` from being read as "eight zero eight zero"
- Speech rate clamping — prevents unnaturally fast or slow playback
- Abbreviation-aware sentence splitting — handles "Dr.", "Mr.", "U.S." correctly
- Sherpa voice quality evaluation performed on Samsung Galaxy S23 Ultra (`#770`)

**TTS settings (PR #789):** Expanded settings include pitch control, auto-speak toggle,
and max spoken sentences limit. `autoSpeakEnabled` is a cached field in `ChatViewModel`,
fully decoupled from the Quick Actions `spokenResponsesEnabled` toggle.

**Per-message speaker button (PR #789):** Each chat message has a speaker icon to replay
its TTS playback with the selected speaker.

**Verbal stop command (PR #789):** Users can say "stop" or "quiet" to halt TTS playback
mid-sentence.

**TTS pronoun normalisation (`#828`):** Pending — will convert first-person pronouns
(my/I → your/you) in TTS output so the assistant speaks in third person when reading
LLM responses that reference the user.

#### 6.2.3 Quick Actions Voice

**Push-to-talk:** Offline STT captures voice input; QIR (QuickIntentRouter) matches the
transcript to device actions; responses are spoken via TTS.

**Slot-fill retry on no-speech (PR #825, `#790`):** When the STT detects no speech,
the system retries the slot-fill prompt instead of failing. Cancel phrases (e.g. "cancel",
"never mind") are recognised and abort the slot-fill flow.

**Start-listening audio cue (PR #825, `#791`):** A brief audio beep signals that voice
capture has started, giving the user confidence the mic is active.

#### 6.2.4 Chat Voice

**Conversational push-to-talk (`#727`, PR #731; `#728`, PR #735):** Chat voice supports
turn-taking controls — the user speaks, the assistant responds with streaming TTS, then
the user can speak again.

**Mode switch (`#741`, PR #744):** Users can choose between one-shot mode (single question
and answer) and back-and-forth mode (continuous conversation) from the chat voice controls.

**Voice-friendly spoken response rendering (`#763`, PR #771):** LLM responses are
preprocessed for natural speech: markdown is stripped, lists are verbalised, and
punctuation is normalised for TTS.

#### 6.2.5 Remaining Voice Research

- **Kokoro-82M / VoxSherpa + expressiveness (`#783`):** Research into alternative TTS
  engines with emotional expressiveness and tone control
- **Kiwi language corpus tuning (`#784`):** Tuning TTS pronunciation for Māori words
  and Kiwi slang used by Jandal
- **VITS noise_scale expressiveness (`#788`):** Fine-tuning the noise scale parameter
  for more natural-sounding VITS voices
- **Custom Piper voice training (`#756`):** Research into training a custom Piper voice
  model with Jandal's Kiwi character
- **Voice memo skill (`#823`):** Native skill for voice note-taking
- **VoiceSession architecture (`#588`):** Unified voice session management for slot-fill
  and follow-on assistant mode
- **QA gate (`#824`):** Real-device voice validation for the current stack on Samsung
  Galaxy S23 Ultra
- **Homescreen widget (`#617`):** Quick actions / voice widget for the homescreen
- **Translator skill (`#659`):** Multilingual translation with TTS output
- **Wake word (`#65`):** "Hey Jandal" via Picovoice Porcupine (future)
- **Live mode (`#64`):** Real-time streaming interaction (future)

### 6.3 Homescreen Glance Widget (issue #617, PR #847)

A Glance-based (`androidx.glance`) homescreen widget that surfaces voice and text Quick Actions
directly from the Android homescreen, without requiring the user to open the app.

**Module:** `:feature:widget`

#### Widget UI (`KernelWidget` / `KernelWidgetReceiver`)

The widget is a `GlanceAppWidget` that renders a single-row card via `provideContent()`:

|| Element | Behaviour |
||---------|-----------|
|| Text pill ("Ask Jandal…") | Fills available width. Tapping opens `WidgetTextInputActivity`. Background uses `DayNightColorProvider` for automatic light/dark theming. |
|| Mic button (48dp circle, M3 primary colour) | Tapping opens `VoiceCommandActivity`. Uses `DayNightColorProvider` (purple day / light-purple night). |

`KernelWidgetReceiver` registers the widget with the metadata declared in
`@xml/kernel_widget_info`.

#### Voice flow

```
Mic button tap
  → VoiceCommandActivity (translucent overlay, taskAffinity="", excludeFromRecents, noHistory)
        Plays a brief 200ms boop (ToneGenerator) to signal listening started
        Displays pulsing mic icon + live partial transcript via VoiceInputController
        Tap outside card or ✕ → cancel and finish()
        On VoiceInputEvent.Transcript (final STT result):
  → WidgetNavigator.navigateToActions(context, transcript, isVoice=true)
        Fires explicit Intent to MainActivity:
          extra "quick_action_input"    = <transcript>
          extra "quick_action_is_voice" = true
  → MainActivity.onNewIntent → KernelNavHost LaunchedEffect(initialQuickActionQuery)
        navController.navigate("actions?widgetQuery=<encoded>&widgetVoice=true")
  → ActionsScreen(initialQuery=<transcript>, initialQueryIsVoice=true)
        viewModel.executeAction(transcript, InputMode.Voice)
        Shows result card + speaks TTS reply
```

#### Text flow

```
Text pill tap
  → WidgetTextInputActivity (translucent overlay, taskAffinity="", excludeFromRecents, noHistory)
        Keyboard appears immediately (FocusRequester + KeyboardController.show())
        User types; IME Send or keyboard action triggers submit()
  → WidgetNavigator.navigateToActions(context, text, isVoice=false)
        Fires explicit Intent to MainActivity:
          extra "quick_action_input"    = <text>
          extra "quick_action_is_voice" = false
  → same nav arg path as voice, with widgetVoice=false
  → ActionsScreen(initialQuery=<text>, initialQueryIsVoice=false)
        viewModel.executeAction(text, InputMode.Text)
        Shows result card only (no TTS)
```

#### Routing

All widget queries target the **Actions screen** (`ROUTE_ACTIONS`), never Chat.
`QuickIntentRouter` is **not** called from widget activities — `ActionsViewModel.executeAction()`
handles all routing internally (Tier 2 regex → Tier 3 Gemma-4 E4B), producing a result card
with an optional TTS reply.

#### Consume-once guard

`widgetQuery` is baked into the route URL as a nav argument. A `savedStateHandle` boolean
(`widgetQueryConsumed`, constant `STATE_WIDGET_QUERY_CONSUMED`) prevents re-execution on
recompose or after process-death restore:

```kotlin
val widgetQuery = backStackEntry.arguments?.getString(ARG_WIDGET_QUERY)
    ?.takeIf { it.isNotBlank() }
    ?.takeIf { backStackEntry.savedStateHandle.get<Boolean>(STATE_WIDGET_QUERY_CONSUMED) != true }
```

`ActionsScreen.onInitialQueryConsumed` sets this flag in `savedStateHandle` immediately after
`executeAction()` is called.

#### Task isolation

Both widget activities declare `android:taskAffinity=""` and `android:excludeFromRecents="true"`.
This places them in their own task, isolated from `MainActivity`'s task. Tapping a widget button
always opens the Actions screen — it never resurfaces whatever screen `MainActivity` was last
showing. `android:noHistory="true"` ensures the overlay activities are not retained in the
back stack.

#### Manifest entries

```xml
<!-- Widget receiver -->
<receiver android:name="com.kernel.ai.feature.widget.KernelWidgetReceiver"
          android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE"/>
    </intent-filter>
    <meta-data android:name="android.appwidget.provider"
               android:resource="@xml/kernel_widget_info"/>
</receiver>

<!-- VoiceCommandService — foundational seam for widget + future wake word -->
<service android:name="com.kernel.ai.feature.widget.VoiceCommandService"
         android:exported="false"/>

<!-- Widget overlay activities (taskAffinity="" for task isolation) -->
<activity android:name="com.kernel.ai.feature.widget.VoiceCommandActivity"
          android:theme="@style/Theme.Kernel.Translucent"
          android:noHistory="true"
          android:taskAffinity=""
          android:excludeFromRecents="true"/>
<activity android:name=".feature.widget.WidgetTextInputActivity"
          android:theme="@style/Theme.Kernel.Translucent"
          android:noHistory="true"
          android:taskAffinity=""
          android:excludeFromRecents="true"/>
```

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

**Seed guard:** The key `truths_seeded_v29` in SharedPreferences (`jandal_persona` prefs file) prevents repeated seeding. If this key is absent (new install or key was bumped), all 138 entries are seeded. **Convention: bump the version suffix every time the corpus changes materially** (e.g. `truths_seeded_v28` → `truths_seeded_v29`). This forces a full re-seed on existing installs the next time the app launches, without requiring a DB wipe. The current key is `truths_seeded_v29`.

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

### 7.3 Verbose Logging Convention

**Verbose logging** is enabled via the "Verbose Logging" toggle in the About screen (Settings → About). This is stored in the app's DataStore preferences (`verbose_logging` key in the "about" DataStore).

**Design decision:** All verbose logging throughout the codebase must respect this centralized toggle rather than using Android's system-level `Log.isLoggable()` or `adb shell setprop log.tag.X V` approach. This ensures:
1. Users can enable/disable verbose logs directly in the app without `adb`
2. Verbose logs are included in "Export Logs" function (which captures logcat output)
3. Consistent logging UX across all debug instrumentation

**Pattern for adding verbose logging:**
1. Inject the appropriate repository or service that needs verbose logging (e.g., `RagRepository`)
2. Add a `setVerboseLogging(enabled: Boolean)` method to the component
3. Store the boolean locally: `private var verboseLoggingEnabled = false`
4. In the logging method, check the local flag: `if (verboseLoggingEnabled) { Log.v(TAG, msg) }`
5. In `ChatViewModel` (or similar high-level VM that has datastore access), observe the `verbose_logging` preference and call `setVerboseLogging()` on init

**Example (RagRepository):**
```kotlin
private var verboseLoggingEnabled = false

fun setVerboseLogging(enabled: Boolean) {
    verboseLoggingEnabled = enabled
}

private fun logVerbose(msg: String) {
    if (verboseLoggingEnabled) {
        Log.v(TAG, msg)
    }
}
```

Then in ChatViewModel init:
```kotlin
viewModelScope.launch {
    dataStore.data
        .map { prefs -> prefs[KEY_VERBOSE_LOGGING] ?: false }
        .collect { enabled ->
            ragRepository.setVerboseLogging(enabled)
        }
}
```

---

## 8. Development Prerequisites

| Requirement | Detail |
|-------------|--------|
| Machine RAM | 32GB minimum (LiteRT builds + Android Emulator) |
| Android Studio | Ladybug (2024.2.1) or newer |
| Android NDK | Required for sqlite-vec JNI bridge |
| JDK | 17 |
| Min SDK | API 35 (Android 15) |
| Target SDK | 36 |
| Test device | Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2, 12GB RAM, Android 16) |

**Backend note:** Current dev/test guidance assumes the Samsung Galaxy S23 Ultra uses the
Hexagon NPU path when the required delegate and models are present, with GPU fallback still
supported for first-run delegate warm-up or unsupported devices.

---

## 8.1 Cross-Module Wiring Checklist

When adding dependencies or features that span multiple modules (especially feature modules):

1. **Add dependency to build.gradle.kts** of the consuming module
   - Example: Adding DataStore to feature:chat for settings wiring requires `implementation(libs.datastore.preferences)`

2. **Add all required imports** to the consuming Kotlin file
   - If using flow operations (`.map()`, `.collect()`, etc.), explicitly import from `kotlinx.coroutines.flow`
   - Do not rely on IDE auto-import — verify imports in source before pushing

3. **Compile locally before pushing**
   - Run `./gradlew :module:compileDebugKotlin` for the specific module
   - Build full debug variant if modifying shared types: `./gradlew assembleDebug`
   - Do NOT push without a successful local build — CI is for validation, not discovery

**Rationale:** Cross-module wiring errors (missing deps, unresolved references) are 100% preventable with a local compile. Catching them in CI wastes ~2–3 min per attempt and blocks iteration. Always build locally first.

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
python3 scripts/adb_skill_test.py    # Device routing/profile regression harness
```

**CI:** Runs lint + unit tests + debug build. No real model inference in CI — `InferenceEngine`
is behind an interface and mocked in all tests. Models (~3GB) are never downloaded in CI.

See [`docs/automated-testing.md`](./automated-testing.md) for the current automation overview,
[`docs/adb-testing.md`](./adb-testing.md) for device bring-up/logcat workflows, and
[`docs/testing/automated-test-specification.md`](./testing/automated-test-specification.md)
for the larger planned coverage matrix.

---

## 10. Roadmap Summary

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Core LiteRT-LM chat + GPU/NPU + GPU alignment fixes + OOM protection | ✅ Complete |
| 2 | sqlite-vec RAG + EmbeddingGemma + episodic distillation + memory UI | ✅ Complete |
| 3 | Resident Agent Architecture: QIR + native SDK tool calling, rich tool results, voice (Sherpa STT/TTS, streaming, multi-speaker, chat voice), weather/list/date/media skills, important dates, world clock, multi-day forecast, colloquial weather routing, multi-turn slot-fill, memory search quality, and broader multi-turn support | 🔄 In Progress |
| 4 | Dreaming Engine (overnight distillation) + Semantic Cache + Self-Healing Identity | ⬜ Planned |
| 5 | Chicory Wasm Runtime + GitHub Skill Store | ⬜ Planned |
| 6 | 8GB device optimisation (dynamic weight loading, E2B auto-select) | ⬜ Planned |

See [`ROADMAP.md`](./ROADMAP.md) for full task-level detail.
