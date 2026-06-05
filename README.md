# Jandal AI

*Play Store release — coming soon. Pre-release builds on [GitHub Releases](https://github.com/NickMonrad/kernel-ai-assistant/releases).*
## How It Works

The app operates on a **Brain–Memory–Action** triad using a three-tier Resident Agent Architecture:

* **The Brain:** **Gemma-4 E-4B/E-2B** runs resident on GPU via LiteRT. A lightweight **`QuickIntentRouter`** (regex + MiniLM fallback) handles instant device actions and slot-filling fast paths. Complex queries go straight to Gemma-4 for full reasoning with native tool calling.
* **The Memory:** A local **RAG (Retrieval-Augmented Generation)** system using **sqlite-vec** and **EmbeddingGemma-300M**. The assistant remembers personal facts, preferences, and conversation history across sessions with zero data leaving the device. Episodic distillation consolidates each conversation into long-term memories.
* **The Action:** A modular skill framework. **Tier 2** native Kotlin actions execute instantly (torch, timer, DND, bluetooth, lists, date arithmetic). **Tier 3** complex skills (weather, calendar, memory recall, Wikipedia) are handled by the resident Gemma-4 model via LiteRT-LM's native `@Tool` annotations with SDK constrained decoding and rich inline result cards. Tool instructions are injected **per turn** only when a request looks tool-oriented, which keeps normal chat prompts slim as the skill set grows. Community-extensible **WebAssembly** skills run sandboxed via **Chicory** for safe extensibility.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 Dynamic Color |
| Inference | Google AI Edge (LiteRT + LiteRT-LM) |
| Reasoning | Gemma-4 E-4B / E-2B (INT4 quantized, GPU resident) |
| Quick Actions | `QuickIntentRouter` (Kotlin regex, zero memory) |
| Complex Tool Calling | LiteRT-LM native `@Tool` annotations + constrained decoding |
| Embeddings | EmbeddingGemma-300M (768-dim) |
| Vector Search | sqlite-vec (NDK) |
| Wasm Runtime | Chicory (pure JVM) |
| DI | Hilt |
| Persistence | Room |
| Min SDK | API 35 (Android 15) |
| Speech-to-Text | Vosk (default), Android SpeechRecognizer (optional) |
| Text-to-Speech | Android TTS (default), Sherpa Piper/Kokoro (optional) |

## Features

### Delivered
- 🧠 **On-device reasoning** — Gemma-4 E-4B running on GPU via LiteRT, no internet required
- 💾 **Persistent memory** — RAG-powered recall across conversations using sqlite-vec semantic search
- 🔒 **100% private** — No cloud APIs, no telemetry, all data stays on device
- 💬 **Full markdown rendering** — headings, bold, italic, inline code, code blocks, tables, links, lists
- 🎯 **Smart chat titles** — auto-generated from conversation content
- 🗂️ **Multi-conversation** — create, delete, rename, and search conversations by title
- 📁 **Conversation management** — archive, pin, drag-to-reorder conversations; swipe-to-archive (left) or delete (right) with confirmation; multi-select with bulk archive/restore/delete; auto-delete archived conversations after configurable retention period (default 7 days)
- 🧠 **Core memories** — add and manage permanent facts the assistant always recalls
- 📂 **Memory Management screen** — view, add and delete core memories and episodic memory browser
- ⚙️ **Model selection** — switch between E-2B and E-4B in Settings
- 🩴 **Persona modes** — choose Full Jandal, Half a Jandal (default), or Boring AI Mode in Model Management
- 🎬 **Fun loading screens** — 13 themed animated narratives
- 🖼️ **Context window management** — structured prompt assembly with KV cache management and recursive summarisation
- 📊 **Runtime info** — shows active model, backend (GPU/NPU/CPU), and device tier in chat
- ⚡ **Quick Actions tab** — instant device commands (torch, timer, DND, bluetooth) via zero-overhead Kotlin pattern matcher
- 💭 **Episodic memory distillation** — Gemma-4 summarises each conversation into long-term memories
- 🔧 **Native skills** — alarms, timers, SMS, email, torch, calendar events, weather (GPS + city), navigation, media playback, Wikipedia
- 📆 **Native date arithmetic** — deterministic `get_date_diff` handling for "days until/since" queries
- 🔍 **search_memory** — semantic search across core + episodic memories on demand
- 🌦️ **Weather follow-up resolution** — colloquial weather phrasing plus indirect references like "there" and "the capital of New Zealand"
- 🧩 **Quick intent slot filling** — supported fast-path intents can pause for missing required parameters instead of failing silently
- 🪪 **Rich tool presentations** — weather cards, list cards, confirmation chips, expandable previews, and surfaced fallback links
- 🔎 **Tool call debugging** — expand any tool call chip to see request/result, tap to copy
- 🧭 **Nav drawer** — Lists, Alarms, and Meal plans accessible from Chat, Actions, and all main screens via hamburger menu
- 📋 **Lists** — create and manage named lists via chat ("add milk to shopping list") or the Lists UI; full CRUD with active/completed sections
- 🗒️ **Notes** — create, edit, and manage free-form notes via chat or the Notes UI; smart auto-titles, pin/unpin, archive/unarchive, search, sort, drag-to-reorder, multi-select bulk archive/delete/restore/pin/share, and archived-only view
- 🗓️ **Scheduled Alarms** — date-specific alarms scheduled via Jandal appear in the Alarms screen for review and cancellation
- 📟 **Side panel** — slide-out drawer accessible from Chat and Settings shows active alarms and timers with live countdown; cancel any from the panel
- 🎵 **Media controls** — pause, stop, skip, and previous track via Jandal ("skip song", "pause music")
- 🎙️ **Podcast playback** — open a podcast app and start/resume playback ("play my podcasts", "resume podcast")
- ⏱️ **Timer management** — list active timers and cancel individual ones ("cancel my 10 minute timer")
- 🗑️ **Alarm multiselect delete** — select multiple alarms in the Alarms screen and delete them at once
- 📝 **Bulk list add** — add multiple items to a list in one request ("save all ingredients to shopping list")
- 🗣️ **Offline voice controls** — push-to-talk Quick Actions plus spoken responses managed from **Settings → Voice**
- 🔊 **Streaming spoken chat replies** — chat TTS begins playback before generation completes; preprocessing layer handles URL colon preservation, speech rate clamping, and abbreviation-aware sentence splitting (`KNOWN_ABBREV` + `INITIALS_REGEX`) so "Dr.", "Mr.", "e.g." don't break sentences; Sherpa voice quality evaluated and tuned on device
- 🔊 **Per-message speaker button** — `VolumeUp` icon on every assistant bubble; tap to play or stop that message's TTS independently of voice mode
- ⚙️ **Expanded TTS settings** — pitch slider (Sherpa only, 0.5–2.0×), auto-speak chat replies toggle (decoupled from Quick Actions via `autoSpeakEnabled` field), max spoken sentences dropdown (0 = unlimited, 2, 3, 5); all grouped in a **"Chat voice behaviour"** section in Settings
- 🛑 **Verbal stop command** — saying "stop", "stop speaking", "cancel", "be quiet", "shut up", or "silence" during TTS playback cancels speech and stops mic re-arm
- 🗣️ **VCTK multi-speaker selection** — choose from 109 VCTK voices (gender filter, speaker ID, accent label) in Settings → Voice; sid mapping sourced directly from the Piper model config
- 🗣️ **Semaine multi-speaker selection** — 4 distinct voices (Prudence, Spike, Obadiah, Poppy) selectable in Settings → Voice (#818, PR #818)
- 📐 **Deterministic unit conversion** — length, mass, volume, temperature, speed with alias normalisation and spoken-STT variants (#676, PR #816)
- 💱 **Deterministic currency conversion** — ISO code resolution via Frankfurter/ECB rates with same-currency short-circuit and clear error for unsupported currencies (#831, PR #848)
- 🗣️ **TTS pronoun normalisation** — converts first-person pronouns (my/I → your/you) in spoken summaries so the assistant speaks in third person (#828, PR #830)
- 🔊 **Voice fallthrough preservation** — Actions→Chat fallthrough preserves the user's voice-speak expectation so replies are spoken even after cross-screen navigation (#832, PR #833)
- ⏱️ **Slot-fill retry on no-speech** — system retries the slot-fill prompt instead of failing; cancel phrases abort the flow; start-listening audio cue confirms mic activity (#790/#791, PR #825)
- 🔒 **Blank response guard** — retries without RAG before showing fallback when LiteRT produces 0 tokens; keeps chat awake during load and generation (#839/#841, PRs #840/#842)
- 🎙️ **Homescreen Glance widget** — quick actions and voice from the launcher via GlanceAppWidget; VoiceCommandActivity and WidgetTextInputActivity with task isolation (#617, PR #847)
- 🔧 **Audio quality fixes** — AudioTrack tail cutoff prevention via hardware-latency silence padding; expectedSlotPromptSpeech normalisation to match TTS output; SID=0 clamp for single-speaker voices; aye pronunciation correction (#837/#828/#810, PRs #838/#836/#811)
- 🍽️ **Deterministic meal planner** — app-owned meal-planning sessions with bounded JSON generation, draft-plan approval, progressive recipe reveal, visible `x of y` progress, interruption-safe resume, quantity sanity validation, cuisine preferences, Kiwi wording normalization, batch day replace/regenerate, and quick-action/chat handoff (#859/#869/#931/#932/#971, PRs #864/#875/this PR)
- 📚 **Meal plans browser** — drawer-accessible Recent plans and Favourites tabs with recipe search, canonical favourite toggles, recipe re-add to Lists, and ingredient export into existing user lists (#933, PR #934)
- 💬 **Multi-turn dialog** — expanded confirmation, digression, and slot-filling coverage across more intents (#708, PR #712)
- ⏰ **Alarms CRUD UI** — create, edit, and toggle alarms directly from the Alarms screen; full CRUD via nav drawer (#479, PR #484)
- 🗒️ **Lists management upgrades** — rename, pin, sort, edit items, favorites, and due dates (#662)

### Coming Soon
- 🗒️ **Lists — hierarchical items** — nested sub-items within lists *(#928)*
- 🌙 **Dreaming Engine** — overnight WorkManager consolidation (Light Sleep → REM → Deep Sleep) *(Phase 4)*
- ⚡ **Semantic cache** — instant responses for repeated knowledge queries *(Phase 4)*
- 🪪 **Self-healing identity** — structured user profile, LLM-managed via Dreaming cycle *(Phase 4)*
- 🧩 **Wasm skill store** — community-extensible plugins with sandboxed execution *(Phase 5)*
- 🏠 **Home Assistant / Google Home** — smart home control *(Phase 5)*
- 📱 **8GB device optimisation** — dynamic weight loading/unloading, E2B fallback *(Phase 6)*
- 🎙️ **"Hey Jandal" wake word** — always-on local detection → instant action routing *(Phase 3F)*

## Roadmap

Work is organised by **launch priority** against the Google Play Store release. The
sequencing below follows the slices in [`docs/PLAN-launch-slice.md`](docs/PLAN-launch-slice.md),
which is the living plan derived from the full backlog review. Labels: 🔴 `launch:blocking`
must ship before publish · 🟡 `launch:post` follows shortly after · ⚪ `launch:deferred` is
consciously parked.

> Counts as of the last backlog review: **24 blocking · 45 post · 17 deferred**. Tracked
> under [Epic #1014 — Play Store Launch Readiness & QA](https://github.com/NickMonrad/kernel-ai-assistant/issues/1014).

### 🔴 Launch Blocking — ordered by delivery slice

**Slice 1 · Foundation & stability** _(do first — unblocks everything)_

| Issue | Size | Summary |
|-------|------|---------|
| ~~[#915](https://github.com/NickMonrad/kernel-ai-assistant/issues/915)~~ · ~~[#916](https://github.com/NickMonrad/kernel-ai-assistant/issues/916)~~ | L · S | ✓ Toolchain upgrade — AGP 9.0.1 / Gradle 9.1.0 / Kotlin 2.3.21 / Hilt 2.59.2 (PR #1082) |
| [#428](https://github.com/NickMonrad/kernel-ai-assistant/issues/428) | M | Memory profiling — peak RAM & concurrent model usage (feeds #430/#432) |
| [#692](https://github.com/NickMonrad/kernel-ai-assistant/issues/692) | M | Fix inference stalls in Boring AI Mode |
| [#937](https://github.com/NickMonrad/kernel-ai-assistant/issues/937) · [#957](https://github.com/NickMonrad/kernel-ai-assistant/issues/957) | M · S | Memory + intent-routing correctness bugs |

**Slice 2 · Heavy hitters — memory-safe model lifecycle** _(highest risk)_

| Issue | Size | Summary |
|-------|------|---------|
| [#430](https://github.com/NickMonrad/kernel-ai-assistant/issues/430) | XL | Dynamic model loading state machine — never hold Gemma-4 + EmbeddingGemma at once |
| [#432](https://github.com/NickMonrad/kernel-ai-assistant/issues/432) | L | Compatibility-tier model swap — auto E-2B + smaller KV cache on 8GB devices |

**Slice 3 · Navigation & visual quality** _(store-listing readiness)_

| Issue | Size | Summary |
|-------|------|---------|
| [#747](https://github.com/NickMonrad/kernel-ai-assistant/issues/747) | M | Back-button & blank-screen navigation bug |
| [#751](https://github.com/NickMonrad/kernel-ai-assistant/issues/751) | L | Navigation refactor — surface Lists / People / Clock / Settings |
| [#226](https://github.com/NickMonrad/kernel-ai-assistant/issues/226) | L | Jandal visual identity — palette, loading states, 🩴 treatment |
| [#961](https://github.com/NickMonrad/kernel-ai-assistant/issues/961) | M | In-chat model settings controls |

**Slice 4 · Finish in-flight capabilities**

| Issue | Size | Summary |
|-------|------|---------|
| [#996](https://github.com/NickMonrad/kernel-ai-assistant/issues/996) | S | Wire Sherpa-ONNX as wake-word dual-threshold verify window |
| [#885](https://github.com/NickMonrad/kernel-ai-assistant/issues/885) · [#886](https://github.com/NickMonrad/kernel-ai-assistant/issues/886) | M · M | Messaging — reply via RemoteInput; send to named group chats |
| [#261](https://github.com/NickMonrad/kernel-ai-assistant/issues/261) | M | Skill discoverability |
| [#928](https://github.com/NickMonrad/kernel-ai-assistant/issues/928) | L | Hierarchical list items |
| [#713](https://github.com/NickMonrad/kernel-ai-assistant/issues/713) | L | Vision foundation — single-image Q&A + image-in-chat |
| [#756](https://github.com/NickMonrad/kernel-ai-assistant/issues/756) · [#824](https://github.com/NickMonrad/kernel-ai-assistant/issues/824) | M · M | Voice — Piper voice-training research; Phase 3F on-device QA gate |

**Slice 5 · Release gate** _(run last)_

| Issue | Size | Summary |
|-------|------|---------|
| [#427](https://github.com/NickMonrad/kernel-ai-assistant/issues/427) | XL | Comprehensive verification — full feature matrix on physical S23 Ultra |
| [#868](https://github.com/NickMonrad/kernel-ai-assistant/issues/868) | S | Documentation & licence/attribution review |
| [#441](https://github.com/NickMonrad/kernel-ai-assistant/issues/441) | M | Publish to Play Store — account, signing, listing, policy compliance |

### 🟡 Post-Launch — fast-follow after publish

- **Memory & data** — cosine-distance vec tables ([#647](https://github.com/NickMonrad/kernel-ai-assistant/issues/647)), anaphoric "remember that" ([#958](https://github.com/NickMonrad/kernel-ai-assistant/issues/958)), low-confidence search filtering ([#959](https://github.com/NickMonrad/kernel-ai-assistant/issues/959)), Artifact entity ([#235](https://github.com/NickMonrad/kernel-ai-assistant/issues/235))
- **Skills** — WebSearch ([#407](https://github.com/NickMonrad/kernel-ai-assistant/issues/407)), calendar events ([#942](https://github.com/NickMonrad/kernel-ai-assistant/issues/942)), podcast quick actions ([#587](https://github.com/NickMonrad/kernel-ai-assistant/issues/587)), map/location ([#258](https://github.com/NickMonrad/kernel-ai-assistant/issues/258)), Plex API ([#594](https://github.com/NickMonrad/kernel-ai-assistant/issues/594)) & YouTube Music ([#596](https://github.com/NickMonrad/kernel-ai-assistant/issues/596))
- **Voice** — STT Kiwi/Māori normalisation epic ([#935](https://github.com/NickMonrad/kernel-ai-assistant/issues/935)), translator skill ([#659](https://github.com/NickMonrad/kernel-ai-assistant/issues/659)), TTS HW accel ([#852](https://github.com/NickMonrad/kernel-ai-assistant/issues/852)), Kokoro quality ([#854](https://github.com/NickMonrad/kernel-ai-assistant/issues/854)), wake-word FP tuning ([#986](https://github.com/NickMonrad/kernel-ai-assistant/issues/986))
- **Chat UX & vision** — thinking-message refactor ([#964](https://github.com/NickMonrad/kernel-ai-assistant/issues/964)), Ephemeral Vision pipeline ([#287](https://github.com/NickMonrad/kernel-ai-assistant/issues/287)), multimodal audio input ([#943](https://github.com/NickMonrad/kernel-ai-assistant/issues/943))
- **Optimisation & runtime** — Matryoshka 256-dim embeddings ([#429](https://github.com/NickMonrad/kernel-ai-assistant/issues/429)), battery deferral ([#431](https://github.com/NickMonrad/kernel-ai-assistant/issues/431)), tok/s benchmark ([#803](https://github.com/NickMonrad/kernel-ai-assistant/issues/803)), grounded-numeric reliability ([#968](https://github.com/NickMonrad/kernel-ai-assistant/issues/968))
- **Test harness** — UIAutomator coverage ([#548](https://github.com/NickMonrad/kernel-ai-assistant/issues/548)) plus harness hardening ([#554](https://github.com/NickMonrad/kernel-ai-assistant/issues/554), [#560](https://github.com/NickMonrad/kernel-ai-assistant/issues/560), [#562](https://github.com/NickMonrad/kernel-ai-assistant/issues/562), [#563](https://github.com/NickMonrad/kernel-ai-assistant/issues/563))

### ⚪ Deferred — parked behind larger phases

- **Phase 4 — Dreaming Engine** ([#705](https://github.com/NickMonrad/kernel-ai-assistant/issues/705)) incl. graph-DB memory research ([#419](https://github.com/NickMonrad/kernel-ai-assistant/issues/419))
- **Phase 5 — Wasm Runtime + Skill Store** ([#706](https://github.com/NickMonrad/kernel-ai-assistant/issues/706)) incl. MCP integration research ([#944](https://github.com/NickMonrad/kernel-ai-assistant/issues/944))
- **Phase 6 — Device Optimisation** ([#707](https://github.com/NickMonrad/kernel-ai-assistant/issues/707)) incl. S21 generation failure ([#684](https://github.com/NickMonrad/kernel-ai-assistant/issues/684))
- **Model experiments** ([#704](https://github.com/NickMonrad/kernel-ai-assistant/issues/704)) — Qwen 3.5 4B/0.8B ([#691](https://github.com/NickMonrad/kernel-ai-assistant/issues/691), [#699](https://github.com/NickMonrad/kernel-ai-assistant/issues/699)), llama.cpp backend ([#702](https://github.com/NickMonrad/kernel-ai-assistant/issues/702))
- **Alternative STT** — Parakeet CTC ([#700](https://github.com/NickMonrad/kernel-ai-assistant/issues/700)) & whisper.cpp ([#703](https://github.com/NickMonrad/kernel-ai-assistant/issues/703)) — gated on the Sherpa-default decision ([#1008](https://github.com/NickMonrad/kernel-ai-assistant/issues/1008))
- **Fun / content skills** — joke ([#819](https://github.com/NickMonrad/kernel-ai-assistant/issues/819)), storytelling ([#820](https://github.com/NickMonrad/kernel-ai-assistant/issues/820)), learn-something-new ([#949](https://github.com/NickMonrad/kernel-ai-assistant/issues/949))

> Full sequencing, dependencies, and critical path: [`docs/PLAN-launch-slice.md`](docs/PLAN-launch-slice.md).
