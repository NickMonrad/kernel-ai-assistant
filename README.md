# Kernel AI Assistant

A high-performance, **local-first** intelligent agent for Android. All inference runs entirely on-device — no cloud APIs, no telemetry, no data leakage. Built on Google's **Gemma-4** model family via **LiteRT**.

## How It Works

The app operates on a **Brain–Memory–Action** triad:

* **The Brain:** A three-model cascade that adapts to your hardware. **FunctionGemma** (270M) handles instant intent routing, while **Gemma-4 E-4B/E-2B** is loaded on-demand for complex reasoning — delivering flagship-class AI on 8–12GB devices.
* **The Memory:** A local **RAG (Retrieval-Augmented Generation)** system using **sqlite-vec** and **EmbeddingGemma-300M**. The assistant remembers personal facts, preferences, and conversation history across sessions with zero data leaving the device.
* **The Action:** A modular skill framework. Native **Kotlin** skills handle high-privilege OS integrations (SMS, device controls, media). Community-extensible **WebAssembly** skills run in a sandboxed **Chicory** runtime for safe extensibility.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 Dynamic Color |
| Inference | Google AI Edge (LiteRT + LiteRT-LM) |
| Reasoning | Gemma-4 E-4B / E-2B (INT4 quantized) |
| Intent Router | FunctionGemma-270M (Mobile Actions fine-tune) |
| Embeddings | EmbeddingGemma-300M (768-dim) |
| Vector Search | sqlite-vec (NDK) |
| Wasm Runtime | Chicory (pure JVM) |
| DI | Hilt |
| Persistence | Room |
| Min SDK | API 35 (Android 15) |

## Features

### Delivered
- 🧠 **On-device reasoning** — Gemma-4 running on GPU/NPU via LiteRT, no internet required
- 💾 **Persistent memory** — RAG-powered recall of personal facts and conversation history
- 🔒 **100% private** — No cloud APIs, no telemetry, all data stays on device
- 💬 **Full markdown rendering** — headings, bold, italic, code blocks, links, lists
- 🎯 **Smart chat titles** — auto-generated from conversation content
- ⚙️ **Model selection** — choose between E-2B and E-4B in Settings
- 🎬 **Fun loading screens** — 13 themed animated narratives
- 🖼️ **Context window management** — structured prompt assembly with KV cache management
- 📊 **Runtime info** — shows active model, backend (GPU/NPU/CPU), and device tier in chat

### Coming Soon
- 🗣️ **Voice + text input** — tap-to-talk with auto-stop *(Phase 3)*
- 🔧 **Native skills** — Flashlight, DND, Bluetooth, Alarms, SMS, Email, Media Control, Notes *(Phase 3)*
- 🧩 **Wasm skill store** — community-extensible plugins (Rust → Wasm) with sandboxed execution *(Phase 4)*
- 🏠 **Home Assistant** — smart home control via Wasm skill *(Phase 4)*

## Roadmap

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Core LiteRT-LM integration + GPU/NPU acceleration + Chat UI | ✅ |
| 2 | sqlite-vec + EmbeddingGemma for local RAG + memory, UI polish, model selection | 🔄 |
| 3 | FunctionGemma intent router + Native Skills + Voice I/O + Brand refresh (Jandal AI) | ⬜ |
| 4 | Chicory Wasm runtime + GitHub Skill Store | ⬜ |
| 5 | 8GB device optimization (dynamic weight loading) | ⬜ |

## Getting Started

### Prerequisites

- Android Studio Ladybug (2024.2.1) or newer
- Android NDK (for sqlite-vec)
- JDK 17
- Physical Android device with 8–12GB RAM (emulator cannot test GPU/NPU)

### Setup

```bash
git clone https://github.com/NickMonrad/kernel-ai-assistant.git
cd kernel-ai-assistant
./scripts/download-models.sh       # Download AI models from HuggingFace (~3GB)
```

Open in Android Studio, connect your device via USB, and run.

## Key Technical Pillars

* **Inference:** Google AI Edge (LiteRT) with INT4 quantization, GPU + NPU delegates
* **Extensibility:** GitHub-indexed Skill Store (Rust → Wasm) + Native Kotlin skills
* **Context:** Recursive context window management with semantic summarization
* **Privacy:** 100% offline-capable; no telemetry or external LLM API dependencies

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

This project adapts code from [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) and uses the [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) library. See [NOTICE](NOTICE) for attribution.
