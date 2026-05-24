# Architecture Reference

Load this when navigating unfamiliar modules or making architectural decisions.

## Three-Tier Resident Agent

```
User input
    │
    ▼
┌─────────────────────────────┐
│  Tier 2: QuickIntentRouter  │  Pure Kotlin regex + MiniLM classifier
│  20 intents: alarms/timers, │    ~0MB, <5ms, runs on main thread
│  media, torch, DND, nav…    │
└────────┬────────────────────┘
         │ no match
         ▼
┌─────────────────────────────┐
│  Tier 3: Gemma-4 E-4B/E-2B │  Resident on GPU, TTFT ~2.3s
│  Native JSON tool calling   │    Complex NLU + tool calling via SkillExecutor
│  + RAG memory context       │
└─────────────────────────────┘
```

**FunctionGemma-270M is deprecated.** Do not load at startup or wire new features to it.

## Module structure

| Module | Purpose |
|--------|---------|
| `:app` | Entry point, Hilt DI, navigation, splash |
| `:core:inference` | LiteRT-LM engine wrapper, model manager, hardware tier detection |
| `:core:memory` | sqlite-vec JNI, EmbeddingGemma, RAG pipeline |
| `:core:wasm` | Chicory Wasm host, bridge functions, resource limiting |
| `:core:ui` | Shared Compose components, Material 3 theme |
| `:core:skills` | SkillInterface, SkillRegistry, JSON schema generation |
| `:feature:chat` | Chat screen, conversation list, ChatViewModel |
| `:feature:settings` | Memory management, skill store, model info, persona config |
| `:feature:onboarding` | First-launch model download flow |
| `:feature:widget` | Glance homescreen widget, VoiceCommandActivity |
| `:feature:convert` | Text conversion utilities |

## Model inventory

| Model | Role | Size | Loading |
|-------|------|------|---------|
| Gemma-4 E-4B / E-2B | Resident reasoning + tool calling | ~3.4GB | Eager at startup |
| EmbeddingGemma-300M | Semantic embeddings (RAG) | <200MB | Lazy on first RAG query |
| all-MiniLM-L6-v2 int8 | Zero-shot intent classifier | ~15MB | Lazy, graceful null fallback |
| FunctionGemma-270M | ~~Intent router~~ **Deprecated** | 289MB | Not loaded; class retained |

## Key architectural invariants

- **Kotlin is the host language.** Wasm is guest-only, never receives direct OS access.
- **No cloud inference.** All inference through LiteRT — never introduce network calls to cloud endpoints.
- **Context window managed, not truncated.** At 80% KV capacity → recursive summarisation injected into prompt.
- **E4B loads first on GPU** — prevents lmkd OOM during ~20s kernel compilation peak.
- **Backend fallback chain:** NPU → GPU (OpenCL/Adreno 740) → CPU.
- **`gemma4InitMutex`** guards all E4B init paths — both `initEngineWhenReady()` and `initGemma4()`.
- **`safeTokenCount()`** nudges powers-of-2 down ~2.4% (4096→4000, 8192→8000) to avoid Adreno reshape bug.
- **`tryExecuteToolCall()`** in ChatViewModel: unknown skill or malformed JSON → plain text fallback, never crash.
- **Contract-first skill development:** JSON schema (`SkillSchema`) before logic; version bump in manifest on changes.
