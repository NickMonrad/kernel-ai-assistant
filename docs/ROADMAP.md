# Kernel AI Assistant — Roadmap

> **Last updated:** 2026-04-14 (updated post-PR #268/#269/#270/#274)
>
> This is the living roadmap for Kernel AI. It tracks what's been built, what's next,
> and what's planned. If you have ideas, [open an issue](https://github.com/NickMonrad/kernel-ai-assistant/issues/new)
> and it'll get woven in here.

---

## Technical Stack

| Component | Technology |
|-----------|-----------|
| **Language** | Kotlin |
| **UI** | Jetpack Compose + Material 3 |
| **Min SDK** | API 35 (Android 15) |
| **DI** | Hilt |
| **Persistence** | Room + sqlite-vec (NDK) |
| **Inference** | LiteRT + LiteRT-LM |
| **Chat model** | Gemma-4 E-4B / E-2B |
| **Embeddings** | EmbeddingGemma-300M (SentencePiece + TFLite) |
| **Intent router (simple)** | `QuickIntentRouter` (Kotlin regex, zero memory, <5ms) |
| **Intent router (complex)** | Gemma-4 native JSON tool-call format |
| **Vector search** | sqlite-vec 0.1.9 via bundled SQLite 3.49.2 |
| **Wasm runtime** | Chicory (pure JVM) |
| **Test device** | Samsung Galaxy S23 Ultra (SD 8 Gen 2, 12GB RAM) |

---

## Phase 1: Core On-Device Chat ✅ Complete

Working chat app with Gemma-4 running entirely on-device.

| Task | Status | PR |
|------|--------|----|
| Project scaffold (9 Gradle modules) | ✅ Done | #3 |
| LiteRT-LM inference engine (NPU→GPU→CPU fallback) | ✅ Done | #5 |
| Model download manager (WorkManager, resume, progress) | ✅ Done | #6 |
| Chat UI (streaming tokens, thinking mode, conversations) | ✅ Done | #7, #8 |
| Hardware tier detection (Flagship/Mid/Compat) | ✅ Done | #8 |
| ADB device testing on S23 Ultra | ✅ Done | — |
| GPU alignment guard (`safeTokenCount`) — avoids LiteRT reshape::Eval bug at powers-of-2 token counts | ✅ Done | #221 |
| GPU kernel cache (serialized OpenCL kernels) | ✅ Done | #221 |
| Foreground service OOM protection during GPU init | ✅ Done | #221 |

---

## Phase 2: Local Semantic Memory (RAG) ✅ Complete

The assistant remembers facts across sessions using on-device vector search, with a
tri-tiered memory architecture inspired by the
[Memory Blueprint (#14)](https://github.com/NickMonrad/kernel-ai-assistant/issues/14).

### Memory Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Prompt Assembly                       │
│                                                         │
│  [System Prompt]                                        │
│  [User Profile]          ← always injected              │
│  [Active Sliding Window] ← Tier 1: current conversation │
│  [Current User Prompt]   ← prepended with RAG context:  │
│    [Core Memories]       ← Tier 3: retrieved by RAG     │
│    [Episodic Memories]   ← Tier 2: retrieved by RAG     │
└─────────────────────────────────────────────────────────┘
```

### Tasks

| Task | Status | PR | Notes |
|------|--------|----|-------|
| sqlite-vec NDK integration | ✅ Done | #11 | Bundled SQLite + sqlite-vec as `libkernelvec.so` |
| EmbeddingGemma-300M integration | ✅ Done | #12 | LiteRT Interpreter + SentencePiece tokenizer, SM8550 NPU model |
| RAG pipeline (basic) | ✅ Done | #13 | `RagRepository`: index + retrieve + inject context. Cross-conversation recall working! |
| User profile (Tier 3 foundation) | ✅ Done | #23 | Singleton profile entity, injected into every prompt, manually editable |
| Cancel generation fix | ✅ Done | #28 | Clears stuck spinner + resets LiteRT conversation state |
| Structured prompt assembly + context window | ✅ Done | #77, #79 | Layered prompt (system→profile→episodic→window→user). KV cache management with 75% proactive reset. |
| Runtime context in system prompt | ✅ Done | #81, #82 | `[Runtime]` block: model name, backend (GPU/NPU/CPU), device info. Timing fix for backend resolution. |
| Smart chat titles ([#15](https://github.com/NickMonrad/kernel-ai-assistant/issues/15)) | ✅ Done | #80, #83 | `generateOnce()` API with directive system prompt. `.lines().first()` cleanup, mutex leak fix. |
| Full markdown rendering ([#36](https://github.com/NickMonrad/kernel-ai-assistant/issues/36), [#26](https://github.com/NickMonrad/kernel-ai-assistant/issues/26)) | ✅ Done | #61, #63 | Custom Compose markdown renderer: headings, bold, italic, code blocks, links, lists. |
| UI polish pass ([#22](https://github.com/NickMonrad/kernel-ai-assistant/issues/22), [#25](https://github.com/NickMonrad/kernel-ai-assistant/issues/25), [#27](https://github.com/NickMonrad/kernel-ai-assistant/issues/27)) | ✅ Done | #62, #64, #68 | Back/home button, last message persistence, keyboard gap fix. |
| Model selection ([#18](https://github.com/NickMonrad/kernel-ai-assistant/issues/18)) | ✅ Done | #72, #76 | E-2B/E-4B chooser in Settings, DataStore persistence, snackbar confirmation. |
| Model persistence ([#20](https://github.com/NickMonrad/kernel-ai-assistant/issues/20)) | ✅ Done | #57 | Models stored in external shared storage, survive reinstalls. |
| Fun loading screens ([#13](https://github.com/NickMonrad/kernel-ai-assistant/issues/13)) | ✅ Done | #85 | 13 themed 3-step narratives (Kernel Kitchen, Techno-Wizard, Star Trek, etc.), animated transitions. |
| Episodic + Core memory tiers | ✅ Done | #101 | Split flat index into episodic (volatile) + core (permanent). Separate vec0 tables. Room DB v3→v4. |
| Memory management UI | ✅ Done | #102 | Core memories CRUD, episodic memory browser, stats. MemoryViewModel, MemoryScreen in Settings. |
| Bulk delete core memories ([#110](https://github.com/NickMonrad/kernel-ai-assistant/issues/110)) | ✅ Done | — | Multi-select + delete in the core memories list in Memory UI |
| Conversation search ([#151](https://github.com/NickMonrad/kernel-ai-assistant/issues/151)) | ✅ Done | #156 | Search bar on conversations list, filters by title with NULL guard and LIKE wildcard escaping. |
| RAG context framing | ✅ Done | #159 | Added framing instruction header, updated section labels to `[Core Memories — permanent facts about the user]` and `[Episodic Memories — recalled from a past conversation]`. |
| Core memory RAG-only ([#160](https://github.com/NickMonrad/kernel-ai-assistant/issues/160)) | ✅ Done | #162 | Removed core memories from system prompt. Core memories now retrieved only via RAG (CORE_MAX_DISTANCE=0.55, topK=10, sorted by similarity then lastAccessedAt). User Profile framing instruction added. |
| Fun loading screens v2 ([#96](https://github.com/NickMonrad/kernel-ai-assistant/issues/96)) | ✅ Done | — | Folded into Jandal visual identity (#226) |
| SM8550 Qualcomm AI Engine delegate ([#44](https://github.com/NickMonrad/kernel-ai-assistant/issues/44)) | ⬜ Pending | — | Bundle QNN TFLite delegate so SM8550-optimised EmbeddingGemma model uses Hexagon NPU |

### Key Design Decisions

- **EmbeddingGemma-300M** over Universal Sentence Encoder — much higher quality embeddings,
  hardware-optimised variants for Qualcomm chipsets
- **SentencePiece tokenizer** implemented in pure Kotlin (no protobuf library dependency)
- **Separate databases**: Room (`kernel_db`) for relational data, native SQLite (`kernel_vectors.db`)
  for vectors — because Room doesn't support sqlite-vec's vec0 virtual tables
- **Graceful degradation**: embedding engine returns empty arrays if models aren't loaded,
  RAG pipeline silently skips injection, chat works fine without memory

---

## Phase 3: Resident Agent Architecture + Native Skills 🔄 In Progress

> **Architecture pivot (Apr 2026):** FunctionGemma-270M has been deprecated from the
> startup sequence. Its 289MB footprint causes lmkd to terminate the process during
> Gemma-4's GPU kernel compilation peak (~4–5GB transient). After exhaustive testing (#219),
> the architecture was redesigned to a three-tier **Resident Agent** pattern (#220).

### Three-Tier Intent Architecture

```
User Input (voice/text)
    │
    ▼
┌─────────────────────────────┐
│  Tier 1: Wake Word          │  ← Always-on, ~0MB (future: Picovoice Porcupine)
│  "Hey Jandal"               │
└────────┬────────────────────┘
         │
         ▼
┌─────────────────────────────┐
│  Tier 2: QuickIntentRouter  │  ← Pure Kotlin regex, ~0MB, <5ms
│  Torch / Timer / DND /      │    Deterministic matching for ~8 simple actions
│  Bluetooth / WiFi / Time /  │    No ML model, no GPU, instantly available
│  Battery / Alarm            │
└────────┬────────────────────┘
         │ no match
         ▼
┌─────────────────────────────┐
│  Tier 3: Gemma-4 E-4B/E-2B │  ← Resident on GPU, TTFT ~2.3s
│  Native tool calling        │    Complex NLU: weather, calendar, email,
│  + RAG memory context       │    multi-step reasoning, conversation
└─────────────────────────────┘
```

### Brand & Polish

| Task | Status | Notes |
|------|--------|-------|
| Brand pass: Jandal AI ([#21](https://github.com/NickMonrad/kernel-ai-assistant/issues/21)) | ✅ Closed | Superseded by #225 (personality) and #226 (visual identity) |
| Fun loading screens v2 ([#96](https://github.com/NickMonrad/kernel-ai-assistant/issues/96)) | ✅ Done | Folded into Jandal visual identity (#226) |
| Jandal personality: Kiwi prompt, dynamic vocab, Kiwi truths ([#225](https://github.com/NickMonrad/kernel-ai-assistant/issues/225)) | ⬜ Pending | Bump FLAGSHIP token cap 4000→8000 first; time-of-day greetings, vocab injection |
| Jandal visual identity: Fern Green palette, Paua loading states ([#226](https://github.com/NickMonrad/kernel-ai-assistant/issues/226)) | ⬜ Pending | Pure UI — Dynamic Colour fallback, Paua shimmer, 🩴 throughout |
| Jandal cultural identity — knows his own NZ/Kiwi culture ([#264](https://github.com/NickMonrad/kernel-ai-assistant/issues/264)) | ✅ Done | PR #268 — Kiwi identity + cultural facts injected into `DEFAULT_SYSTEM_PROMPT` in `ModelConfig.kt` |
| Review UI patterns ([#71](https://github.com/NickMonrad/kernel-ai-assistant/issues/71)) | ⬜ Pending | Audit and refine UI patterns across the app |
| Copy chat content ([#78](https://github.com/NickMonrad/kernel-ai-assistant/issues/78)) | ✅ Done | — |
| Copy tool call content for debugging ([#260](https://github.com/NickMonrad/kernel-ai-assistant/issues/260)) | ✅ Done | PR #325/#326 — copy icon in expanded tool call chip; copies `[Tool: name]\nRequest\nResult` |
| Skill discoverability UI ([#261](https://github.com/NickMonrad/kernel-ai-assistant/issues/261)) | ⬜ Pending | Settings screen listing skills with enable/disable toggles + config navigation (run_intents non-disableable) |

### Resident Agent — Tier 2: QuickIntentRouter

| Task | Status | Notes |
|------|--------|-------|
| `QuickIntentRouter` — Kotlin regex matcher | ⬜ Pending | Zero memory, <5ms, deterministic. Replaces FunctionGemma for simple actions |
| Wire real OS actions in `KernelAIToolSet` | ⬜ Pending | `setTimer` → `AlarmClock.ACTION_SET_TIMER` + `EXTRA_SKIP_UI`; battery → `BatteryManager`; DND → `NotificationManager` |
| Refactor Actions tab to use `QuickIntentRouter` | ⬜ Pending | Remove `FunctionGemmaRouter` dependency from `ActionsViewModel` |
| Quick Actions tab UI (#221) | ✅ Done | History list, FAB (⚡), bottom sheet input, Room persistence |
| Bottom nav bar (Chats / Actions) | ✅ Done | PR #221 |

### Resident Agent — Tier 3: E4B Baseline Skills + Rich UI ([#222](https://github.com/NickMonrad/kernel-ai-assistant/issues/222))

| Task | Status | Notes |
|------|--------|-------|
| E4B native tool calling ([#84](https://github.com/NickMonrad/kernel-ai-assistant/issues/84)) | ✅ Done | `extractNativeToolCall()` + Gemma-4 token format; tool schemas + examples injected via `buildNativeDeclarations()` |
| Tool system prompt injection | ✅ Done | `SkillRegistry.buildNativeDeclarations()` injected into system prompt; concrete `<\|tool_call>` examples per skill |
| JS skill execution layer — WebView gateway ([#239](https://github.com/NickMonrad/kernel-ai-assistant/issues/239)) | ✅ Done | `JsSkillRunner` + hidden WebView; `run_js` gateway (#247/#251) |
| `get_system_info` skill | ✅ Done | Runtime device/model/backend info |
| `get_weather` JS skill — Open-Meteo + GPS | ✅ Done | Renamed to `get_weather_gps` (native, GPS + forecast) and `get-weather-city` (JS, named city + forecast). GPS routing fix + array bounds validation (#272, PR #274) |
| `query_wikipedia` JS skill | ✅ Done | Full article fetch via REST summary API (#257) |
| `save_memory` skill | ✅ Done | Persists to `MemoryRepository.addCoreMemory`; explicit trigger enforced in system prompt (#257) |
| `set_alarm` via `run_intent` | ✅ Done | `AlarmClock.ACTION_SET_ALARM`; package visibility fix (#262); hallucination rule (#263) |
| `set_timer` via `run_intent` | ✅ Done | `AlarmClock.ACTION_SET_TIMER` (#257/#262) |
| `toggle_flashlight_on/off` via `run_intent` | ✅ Done | `CameraManager.setTorchMode()` (#247) |
| `send_email` via `run_intent` | ✅ Done | `ACTION_SEND` mail chooser; recipient NOT pre-filled (exfiltration guard) (#247) |
| `send_sms` via `run_intent` | ✅ Done | `ACTION_SENDTO smsto:`; message pre-filled (#247) |
| **Rich tool result UI** ([#222](https://github.com/NickMonrad/kernel-ai-assistant/issues/222)) | ⬜ Pending | Replace 🔧 debug chip with weather card, confirmation chips, list cards per skill type |
| **`set_do_not_disturb`** | ⬜ Pending | `NotificationManager.setInterruptionFilter()` |
| **`create_calendar_event`** ([#265](https://github.com/NickMonrad/kernel-ai-assistant/issues/265)) | ✅ Done | PR #309 — `CalendarContract ACTION_INSERT` edit screen; `resolveDate` + `resolveTime` with natural language format support (PR #325) |
| **Maps & location intents** ([#258](https://github.com/NickMonrad/kernel-ai-assistant/issues/258)) | ⬜ Pending | `navigate_to` → `geo:` intent; `open_in_maps` → Maps app; JS skill for nearby search (Phase 5) |
| **`send_sms` / `send_email` recipient from contacts** ([#256](https://github.com/NickMonrad/kernel-ai-assistant/issues/256)) | ⬜ Pending | `READ_CONTACTS` + `ContactsContract` lookup before composing |
| **Weather forecast** ([#255](https://github.com/NickMonrad/kernel-ai-assistant/issues/255)) | ✅ Done | PR #269 (JS skill), PR #274 (native GPS skill). Both skills support `forecast_days` 1–7 with emoji + min/max temps |
| **`add_to_shopping_list`** | ⬜ Pending | Room-backed local list with list card UI |
| **WiFi / Bluetooth / Airplane / Hotspot toggles** | ⬜ Pending | Settings intent fallbacks where direct API removed |
| Gated model download handling ([#38](https://github.com/NickMonrad/kernel-ai-assistant/issues/38)) | ✅ Done | HuggingFace OAuth PendingIntent for Samsung |

### Memory & Distillation

| Task | Status | Notes |
|------|--------|-------|
| Episodic memory distillation ([#165](https://github.com/NickMonrad/kernel-ai-assistant/issues/165)) | ✅ Done | Gemma-4 distils conversations into episodic memories on close |
| Fix: message_embeddings_vec orphan cleanup ([#163](https://github.com/NickMonrad/kernel-ai-assistant/issues/163)) | ✅ Done | — |
| Message embedding stats in Memory screen ([#164](https://github.com/NickMonrad/kernel-ai-assistant/issues/164)) | ✅ Done | — |
| Bulk delete core memories ([#110](https://github.com/NickMonrad/kernel-ai-assistant/issues/110)) | ✅ Done | — |
| Memory tool gaps: save_memory triggers + search_memory skill ([#223](https://github.com/NickMonrad/kernel-ai-assistant/issues/223)) / ([#236](https://github.com/NickMonrad/kernel-ai-assistant/issues/236)) | ✅ Done | save_memory trigger enforced via hard rule in system prompt (PR #257); `search_memory` native skill with cross-conversation RAG search (PR #270) |
| search_memory quality — short entry filter ([#323](https://github.com/NickMonrad/kernel-ai-assistant/issues/323)) | ✅ Done | PR #326 — `MIN_EPISODIC_CONTENT_LENGTH = 20` filters short distillation hallucinations at save and search time |
| Prevent model using stale weather from memory ([#322](https://github.com/NickMonrad/kernel-ai-assistant/issues/322)) | ✅ Done | PR #326 — "ALWAYS call tool, never use memory" added to both weather tool descriptions |

### Voice Interface

| Task | Status | Notes |
|------|--------|-------|
| Voice input (push-to-talk) | ⬜ Pending | Android `SpeechRecognizer` + `TextToSpeech` |
| Live mode ([#64](https://github.com/NickMonrad/kernel-ai-assistant/issues/64)) | ⬜ Pending | Real-time streaming / continuous interaction mode |
| "Hey Jandal" wake word ([#65](https://github.com/NickMonrad/kernel-ai-assistant/issues/65)) | ⬜ Pending | Always-on local detection (Picovoice Porcupine) → `QuickIntentRouter` → E4B |

### Known Issues / Decisions

| Issue | Description |
|-------|-------------|
| [#218](https://github.com/NickMonrad/kernel-ai-assistant/issues/218) | ~~FunctionGemma limited scope~~ — **Closed** (FG deprecated, #220) |
| [#219](https://github.com/NickMonrad/kernel-ai-assistant/issues/219) | ~~FG + E4B OOM~~ — **Closed** (resolved by architecture pivot, #220) |
| [#220](https://github.com/NickMonrad/kernel-ai-assistant/issues/220) | Resident Agent Architecture — approved design, open for reference |
| [#230](https://github.com/NickMonrad/kernel-ai-assistant/issues/230) | `safeTokenCount()` uses 4000 not 4096 — workaround for LiteRT GPU reshape::Eval bug at powers-of-2. Already implemented; open for doc/comment clarity review |
| [#231](https://github.com/NickMonrad/kernel-ai-assistant/issues/231) | **NPU fallback rejects QTI devices** — `Build.SOC_MANUFACTURER = "QTI"` (many Snapdragon 8 Gen 2/3 devices) fails allowlist check, falls back to GPU instead of Hexagon NPU. Fix: partial-match or add "QTI" to allowlist. **High priority** |
| [#232](https://github.com/NickMonrad/kernel-ai-assistant/issues/232) | Chicory (pure-JVM Wasm) significantly slower than JIT engines — design constraint. Community skills must stay lightweight (API glue + data formatting only) to avoid 5s timeout. See Phase 5 |

---

## Phase 4: Dreaming Engine

Overnight WorkManager consolidation cycle + Semantic Cache + Self-Healing Identity System.

### The Dreaming Cycle

WorkManager runs three sequential phases while the device is charging and idle:

| Sleep Phase | What happens |
|-------------|-------------|
| Light Sleep | Prune `semantic_cache` entries not accessed in 7 days. Prune episodic memories older than 30 days. |
| REM Sleep | Batch distil recent conversations into episodic memories (replaces Phase 3's inline trigger). Extract candidate facts via Gemma-4 E-2B inference. |
| Deep Sleep | Route candidate facts: permanent identity → structured profile fields; specific/temporary → `core_memories_vec` via `vector_promotion`. Clear the profile sandbox. |

### Tasks

| Task | Status | Notes |
|------|--------|-------|
| Semantic Cache (#49) | ⬜ Pending | `semantic_cache` vec table; bypass Gemma-4 for repeated knowledge queries (≥0.95 cosine similarity); parallel FunctionGemma intent check + cache lookup; 7-day LRU pruning in Light Sleep |
| Self-Healing Identity System (#47) | ⬜ Pending | Replace free-text profile with structured fields (name, role, env, rules, sandbox). Gemma-4 promotes sandbox → core identity or core memories in Deep Sleep. |
| WorkManager chain scaffold | ⬜ Pending | Light Sleep → REM Sleep → Deep Sleep chained workers, runs on CHARGING + IDLE |
| Episodic distillation (overnight batch) | ⬜ Pending | Move Phase 3's inline distillation to REM Sleep batch |
| Profile maintenance LLM routing | ⬜ Pending | Gemma-4 E-2B classifies candidate memories: permanent facts → structured profile, specific → `vector_promotion` array |
| Failure handling + rollback | ⬜ Pending | Malformed JSON output → abort transaction, preserve existing profile, retry next cycle |

---

## Phase 5: WebAssembly Runtime + Skill Store

Community-extensible skills running in sandboxed Wasm via Chicory.

| Task | Status | Notes |
|------|--------|-------|
| Chicory Wasm integration | ⬜ Pending | Load `.wasm`, WASIp1 environment, host bridges |
| **Design constraint ([#232](https://github.com/NickMonrad/kernel-ai-assistant/issues/232)):** Chicory is a pure-JVM interpreter — significantly slower than JIT engines (Wasmtime/Wasmer). Community skills must stay lightweight (API glue + data formatting). Avoid complex computation (image processing, heavy parsing) to stay within 5s timeout. |  |  |
| Host bridge functions | ⬜ Pending | `host_log`, `host_get_input`, `host_set_output`, `host_http_get` |
| Resource limiting | ⬜ Pending | Wall-clock timeout, memory cap, output/network limits |
| GitHub skill store | ⬜ Pending | `NickMonrad/kernel-ai-skills` repo with manifest |
| Example Wasm skills | ⬜ Pending | Recipe parser, unit converter |
| Sideloading + permission audit | ⬜ Pending | Import from local storage or URL, risk dialog |
| Skill management UI | ⬜ Pending | Install/update/uninstall, per-skill permissions |
| Skill building & baseline skills ([#34](https://github.com/NickMonrad/kernel-ai-assistant/issues/34)) | ⬜ Pending | Home Assistant, common tasks inspired by Google Edge Gallery |
| Recipe skill + regional produce data ([#43](https://github.com/NickMonrad/kernel-ai-assistant/issues/43)) | ⬜ Pending | Recipe Wasm skill with regional/seasonal produce datasources |
| Multimodal capabilities ([#32](https://github.com/NickMonrad/kernel-ai-assistant/issues/32)) | ⬜ Pending | Image/camera input using a multimodal model variant |
| LiteRT-LM auto-update mechanism ([#31](https://github.com/NickMonrad/kernel-ai-assistant/issues/31)) | ⬜ Pending | App picks up new LiteRT-LM library releases automatically |

---

## Phase 6: 8GB Device Optimization

Dynamic weight loading/unloading so the app runs smoothly on 8GB RAM devices.

| Task | Status | Notes |
|------|--------|-------|
| Memory profiling | ⬜ Pending | Peak RAM states, concurrent model usage |
| Dynamic model loading state machine | ⬜ Pending | Never hold Gemma-4 + EmbeddingGemma simultaneously on low RAM |
| Embedding dimension reduction | ⬜ Pending | Matryoshka reduction to 256-dim on 8GB devices |
| Compatibility tier model swap | ⬜ Pending | Auto-select E-2B on 8GB, smaller KV cache |
| Battery optimization | ⬜ Pending | Defer background work in low battery |

---

## Ideas & Enhancements

Collected from [GitHub Issues](https://github.com/NickMonrad/kernel-ai-assistant/issues).
File new ideas there — they'll get reviewed and woven into the roadmap.

| Issue | Title | Phase | Status |
|-------|-------|-------|--------|
| [#13](https://github.com/NickMonrad/kernel-ai-assistant/issues/13) | Fun loading screens | Phase 2 | ✅ Done (#85) |
| [#14](https://github.com/NickMonrad/kernel-ai-assistant/issues/14) | Memory Blueprint (tri-tiered architecture) | Phase 2 (adopted) | ✅ Closed |
| [#15](https://github.com/NickMonrad/kernel-ai-assistant/issues/15) | Smart chat titles with override | Phase 2 | ✅ Done (#80, #83) |
| [#18](https://github.com/NickMonrad/kernel-ai-assistant/issues/18) | Model selection in Settings | Phase 2 | ✅ Done (#72) |
| [#20](https://github.com/NickMonrad/kernel-ai-assistant/issues/20) | Model persistence (survive reinstall) | Phase 2 | ✅ Done (#57) |
| [#21](https://github.com/NickMonrad/kernel-ai-assistant/issues/21) | Brand Strategy: Jandal AI | Phase 3 | ✅ Closed — superseded by #225 + #226 |
| [#22](https://github.com/NickMonrad/kernel-ai-assistant/issues/22) | Missing Home/back button | Phase 2 | ✅ Done (#62) |
| [#25](https://github.com/NickMonrad/kernel-ai-assistant/issues/25) | Last streaming message lost on nav away | Phase 2 | ✅ Done |
| [#26](https://github.com/NickMonrad/kernel-ai-assistant/issues/26) | URL/markdown links not clickable | Phase 2 | ✅ Done (#61) |
| [#27](https://github.com/NickMonrad/kernel-ai-assistant/issues/27) | Keyboard gap at bottom of chat | Phase 2 | ✅ Done (#68) |
| [#29](https://github.com/NickMonrad/kernel-ai-assistant/issues/29) | WASM Plugin/Skill Storefront | Phase 5 | ⬜ Pending |
| [#31](https://github.com/NickMonrad/kernel-ai-assistant/issues/31) | LiteRT-LM auto-update mechanism | Phase 5 | ⬜ Pending |
| [#32](https://github.com/NickMonrad/kernel-ai-assistant/issues/32) | Multimodal capabilities | Phase 5 | ⬜ Pending |
| [#34](https://github.com/NickMonrad/kernel-ai-assistant/issues/34) | Skill building & baseline skills | Phase 5 | ⬜ Pending |
| [#36](https://github.com/NickMonrad/kernel-ai-assistant/issues/36) | Markdown/code blocks not rendering | Phase 2 | ✅ Done (#63) |
| [#38](https://github.com/NickMonrad/kernel-ai-assistant/issues/38) | Handle gated model downloads | Phase 3 | ✅ Done |
| [#43](https://github.com/NickMonrad/kernel-ai-assistant/issues/43) | Recipe skill datasources & regional produce | Phase 5 | ⬜ Pending |
| [#44](https://github.com/NickMonrad/kernel-ai-assistant/issues/44) | SM8550 Qualcomm AI Engine delegate for EmbeddingGemma | Phase 6 | ⬜ Pending |
| [#46](https://github.com/NickMonrad/kernel-ai-assistant/issues/46) | Model Settings UI | Phase 3 | ✅ Done |
| [#47](https://github.com/NickMonrad/kernel-ai-assistant/issues/47) | Self-Healing Identity System | Phase 4 | ⬜ Pending |
| [#49](https://github.com/NickMonrad/kernel-ai-assistant/issues/49) | Semantic Caching via sqlite-vec | Phase 4 | ⬜ Pending |
| [#56](https://github.com/NickMonrad/kernel-ai-assistant/issues/56) | Download worker saves to wrong path | Phase 1 | ✅ Fixed (#57) |
| [#58](https://github.com/NickMonrad/kernel-ai-assistant/issues/58) | Engine init stuck (stale WorkManager) | Phase 1 | ✅ Fixed (#75) |
| [#59](https://github.com/NickMonrad/kernel-ai-assistant/issues/59) | Settings: show active model/backend/tier | Phase 2 | ✅ Done (#72) |
| [#60](https://github.com/NickMonrad/kernel-ai-assistant/issues/60) | Model selection: choose E2B/E4B | Phase 2 | ✅ Done (#72) |
| [#61](https://github.com/NickMonrad/kernel-ai-assistant/issues/61) | Full markdown rendering | Phase 2 | ✅ Done (#63) |
| [#64](https://github.com/NickMonrad/kernel-ai-assistant/issues/64) | Live mode | Phase 3 | ⬜ Pending |
| [#65](https://github.com/NickMonrad/kernel-ai-assistant/issues/65) | "Hey Jandal" wake word | Phase 3 Voice | ⬜ Pending |
| [#71](https://github.com/NickMonrad/kernel-ai-assistant/issues/71) | Review UI patterns | Phase 3 | ⬜ Pending |
| [#78](https://github.com/NickMonrad/kernel-ai-assistant/issues/78) | Copy chat content | Phase 3 | ✅ Done |
| [#84](https://github.com/NickMonrad/kernel-ai-assistant/issues/84) | Gemma 4 native tool calling | Phase 3 | ✅ Done |
| [#86](https://github.com/NickMonrad/kernel-ai-assistant/issues/86) | GetSystemInfo native skill | Phase 3 | ✅ Done |
| [#96](https://github.com/NickMonrad/kernel-ai-assistant/issues/96) | Fun loading screens v2 | Phase 3 (brand pass) | ✅ Done — folded into #226 |
| [#103](https://github.com/NickMonrad/kernel-ai-assistant/issues/103) | SaveMemory native skill | Phase 3 | ✅ Done |
| [#110](https://github.com/NickMonrad/kernel-ai-assistant/issues/110) | Bulk delete core memories | Phase 2 | ✅ Done |
| [#151](https://github.com/NickMonrad/kernel-ai-assistant/issues/151) | Conversation search by title | Phase 2 | ✅ Done (#156) |
| [#160](https://github.com/NickMonrad/kernel-ai-assistant/issues/160) | Core memory RAG-only + User Profile framing | Phase 2 | ✅ Done (#162) |
| [#163](https://github.com/NickMonrad/kernel-ai-assistant/issues/163) | message_embeddings_vec orphan cleanup on conversation delete | Phase 3 | ✅ Done |
| [#164](https://github.com/NickMonrad/kernel-ai-assistant/issues/164) | Message embedding stats in Memory screen | Phase 3 | ✅ Done |
| [#165](https://github.com/NickMonrad/kernel-ai-assistant/issues/165) | Episodic memory distillation on conversation close | Phase 3 | ✅ Done |
| [#229](https://github.com/NickMonrad/kernel-ai-assistant/issues/229) | Cannot copy tool call output | Phase 3 | ✅ Done — PR #325/#326 |
| [#236](https://github.com/NickMonrad/kernel-ai-assistant/issues/236) | Cross-conversation search_memory skill | Phase 3 | ✅ Done — PR #270 |
| [#239](https://github.com/NickMonrad/kernel-ai-assistant/issues/239) | JS skill execution layer (WebView gateway) | Phase 3 | ✅ Done — #247/#251 |
| [#240](https://github.com/NickMonrad/kernel-ai-assistant/issues/240) | Unit tests for extractToolCallJson / tryExecuteToolCall | Phase 3 | ✅ Done — PR #318 (`ToolCallExtractor` + `SkillExecutorTest`, 28 tests) |
| [#248](https://github.com/NickMonrad/kernel-ai-assistant/issues/248) | "Morena" used at wrong time of day | Phase 3 | ✅ Fixed — #252 |
| [#250](https://github.com/NickMonrad/kernel-ai-assistant/issues/250) | Wrong emoji on chat background | Phase 3 | ✅ Fixed — #252 |
| [#253](https://github.com/NickMonrad/kernel-ai-assistant/issues/253) | set_timer intent | Phase 3 | ✅ Done — #257/#262 |
| [#254](https://github.com/NickMonrad/kernel-ai-assistant/issues/254) | GPS weather location name (Nominatim) | Phase 3 | ✅ Done — #257 |
| [#255](https://github.com/NickMonrad/kernel-ai-assistant/issues/255) | Weather forecast for tomorrow / next N days | Phase 3 | ✅ Done — PR #269/#274 |
| [#256](https://github.com/NickMonrad/kernel-ai-assistant/issues/256) | SMS/email — pre-populate recipient from contact name | Phase 3 | ⬜ Pending |
| [#258](https://github.com/NickMonrad/kernel-ai-assistant/issues/258) | Maps & location skills (navigate, open, find nearby) | Phase 3/5 | ⬜ Pending — native intents Phase 3; JS nearby search Phase 5 |
| [#260](https://github.com/NickMonrad/kernel-ai-assistant/issues/260) | Copy tool call content for debugging | Phase 3 | ✅ Done — PR #325/#326 |
| [#261](https://github.com/NickMonrad/kernel-ai-assistant/issues/261) | Skill discoverability UI | Phase 3 | ⬜ Pending |
| [#264](https://github.com/NickMonrad/kernel-ai-assistant/issues/264) | Jandal doesn't know his own culture | Phase 3 | ✅ Done (PR #268) |
| [#265](https://github.com/NickMonrad/kernel-ai-assistant/issues/265) | Calendar event intent | Phase 3 | ✅ Done — PR #309 |
| [#230](https://github.com/NickMonrad/kernel-ai-assistant/issues/230) | Review `safeTokenCount()` token alignment logic | Phase 3 (technical debt) | ⬜ Open — workaround already in place, needs comment/doc clarity |
| [#231](https://github.com/NickMonrad/kernel-ai-assistant/issues/231) | NPU fallback rejects QTI Snapdragon devices | Phase 3 (device compat) | ⬜ Open — high priority bug, partial-match fix needed |
| [#232](https://github.com/NickMonrad/kernel-ai-assistant/issues/232) | Chicory WASM performance design constraint | Phase 5 | ⬜ Open — design note, guides skill authoring guidelines |
| [#272](https://github.com/NickMonrad/kernel-ai-assistant/issues/272) | GPS weather routing — model always used JS skill | Phase 3 | ✅ Done (PR #274) |
| [#301](https://github.com/NickMonrad/kernel-ai-assistant/issues/301) | isFirstReply derivation from actual conversation state | Phase 3 | ✅ Done — PR #310 |
| [#311](https://github.com/NickMonrad/kernel-ai-assistant/issues/311) | Home Assistant integration skill | Phase 3/5 | ⬜ Pending |
| [#312](https://github.com/NickMonrad/kernel-ai-assistant/issues/312) | Google Home integration skill | Phase 3/5 | ⬜ Pending |
| [#313](https://github.com/NickMonrad/kernel-ai-assistant/issues/313) | DuckDuckGo web search skill | Phase 3/5 | ⬜ Pending |
| [#314](https://github.com/NickMonrad/kernel-ai-assistant/issues/314) | Donetick task integration | Phase 3/5 | ⬜ Pending |
| [#315](https://github.com/NickMonrad/kernel-ai-assistant/issues/315) | Personal notes, reminders, shopping list — Room DB MVP | Phase 3 | ⬜ Pending |
| [#316](https://github.com/NickMonrad/kernel-ai-assistant/issues/316) | Plex media skill | Phase 3/5 | ⬜ Pending |
| [#317](https://github.com/NickMonrad/kernel-ai-assistant/issues/317) | Send calendar invites to contacts | Phase 3 | ⬜ Pending |
| [#319](https://github.com/NickMonrad/kernel-ai-assistant/issues/319) | resolveDate fails on natural date formats | Phase 3 (bug) | ✅ Done — PR #325 |
| [#320](https://github.com/NickMonrad/kernel-ai-assistant/issues/320) | resolveTime single-digit minute padding | Phase 3 (bug) | ✅ Done — PR #325 |
| [#321](https://github.com/NickMonrad/kernel-ai-assistant/issues/321) | Alarm AM/PM no-colon format (`10pm` → `01:00`) | Phase 3 (bug) | ✅ Done — PR #325 |
| [#322](https://github.com/NickMonrad/kernel-ai-assistant/issues/322) | Model uses stale weather from memory instead of tool | Phase 3 (bug) | ✅ Done — PR #326 |
| [#323](https://github.com/NickMonrad/kernel-ai-assistant/issues/323) | search_memory returns low-quality short fragments | Phase 3 (bug) | ✅ Done — PR #326 |
| [#324](https://github.com/NickMonrad/kernel-ai-assistant/issues/324) | Tomorrow alarm sets for today | Phase 3 (bug) | ⚠ Partial — PR #326 warning label; full fix tracked in #327 |
| [#327](https://github.com/NickMonrad/kernel-ai-assistant/issues/327) | Full date-specific alarm via `AlarmManager.setExact()` | Phase 3 | ⬜ Pending — needs permission, BroadcastReceiver, persistence |

---

## Key Resources

| Resource | URL |
|----------|-----|
| Gemma-4 E-2B (LiteRT) | [huggingface.co/litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) |
| Gemma-4 E-4B (LiteRT) | [huggingface.co/litert-community/gemma-4-E4B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) |
| EmbeddingGemma-300M | [huggingface.co/litert-community/embeddinggemma-300m](https://huggingface.co/litert-community/embeddinggemma-300m) |
| FunctionGemma-270M Mobile Actions | [huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions](https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions) |
| LiteRT-LM | [github.com/google-ai-edge/LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) |
| sqlite-vec | [github.com/asg017/sqlite-vec](https://github.com/asg017/sqlite-vec) |
| Chicory (Wasm) | [github.com/nickkmonrad/chicory](https://github.com/nickkmonrad/chicory) |
