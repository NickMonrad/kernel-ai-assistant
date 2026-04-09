# Kernel AI Assistant — Roadmap

> **Last updated:** 2026-04-09
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
| **Intent router** | FunctionGemma-270M-FT-Mobile-Actions |
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

---

## Phase 2: Local Semantic Memory (RAG) 🔄 In Progress

The assistant remembers facts across sessions using on-device vector search, with a
tri-tiered memory architecture inspired by the
[Memory Blueprint (#14)](https://github.com/NickMonrad/kernel-ai-assistant/issues/14).

### Memory Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Prompt Assembly                       │
│                                                         │
│  [System Prompt]                                        │
│  [User Profile]          ← Tier 3: always injected      │
│  [Core Memories]         ← Tier 3: permanent facts      │
│  [Episodic Memories]     ← Tier 2: relevant past turns  │
│  [Active Sliding Window] ← Tier 1: current conversation │
│  [Current User Prompt]                                  │
└─────────────────────────────────────────────────────────┘

         ▲ Dreaming Engine promotes facts upward
         │
┌────────┴────────────────────────────────────────────────┐
│  Dreaming Engine (runs while charging + idle)           │
│                                                         │
│  Light Sleep → prune low-value episodic entries (SQL)   │
│  REM Sleep   → extract facts via E-2B inference         │
│  Deep Sleep  → promote to core, rewrite profile, GC    │
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
| Structured prompt assembly + context window | ⬜ Next | — | Layered prompt (system→profile→core→episodic→window→user). Token budgeting. |
| Episodic + Core memory tiers | ⬜ Pending | — | Split flat index into episodic (volatile) + core (permanent). Separate vec0 tables. |
| Memory management UI | ⬜ Pending | — | Profile editor, core memories CRUD, episodic browser, stats |
| Dreaming Engine | ⬜ Pending | — | WorkManager: Light Sleep → REM Sleep → Deep Sleep consolidation |
| Smart chat titles ([#15](https://github.com/NickMonrad/kernel-ai-assistant/issues/15)) | ⬜ Pending | — | LLM-generated summaries, periodic re-titling, manual override |
| Fun loading screens ([#13](https://github.com/NickMonrad/kernel-ai-assistant/issues/13)) | ⬜ Pending | — | Multi-step narrative sequences tied to loading phases |
| UI polish pass ([#22](https://github.com/NickMonrad/kernel-ai-assistant/issues/22), [#25](https://github.com/NickMonrad/kernel-ai-assistant/issues/25), [#26](https://github.com/NickMonrad/kernel-ai-assistant/issues/26), [#27](https://github.com/NickMonrad/kernel-ai-assistant/issues/27)) | ⬜ Pending | — | Back/home button, last message persistence, URL rendering, keyboard gap |

### Key Design Decisions

- **EmbeddingGemma-300M** over Universal Sentence Encoder — much higher quality embeddings,
  hardware-optimised variants for Qualcomm chipsets
- **SentencePiece tokenizer** implemented in pure Kotlin (no protobuf library dependency)
- **Separate databases**: Room (`kernel_db`) for relational data, native SQLite (`kernel_vectors.db`)
  for vectors — because Room doesn't support sqlite-vec's vec0 virtual tables
- **Graceful degradation**: embedding engine returns empty arrays if models aren't loaded,
  RAG pipeline silently skips injection, chat works fine without memory

---

## Phase 3: Brand + Model Management + FunctionGemma Intent Router + Native Skills

Brand refresh to Jandal AI, model management improvements, then FunctionGemma for fast intent routing.

| Task | Status | Notes |
|------|--------|-------|
| Brand pass: Jandal AI ([#21](https://github.com/NickMonrad/kernel-ai-assistant/issues/21)) | ⬜ Pending | App name, package rename `com.kernel.ai` → `com.jandal.ai`, Kiwi persona, Fern Green palette |
| Model persistence (survive reinstall) ([#20](https://github.com/NickMonrad/kernel-ai-assistant/issues/20)) | ⬜ Pending | Store models on external/shared storage instead of `filesDir` |
| Model selection in Settings ([#18](https://github.com/NickMonrad/kernel-ai-assistant/issues/18)) | ⬜ Pending | Let user choose E-2B vs E-4B |

### Phase 3 continued: FunctionGemma Intent Router + Native Skills

FunctionGemma routes user intents to native Android actions without loading the large model.

| Task | Status | Notes |
|------|--------|-------|
| FunctionGemma integration | ⬜ Pending | Pre-fine-tuned LiteRT model, ~154 tk/s on CPU |
| Skill registry + JSON schema generation | ⬜ Pending | Uses LiteRT-LM's `@Tool`/`@ToolParam` annotations |
| Native skills (8+ Kotlin skills) | ⬜ Pending | Flashlight, DND, Bluetooth, Alarms, SMS, Notes, Media |
| Model cascade orchestrator | ⬜ Pending | FunctionGemma → skill exec OR escalate to Gemma-4 |
| Voice input/output | ⬜ Pending | Android `SpeechRecognizer` + `TextToSpeech` |
| Permission handling | ⬜ Pending | Per-skill runtime permissions with graceful degradation |

### Model Cascade Architecture

```
User Input (voice/text)
    │
    ▼
┌─────────────────────────┐
│  FunctionGemma-270M-IT  │  ← Always loaded (~135MB), near-instant
│  "Intent Router"        │
└────────┬────────────────┘
         │
    ┌────┴─────────────────────────────┐
    │                                  │
    ▼ (high confidence)                ▼ (low confidence / complex)
┌──────────────┐              ┌──────────────────────┐
│ Native Skill │              │ Gemma-4 E-4B/E-2B    │
│ or Wasm Skill│              │ + RAG memory context  │
│ (direct exec)│              └──────────────────────┘
└──────────────┘
```

---

## Phase 4: WebAssembly Runtime + Skill Store

Community-extensible skills running in sandboxed Wasm via Chicory.

| Task | Status | Notes |
|------|--------|-------|
| Chicory Wasm integration | ⬜ Pending | Load `.wasm`, WASIp1 environment, host bridges |
| Host bridge functions | ⬜ Pending | `host_log`, `host_get_input`, `host_set_output`, `host_http_get` |
| Resource limiting | ⬜ Pending | Wall-clock timeout, memory cap, output/network limits |
| GitHub skill store | ⬜ Pending | `NickMonrad/kernel-ai-skills` repo with manifest |
| Example Wasm skills | ⬜ Pending | Recipe parser, unit converter |
| Sideloading + permission audit | ⬜ Pending | Import from local storage or URL, risk dialog |
| Skill management UI | ⬜ Pending | Install/update/uninstall, per-skill permissions |
| Skill building & baseline skills ([#34](https://github.com/NickMonrad/kernel-ai-assistant/issues/34)) | ⬜ Pending | Home Assistant, common tasks inspired by Google Edge Gallery |
| Multimodal capabilities ([#32](https://github.com/NickMonrad/kernel-ai-assistant/issues/32)) | ⬜ Pending | Image/camera input using a multimodal model variant |
| LiteRT-LM auto-update mechanism ([#31](https://github.com/NickMonrad/kernel-ai-assistant/issues/31)) | ⬜ Pending | App picks up new LiteRT-LM library releases automatically |

---

## Phase 5: 8GB Device Optimization

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

| Issue | Title | Phase |
|-------|-------|-------|
| [#13](https://github.com/NickMonrad/kernel-ai-assistant/issues/13) | Fun loading screens | Phase 2 |
| [#14](https://github.com/NickMonrad/kernel-ai-assistant/issues/14) | Memory Blueprint (tri-tiered architecture) | Phase 2 (adopted) |
| [#15](https://github.com/NickMonrad/kernel-ai-assistant/issues/15) | Smart chat titles with override | Phase 2 |
| [#18](https://github.com/NickMonrad/kernel-ai-assistant/issues/18) | Model selection in Settings | Phase 3 |
| [#20](https://github.com/NickMonrad/kernel-ai-assistant/issues/20) | Model persistence (survive reinstall) | Phase 3 |
| [#21](https://github.com/NickMonrad/kernel-ai-assistant/issues/21) | Brand Strategy: Jandal AI | Phase 3 |
| [#22](https://github.com/NickMonrad/kernel-ai-assistant/issues/22) | Missing Home/back button | Phase 2 UI polish |
| [#25](https://github.com/NickMonrad/kernel-ai-assistant/issues/25) | Last streaming message lost on nav away | Phase 2 UI polish |
| [#26](https://github.com/NickMonrad/kernel-ai-assistant/issues/26) | URL/markdown links not clickable | Phase 2 UI polish |
| [#27](https://github.com/NickMonrad/kernel-ai-assistant/issues/27) | Keyboard gap at bottom of chat | Phase 2 UI polish |
| [#29](https://github.com/NickMonrad/kernel-ai-assistant/issues/29) | WASM Plugin/Skill Storefront | Phase 4 |
| [#31](https://github.com/NickMonrad/kernel-ai-assistant/issues/31) | LiteRT-LM auto-update mechanism | Phase 4 |
| [#32](https://github.com/NickMonrad/kernel-ai-assistant/issues/32) | Multimodal capabilities | Phase 4 |
| [#34](https://github.com/NickMonrad/kernel-ai-assistant/issues/34) | Skill building & baseline skills | Phase 4 |

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
