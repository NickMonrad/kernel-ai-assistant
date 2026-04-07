# Copilot Instructions — Kernel AI Assistant

## Project Overview

An Android-native, **local-first AI assistant** with zero cloud dependencies. All inference runs on-device via Google AI Edge (LiteRT). The architecture follows a **Brain–Memory–Action** triad, orchestrated centrally in Kotlin.

## Dev Environment Prerequisites

- **Android Studio:** Ladybug (2024.2.1) or newer
- **Android NDK:** Required for SQLite-VSS and Wasm JNI bridges
- **RAM:** 32GB minimum (LiteRT builds + Android Emulator)
- **Test device:** Physical hardware with 8–12GB RAM and Snapdragon 8 Gen 2/3 (or NPU-equivalent)

## Architecture

### Brain — LiteRT Inference Engine

Two hardware tiers, selected at runtime by the **Model Manager**:

| Tier | RAM | Reasoning Model | Embedding Model |
|---|---|---|---|
| Performance | 12GB+ | Gemma-4 E-4B | EmbeddingGemma-300M |
| Compatibility | 8GB | Gemma-4 E-2B | Gecko-110m-en |

- All models use **INT4 (4-bit) quantization** via LiteRT with GPU + NPU delegates (AICore).
- The Model Manager handles dynamic weight warm-up/teardown to control peak RAM.
- A shared **FunctionGemma-270M** acts as the "Intent Router" for tool/skill dispatch across both tiers.

### Memory — Local RAG

**Short-term (session):** LiteRT-LM KV Cache — 4,096 tokens (Performance) / 2,048 tokens (Compatibility). At 80% capacity, the reasoning model generates a recursive summary injected back into the prompt.

**Long-term (semantic):** SQLite + VSS (Vector Similarity Search) extension. Every user query triggers a cosine-similarity search; the top 3–5 fragments are prepended to the system prompt. Embeddings run in a background thread via Gecko-110m or EmbeddingGemma-300M.

### Action — Skill Framework

**Native Skills (Kotlin/JVM)** — high-privilege OS integrations:
- Device controls: Flashlight, DND, Bluetooth, Alarm/Timer
- Communication: Email (`ACTION_SEND`), background SMS (`SEND_SMS`)
- Productivity: Google Keep via Android Intents
- Media: MediaSession API (e.g., Plexamp)

**Extensible Skills (WebAssembly)** — sandboxed, community-extensible:
- Runtime: Extism or Wasmtime via JNI bridge
- No direct OS access; data exchange via JSON or Protobuf
- A **"Fuel" system** caps CPU cycles per Wasm plugin (prevents battery drain/infinite loops)
- Sideloaded skills require user "Accept Risk" acknowledgement and a permission audit of the Wasm import section

## Key Conventions

- **Kotlin is the host language.** All orchestration, OS integration, and JNI bridges are Kotlin/JVM. Wasm is guest-only.
- **No external LLM APIs.** Never introduce network calls to cloud inference endpoints; all model inference must go through LiteRT.
- **Skill registry is decoupled.** Skills are not hardcoded into the main app flow — they are registered and dispatched through the central skill registry.
- **Skill Store source of truth:** A curated GitHub repo with a `manifest.json` containing verified skill hashes and Wasm binaries. Sideloading (ZIP or URL) is also supported and hot-swappable at runtime.
- **Context window is managed, not truncated.** When approaching the token limit, trigger recursive summarization via the 2B/4B model — do not simply drop history.
- **Wasm sandboxing is non-negotiable.** Wasm modules must never receive direct OS capabilities; capabilities are granted only via explicit Kotlin host bridge functions.

## Agent Working Model

This project uses a **Sonnet-orchestrates, Codex-implements** pattern:

| Role | Agent | Responsibility |
|------|-------|----------------|
| **Orchestrator** | Sonnet (this session) | Analysis, planning, coordination, decisions, PR descriptions |
| **Implementor** | `codex-developer` | All code changes — features, bug fixes, refactors |
| **Test writer** | `playwright-test-engineer` | All Playwright/instrumented E2E test creation and fixes |

### Rules
- **Sonnet never writes code directly** — all implementation is delegated to `codex-developer`
- **Both agents can run in parallel** when a task requires code changes AND new tests
- Sonnet reviews agent output, spot-checks critical changes, and raises PRs
- If an agent fails twice, Sonnet may attempt the task directly as a fallback

## Branching & PR Standards

- Default branch: `main`
- Feature branches: `feature/<short-name>` (e.g. `feature/wasm-skill-store`)
- **Always branch from `main`**, never chain branches
- **Copilot raises PRs, the repo owner merges** — never auto-merge
- PR body must include `Closes #N` for the relevant GitHub issue

## Commit Message Format

```
type(#issue): short description

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`

## Communication Standards

- **Always include the full URL** when creating a PR or issue
- **State the branch name** when starting a new feature, before any work begins
- **After pushing to a PR branch**, proactively check CI status and report pass/fail
- **After a PR is created**, immediately merge `main` into the feature branch if it's behind
- **Report sub-agent failures immediately** — surface and retry before proceeding

## GitHub Issues

- Close issues in PR body with `Closes #N`
- When a feature request is raised mid-session, create a GitHub issue for it

## Implementation Phases (Roadmap)

1. Core LiteRT-LM integration with GPU acceleration for Gemma-4
2. SQLite-VSS + Gecko for local semantic search
3. FunctionGemma Intent Router + initial Native Skills
4. Wasm Runtime + GitHub-based Skill Store
5. 8GB device optimization (dynamic weight loading/unloading)
