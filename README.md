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
- 🧭 **Nav drawer** — Lists and Alarms accessible from Chat, Actions, and all main screens via hamburger menu
- 📋 **Lists** — create and manage named lists via chat ("add milk to shopping list") or the Lists UI; full CRUD with active/completed sections
- 🗓️ **Scheduled Alarms** — date-specific alarms scheduled via Jandal appear in the Alarms screen for review and cancellation
- 📟 **Side panel** — slide-out drawer accessible from Chat and Settings shows active alarms and timers with live countdown; cancel any from the panel
- 🎵 **Media controls** — pause, stop, skip, and previous track via Jandal ("skip song", "pause music")
- 🎙️ **Podcast playback** — open a podcast app and start/resume playback ("play my podcasts", "resume podcast")
- ⏱️ **Timer management** — list active timers and cancel individual ones ("cancel my 10 minute timer")
- 🗑️ **Alarm multiselect delete** — select multiple alarms in the Alarms screen and delete them at once
- 📝 **Bulk list add** — add multiple items to a list in one request ("save all ingredients to shopping list")

### Coming Soon
- 🗣️ **Voice input** — tap-to-talk with auto-stop *(Phase 3F)*
- 💬 **Expanded multi-turn dialog** — broader confirmation, digression, and slot-filling coverage across more intents *(Phase 3G, #708 and follow-ups)*
- 🗒️ **Lists — management upgrades** — rename, pin, sort, edit items, favorites, and due dates *(#662)*
- ⏰ **Alarms CRUD UI** — create, edit, and toggle alarms directly from the Alarms screen *(Phase 3, #479)*
- 📱 **Homescreen widget** — quick actions and voice from the launcher *(Phase 3F, #617)*
- 🌙 **Dreaming Engine** — overnight WorkManager consolidation (Light Sleep → REM → Deep Sleep) *(Phase 4)*
- ⚡ **Semantic cache** — instant responses for repeated knowledge queries *(Phase 4)*
- 🪪 **Self-healing identity** — structured user profile, LLM-managed via Dreaming cycle *(Phase 4)*
- 🧩 **Wasm skill store** — community-extensible plugins with sandboxed execution *(Phase 5)*
- 🏠 **Home Assistant / Google Home** — smart home control *(Phase 5)*
- 📱 **8GB device optimisation** — dynamic weight loading/unloading, E2B fallback *(Phase 6)*
- 🎙️ **"Hey Jandal" wake word** — always-on local detection → instant action routing *(Phase 3F)*

## Roadmap

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Core LiteRT-LM integration + GPU/NPU acceleration + Chat UI | ✅ |
| 2 | sqlite-vec + EmbeddingGemma for local RAG + memory, UI polish, model selection | ✅ |
| 3 | Native Skills (alarms, lists, weather, media, navigation) + multi-turn dialog + episodic distillation + nav drawer + Brand refresh | 🔄 |
| 4 | Dreaming Engine (WorkManager overnight cycle) + Semantic Cache + Self-Healing Identity System | ⬜ |
| 5 | Chicory Wasm runtime + GitHub Skill Store | ⬜ |
| 6 | 8GB device optimization (dynamic weight loading) | ⬜ |

## Getting Started

### Tested devices

| Device | Chip | RAM | Backend | Status |
|--------|------|-----|---------|--------|
| Samsung Galaxy S23 Ultra | Snapdragon 8 Gen 2 (SM8550) | 12 GB | NPU (Hexagon) | ✅ Tested |
| Google Pixel 10 | Tensor G5 | 12 GB | GPU (Immortalis-G925) | ✅ Expected compatible |

> **Backend note:** On Qualcomm devices the app uses the Hexagon NPU delegate for fastest inference. On Google Pixel 10 (Tensor G5) the app uses `Backend.GPU` (ARM Immortalis-G925 via LiteRT). Performance and output are equivalent — the hardware tier detection automatically selects the right delegate. See [`models/README.md`](models/README.md) for per-device model setup.

### Prerequisites

- Android Studio Ladybug (2024.2.1) or newer
- Android NDK (for sqlite-vec)
- JDK 17
- Physical Android device with 8–12GB RAM (emulator cannot test GPU/NPU)

### Setup

```bash
git clone https://github.com/NickMonrad/kernel-ai-assistant.git
cd kernel-ai-assistant
./gradlew assembleDebug
```

Model files are managed manually for local development. Place the required `.litertlm`,
`.tflite`, and `sentencepiece.model` files under `models/` and push them to the device
using the instructions in [`models/README.md`](models/README.md).

Open in Android Studio, connect your device via USB, and run.

### Optional: Android CLI for agent workflows

This repo can use the official `android` CLI as a low-noise accelerator for agentic Android work:

```bash
./scripts/setup-android-cli.sh
android describe --project_dir=.
android docs search 'edge-to-edge Compose guidance'
```

Useful commands in this repo:

- `android describe --project_dir=.` — discover project metadata and build outputs
- `android docs search ...` / `android docs fetch ...` — fetch official Android guidance
- `android layout --pretty` — inspect the active app UI from a connected device/emulator
- `android run --apks=app/build/outputs/apk/debug/app-debug.apk` — deploy a known APK

`./scripts/setup-android-cli.sh` installs the official Google CLI into `~/.local/bin` on supported platforms, runs `android init`, and wires the `android-cli` skill into detected agents such as OpenCode and Copilot.

If the CLI is unavailable on your platform, keep using the normal Gradle + `adb` workflow.

## Testing

- Automated test overview: [`docs/automated-testing.md`](docs/automated-testing.md)
- Device setup and logcat guide: [`docs/adb-testing.md`](docs/adb-testing.md)
- Detailed backlog/specification: [`docs/testing/automated-test-specification.md`](docs/testing/automated-test-specification.md)

Common commands:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
python3 scripts/adb_skill_test.py --phases weather,lists
python3 scripts/adb_skill_test.py --profile
```

## Key Technical Pillars

* **Inference:** Google AI Edge (LiteRT) with INT4 quantization, GPU + NPU delegates
* **Extensibility:** GitHub-indexed Skill Store (Rust → Wasm) + Native Kotlin skills
* **Context:** Recursive context window management with semantic summarization
* **Privacy:** 100% offline-capable; no telemetry or external LLM API dependencies

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

This project adapts code from [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) and uses the [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) library. See [NOTICE](NOTICE) for attribution.
