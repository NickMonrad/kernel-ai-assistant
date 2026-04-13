# Copilot Instructions — Kernel AI Assistant

## Project Overview

An Android-native, **local-first AI assistant** with zero cloud dependencies. All inference runs on-device via Google AI Edge (LiteRT). The architecture follows a **Brain–Memory–Action** triad, orchestrated centrally in Kotlin.

**Repo:** `NickMonrad/kernel-ai-assistant`
**Test device:** Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2, 12GB RAM, Android 16 / One UI 8.0)

## Dev Environment

- **Android Studio:** Ladybug (2024.2.1) or newer
- **Android NDK:** Required for sqlite-vec JNI bridge
- **Min SDK:** API 35 (Android 15), target SDK 36
- **RAM:** 32GB minimum (LiteRT builds + Android Emulator)
- **Physical device required** for NPU/GPU testing — emulator cannot test hardware delegates

## Gradle Module Structure

```
:app                  Entry point, Hilt DI setup, navigation, splash screen
:core:inference       LiteRT-LM engine wrapper, model manager, hardware tier detection
:core:memory          sqlite-vec integration, EmbeddingGemma, RAG pipeline
:core:wasm            Chicory Wasm host runtime, bridge functions, resource limiting
:core:ui              Shared Compose components, Material 3 theme
:core:skills          Skill interface, SkillRegistry, JSON schema generation
:feature:chat         Chat screen, conversation list, ChatViewModel
:feature:settings     Memory management, skill store, model info, persona config
:feature:onboarding   First-launch model download flow
```

## Architecture

### Brain — Resident Agent (Three-Tier)

The architecture uses a three-tier Resident Agent pattern — FunctionGemma is **deprecated** and no longer loaded at startup:

```
User input
    │
    ▼
┌─────────────────────────────┐
│  Tier 2: QuickIntentRouter  │  ← Pure Kotlin regex, ~0MB, <5ms (⬜ pending)
│  Torch / Timer / DND etc.   │    Deterministic matching for ~8 simple actions
└────────┬────────────────────┘
         │ no match
         ▼
┌─────────────────────────────┐
│  Tier 3: Gemma-4 E-4B/E-2B │  ← Resident on GPU, TTFT ~2.3s
│  Native JSON tool calling   │    Complex NLU + tool calling via SkillExecutor
│  + RAG memory context       │    Confirmed working: weather, save_memory, get_system_info
└─────────────────────────────┘
```

**Model inventory:**

| Model | Role | Size | Loading |
|-------|------|------|---------|
| Gemma-4 E-4B (Performance) / E-2B (Compat) | Resident reasoning + tool calling | ~3.4GB | Eager at startup — E4B-first to guarantee full GPU headroom |
| EmbeddingGemma-300M | Semantic embeddings (RAG) | <200MB | Lazy — loads on first RAG-triggering query |
| FunctionGemma-270M | ~~Intent router~~ **Deprecated** | 289MB | Not loaded at startup; class retained for future reference |

- All models use **quantized weights** (INT4/INT8) via LiteRT
- E4B runs on **GPU (OpenCL / Adreno 740)** — loaded first with full memory headroom
- `safeTokenCount()` guard: nudges powers-of-2 down ~2.4% to avoid LiteRT `reshape::Eval` buffer-alignment bug on Adreno (4096→4000, 8192→8000)
- **E4B-first loading:** E4B initialises on GPU before FunctionGemma is considered — prevents lmkd OOM during ~20s GPU kernel compilation peak
- Backend fallback chain: NPU → GPU → CPU

### Memory — Local RAG

**Short-term (session):** LiteRT-LM KV Cache — 4,000 tokens (Performance) / 2,000 tokens (Compatibility). At 80% capacity, the reasoning model generates a recursive summary injected back into the prompt.

**Long-term (semantic):** sqlite-vec (compiled via NDK for arm64-v8a) with Room entities. Every user query triggers cosine-similarity search via `vec_distance_cosine()`; top 3–5 memory fragments prepended to system prompt. EmbeddingGemma-300M generates 768-dim vectors (256-dim on 8GB tier via Matryoshka reduction).

### Action — Skill Framework

**Native Skills (Kotlin/JVM)** — high-privilege OS integrations:
- Device controls: Flashlight, DND, Bluetooth, Alarm/Timer
- Communication: Email (`ACTION_SEND`), background SMS (`SEND_SMS`)
- Productivity: Local notes in Room database (future: Nextcloud Notes)
- Media: Generic MediaSession controller via NotificationListenerService

**Extensible Skills (WebAssembly)** — sandboxed via **Chicory** (pure JVM, v1.0+):
- No direct OS access; data exchange via JSON through shared linear memory
- Resource limiting via coroutine timeouts (5s wall-clock), memory caps (16MB), output limits (1MB)
- Wasm skills authored in **Rust** (via wasm-pack / cargo-component)
- Sideloaded skills require permission audit of Wasm import section + user "Accept Risk" dialog

## Key Conventions

### Architecture
- **Kotlin is the host language.** All orchestration, OS integration, and JNI bridges are Kotlin/JVM. Wasm is guest-only.
- **No external LLM APIs.** Never introduce network calls to cloud inference endpoints; all model inference must go through LiteRT.
- **Contract-first skill development.** Define the JSON schema (`SkillSchema`) for every skill before writing logic. The schema is injected into the system prompt via `SkillRegistry.buildFunctionDeclarationsJson()` so E4B knows available tools. Any skill change starts with a version bump in the manifest.
- **Context window is managed, not truncated.** When approaching the token limit, trigger recursive summarisation — do not simply drop history.
- **FunctionGemma is deprecated.** Do not load FunctionGemma at startup or wire new features to it. All intent routing goes through `QuickIntentRouter` (Tier 2) or Gemma-4 E4B native tool calling (Tier 3).

### Performance & Safety
- **Dedicated LLM Dispatcher.** All inference runs on a custom coroutine dispatcher (dedicated thread pool), never `Dispatchers.Main`. Keep UI responsive while GPU is saturated.
- **E4B tool call validation.** `tryExecuteToolCall()` in `ChatViewModel` parses E4B JSON output and dispatches to `SkillExecutor`. If the skill name is unknown or JSON is malformed, fall through to plain text response — do not crash.
- **Verify quantization.** Use LiteRT Metadata Extractor to confirm models are actually running at the expected bit-depth. An accidental FP32 model will OOM on 8GB devices.
- **LeakCanary from day one.** Integrate early to catch model weight leaks — weights that linger after a conversation closes.
- **`gemma4InitMutex`** guards all E4B init paths — both `initEngineWhenReady()` and `initGemma4()` must hold this lock to prevent concurrent double-init and GPU engine orphaning.

### Security
- **Explicit Intents only.** When triggering native Android actions (SMS, email, etc.), never use implicit intents that could be intercepted by other apps.
- **Wasm sandboxing is non-negotiable.** Wasm modules never receive direct OS capabilities. All capabilities granted via explicit Kotlin host bridge functions.
- **Domain-scoped network access.** Wasm skills needing HTTP get domain-specific bridge functions (e.g., `fetchHomeAssistant(path)`) with URL validation against an allowlist — never a generic `fetch()`.

### Cold Start Strategy
- E4B loads eagerly on GPU at startup (~20s first boot, ~2s with kernel cache) — all features available immediately once ready
- EmbeddingGemma loads lazily on first RAG-triggering query
- "Thinking…" indicator shown while E4B is initialising

## UI/UX

- **Jetpack Compose** with Material 3 Dynamic Color, dark default (AMOLED-friendly)
- **Chat-centric:** conversations list as home, chat screen as primary interaction
- **Multiple conversations** with Room-persisted history (create/delete/rename)
- **Voice:** tap-to-toggle with auto-stop on silence (future: wake word)
- **Skill results:** inline rich cards in the conversation stream
- **Persona:** friendly, concise, slightly playful default (future: configurable)

## Agent Working Model

This project uses a **Sonnet-orchestrates, specialists-implement** pattern with 7 agents:

| Agent | Role | Domain |
|-------|------|--------|
| **coordinator** | Orchestrator | Decomposes multi-domain tasks, routes to specialists, synthesises results |
| **android-developer** | Implementor | Kotlin/Compose/Gradle, native skills, UI, app plumbing |
| **llm-engineer** | AI specialist | LiteRT integration, model cascade, RAG pipeline, prompt engineering |
| **test-writer** | Test specialist | JUnit 5 + MockK unit tests, Compose UI tests (independent of implementation) |
| **spec-writer** | Documentation | README, specification.md, copilot-instructions.md, skill schemas |
| **code-reviewer** | Reviewer | Security, memory safety, LiteRT anti-patterns, correctness |
| **wasm-skill-author** | Wasm specialist | Rust → Wasm skills, Chicory bridge, Skill Store (Phase 4+) |

### Rules
- **test-writer works independently** — never sees the implementation agent's prompt; tests based on interfaces and contracts only
- **code-reviewer runs before every PR merge** — at minimum a quick pass
- **Agents can run in parallel** when work is independent (e.g., android-developer + test-writer)
- **Owner reviews and tests on S23 Ultra before merging** — every feature delivery includes manual testing steps and ADB commands
- If an agent fails twice, escalate or attempt the task directly as a fallback

### Typical workflow for a feature
```
1. Sonnet/coordinator: analyse issue, explore codebase, form plan
2. Dispatch: android-developer or llm-engineer (implementation)
3. Parallel: test-writer (tests based on interfaces)
4. Parallel: spec-writer (update docs if needed)
5. Sonnet: raise PR with Closes #N
6. Parallel: code-reviewer reviews the PR + CI runs
7. Sonnet: push any fixes from code review to the PR branch
8. code-reviewer: re-review the fix commits (confirm issues resolved, no regressions introduced)
9. Owner: manual test on S23 Ultra via ADB once CI passes
10. Owner: final review and merge
```

**Code review is mandatory before every merge.** The re-review pass (step 8) is scoped to the fix commits only — not a full re-review of the whole PR.

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

## Development Workflow

### Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew test                   # Run all unit tests (JUnit 5 + MockK)
./gradlew testDebugUnitTest      # Unit tests for debug variant only
./gradlew connectedDebugAndroidTest  # Compose UI tests (requires connected device)
./gradlew lint                   # Android lint
./gradlew installDebug           # Build + install on connected device via ADB
./gradlew :core:inference:test   # Run tests for a single module
```

### Testing Strategy

- **Unit tests:** JUnit 5 + MockK for all non-UI logic (inference engine, skill registry, RAG pipeline, model manager)
- **Compose UI tests:** `androidx.compose.ui:ui-test-junit4` for chat screen, conversation list, settings
- **Test location:** `src/test/` for unit tests, `src/androidTest/` for Compose/instrumented tests
- **Mocking LiteRT:** The inference engine is behind an interface (`InferenceEngine`) — mock it in tests, never load real models in CI

### Model Files

Models are **gitignored** (1-3GB each). After cloning:
```bash
./scripts/download-models.sh     # Downloads all models from HuggingFace to app/src/main/assets/models/
./scripts/download-models.sh functiongemma   # Download a single model
```
The script verifies SHA256 hashes after download. Models directory: `models/` (gitignored).

### Device Deployment

- **USB wired ADB** is the standard deployment method
- `adb devices` to verify S23 Ultra is connected
- `./gradlew installDebug` to build and deploy
- `adb logcat -s KernelAI` to tail app logs (all app logging uses the `KernelAI` tag)

### Dev Loop (typical feature)

```
1. Branch from main: git checkout -b feature/<name>
2. Implement changes (or delegate to android-developer/llm-engineer)
3. ./gradlew test                              # Unit tests pass
4. ./gradlew connectedDebugAndroidTest         # UI tests pass (if device connected)
5. ./gradlew lint                              # No new warnings
6. git commit with conventional format
7. git push, raise PR with Closes #N
8. Parallel: code-reviewer reviews PR + CI runs Build & Test
9. Push any code review fixes to the PR branch
10. code-reviewer: re-review fix commits (scoped — not a full re-review)
11. ./gradlew installDebug                     # Deploy to S23 Ultra
12. Manual smoke test on device
13. Owner reviews and merges
```

### Before Raising a PR

1. `./gradlew test` passes (all unit tests)
2. `./gradlew lint` passes (no new warnings)
3. `./gradlew assembleDebug` succeeds (builds clean)
4. If UI changes: `./gradlew connectedDebugAndroidTest` passes
5. Manual smoke test on device for inference-related changes
6. Commit messages follow `type(#issue): description` format

### CI Notes

- CI cannot run real model inference (no GPU/NPU, models too large)
- CI runs: lint, unit tests, debug build
- Inference tests use mocked `InferenceEngine` — never download models in CI
- Compose UI tests run on CI via Android Emulator (API 35 system image)

## Benchmarking

- Maintain a `benchmarks/` folder with standard test queries
- Track Time-to-First-Token (TTFT) and tokens/sec across builds using Android Macrobenchmark
- Standard queries: simple device action, conversational question, RAG-augmented query

## Implementation Phases

1. ✅ Core LiteRT-LM integration with GPU/NPU acceleration for Gemma-4
2. ✅ sqlite-vec + EmbeddingGemma for local semantic search (RAG)
3. 🔄 Resident Agent Architecture: QuickIntentRouter (Tier 2) + E4B native tool calling (Tier 3) + Voice I/O — **top priority: #222 (baseline skills + rich UI), #223 (memory tools)**
4. Chicory Wasm Runtime + GitHub-based Skill Store
5. 8GB device optimization (dynamic weight loading/unloading)
