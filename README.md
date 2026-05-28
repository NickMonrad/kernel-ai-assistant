# Jandal AI

A high-performance, **local-first** intelligent agent for Android. All inference runs entirely on-device — no cloud APIs, no telemetry, no data leakage. Built on Google's **Gemma-4** model family via **LiteRT**.

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
|- 🗣️ **VCTK multi-speaker selection** — choose from 109 VCTK voices (gender filter, speaker ID, accent label) in Settings → Voice; sid mapping sourced directly from the Piper model config
|- 🗣️ **Semaine multi-speaker selection** — 4 distinct voices (Prudence, Spike, Obadiah, Poppy) selectable in Settings → Voice (#818, PR #818)
|- 📐 **Deterministic unit conversion** — length, mass, volume, temperature, speed with alias normalisation and spoken-STT variants (#676, PR #816)
|- 💱 **Deterministic currency conversion** — ISO code resolution via Frankfurter/ECB rates with same-currency short-circuit and clear error for unsupported currencies (#831, PR #848)
|- 🗣️ **TTS pronoun normalisation** — converts first-person pronouns (my/I → your/you) in spoken summaries so the assistant speaks in third person (#828, PR #830)
|- 🔊 **Voice fallthrough preservation** — Actions→Chat fallthrough preserves the user's voice-speak expectation so replies are spoken even after cross-screen navigation (#832, PR #833)
|- ⏱️ **Slot-fill retry on no-speech** — system retries the slot-fill prompt instead of failing; cancel phrases abort the flow; start-listening audio cue confirms mic activity (#790/#791, PR #825)
|- 🔒 **Blank response guard** — retries without RAG before showing fallback when LiteRT produces 0 tokens; keeps chat awake during load and generation (#839/#841, PRs #840/#842)
|- 🎙️ **Homescreen Glance widget** — quick actions and voice from the launcher via GlanceAppWidget; VoiceCommandActivity and WidgetTextInputActivity with task isolation (#617, PR #847)
|- 🔧 **Audio quality fixes** — AudioTrack tail cutoff prevention via hardware-latency silence padding; expectedSlotPromptSpeech normalisation to match TTS output; SID=0 clamp for single-speaker voices; aye pronunciation correction (#837/#828/#810, PRs #838/#836/#811)
|- 🍽️ **Deterministic meal planner** — app-owned meal-planning sessions with bounded JSON generation, draft-plan approval, progressive recipe reveal, visible `x of y` progress, interruption-safe resume, quantity sanity validation, cuisine preferences, Kiwi wording normalization, batch day replace/regenerate, and quick-action/chat handoff (#859/#869/#931/#932/#971, PRs #864/#875/this PR)
|- 📚 **Meal plans browser** — drawer-accessible Recent plans and Favourites tabs with recipe search, canonical favourite toggles, recipe re-add to Lists, and ingredient export into existing user lists (#933, PR #934)
|- 💬 **Chat UX overhaul** — visual customisation (bubble colours, font size, chat background), attachment picker foundation, and tool chip redesign (#963/#911/#952/#906, PR #980)
|- 🗒️ **Note-taking / voice memo skill** — `create_note` native skill with Tier 2 instant routing for "add a note", "voice memo", "note that" patterns; Notes browser accessible from the nav drawer (#823, PR #989)
|- 💭 **Thinking mode fully enabled** — `enable_thinking` extraContext flag correctly activates chain-of-thought; channel wrapper markup stripped from visible output; background utility calls explicitly suppress thinking to avoid GPU waste (#946, PR #946)
|- 🔔 **Important date day-of notifications** — WorkManager job fires an on-device notification on the day of a saved important date (#902, PR #923)
|- 🗣️ **TTS normalisation** — fraction-to-word ("1/2" → "a half"), ordinals ("1st" → "first"), number ranges, and expanded Kiwi corpus (#876/#912, PR #930)
|- 🌦️ **Broader weather routing** — weather skill now triggers without "forecast" keyword; phrases like "is it raining", "what's the weather like" route correctly (#929, PR #972)

### Coming Soon
- 💬 **Expanded multi-turn dialog** — broader confirmation, digression, and slot-filling coverage across more intents *(Phase 3G, #708 and follow-ups)*
- 💭 **Thinking bubble** — visual presentation of chain-of-thought in the chat stream *(#964)*
- 🛠️ **In-chat model controls** — adjust model settings without leaving the conversation *(#961)*
- 🗒️ **Lists — management upgrades** — rename, pin, sort, edit items, favorites, and due dates *(#662)*
- ⏰ **Alarms CRUD UI** — create, edit, and toggle alarms directly from the Alarms screen *(Phase 3, #479)*
- 🌙 **Dreaming Engine** — overnight WorkManager consolidation (Light Sleep → REM → Deep Sleep) *(Phase 4)*
- ⚡ **Semantic cache** — instant responses for repeated knowledge queries *(Phase 4)*
- 🪪 **Self-healing identity** — structured user profile, LLM-managed via Dreaming cycle *(Phase 4)*
- 🧩 **Wasm skill store** — community-extensible plugins with sandboxed execution *(Phase 5)*
- 🏠 **Home Assistant / Google Home** — smart home control *(Phase 5)*
- 📱 **8GB device optimisation** — dynamic weight loading/unloading, E2B fallback *(Phase 6)*
- 🎙️ **"Hey Jandal" wake word** — always-on local openWakeWord detection + Android Default Assistant integration *(Phase 3F, #65)*

## Roadmap
