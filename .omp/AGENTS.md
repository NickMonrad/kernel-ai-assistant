# Kernel AI Assistant — Agent Context

Android-native, local-first AI assistant. All inference on-device via LiteRT. Kotlin host; Wasm guest-only.

**Repo:** `NickMonrad/kernel-ai-assistant` | **Test device:** Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2, 12GB RAM, Android 16 / One UI 8.0) | **Min SDK:** 35 (Android 15)

## Architecture — Three-Tier Brain

```
User input
    │
    ▼
┌─────────────────────────────┐
│  Tier 2: QuickIntentRouter  │  Pure Kotlin regex + MiniLM classifier, ~0MB, <5ms
│  20+ intents: alarms/timers,│  Phases 1A/1B/2 merged (MiniLM classifier active).
│  media, torch, DND, nav…   │
└────────┬────────────────────┘
         │ no match
         ▼
┌─────────────────────────────┐
│  Tier 3: Gemma-4 E-4B/E-2B │  Resident on GPU, TTFT ~2.3s
│  Native JSON tool calling   │  Complex NLU + tool calling via SkillExecutor
│  + RAG memory context       │  Confirmed working: weather, save_memory, get_system_info
└─────────────────────────────┘
```

**FunctionGemma-270M is deprecated.** Do not load at startup or wire new features to it.

### `save_memory` routing (fix #937, commit `480fafcd`) — do not add patterns without checking

| Input | Tier | Reason |
|-------|------|--------|
| `save/store/keep [to/in memory [that] \| that] <content>` | **Tier 2** | Direct intercept |
| `save/store/keep this/it …` | **Tier 3** | Anaphoric — needs LLM context |
| `remember [that] <content>` — not starting with this/that/it | **Tier 2** | Direct intercept; first-person normalised by `normaliseSaveContent()` |
| `remember that this/that/it …` | **Tier 3** | True anaphoric — needs LLM context |

`normaliseSaveContent()` handles full first-person conjugation: `I'm`/`I am` → `Name is`, `I have` → `Name has`, `I prefer/like/…` → conjugated third-person, bare `I` → `Name` (catch-all), `my` → `Name's`. Applied on both Tier 2 and Tier 3 code paths.

### Model inventory

| Model | Role | Loading |
|-------|------|---------|
| Gemma-4 E-4B (Performance) / E-2B (Compat) | Resident reasoning + tool calling (~3.4GB) | Eager — E4B first, full GPU headroom |
| EmbeddingGemma-300M | Semantic embeddings, 768-dim RAG | Lazy on first RAG-triggering query |
| all-MiniLM-L6-v2 int8 | Zero-shot intent classifier (~15MB) | Lazy, graceful null fallback |
| FunctionGemma-270M | ~~Intent router~~ **Deprecated** | Not loaded; class retained pending #358 |

- Quantized weights (INT4/INT8) via LiteRT. Backend fallback: NPU → GPU (OpenCL/Adreno 740) → CPU.
- `safeTokenCount()`: nudge powers-of-2 down ~2.4% (4096→4000, 8192→8000) — Adreno reshape buffer bug.
- Both E-4B and E-2B support thinking mode.

### Thinking mode — two requirements, both needed

1. `Channel("thought", "<|think|>", "<|/think|>")` registered in `ConversationConfig.channels`
2. `extraContext = mapOf("enable_thinking" to true)` in `sendMessageAsync`

Either alone produces zero chain-of-thought. Strip channel wrapper from stream: `message.toString()` includes `<|channel>thought\n...\n<channel|>` — strip via `CHANNEL_WRAPPER_RE` in `LiteRtInferenceEngine.generate()`.

**Background `generateOnce()` calls (title gen, episodic distillation, profile extraction) must explicitly pass `thinkingEnabled = false`.**

### Memory

- **Short-term:** LiteRT KV Cache — 4,000 tokens (Performance) / 2,000 (Compat). At 80% capacity → recursive summarisation injected back into prompt, not truncation.
- **Long-term:** sqlite-vec (NDK arm64-v8a) + Room. Every query → `vec_distance_cosine()`, top 3–5 fragments prepended to system prompt. EmbeddingGemma 768-dim (256-dim on 8GB via Matryoshka).

### Skills

- **Native (Kotlin/JVM):** flashlight, DND, Bluetooth, alarm/timer, email (`ACTION_SEND`), SMS (`SEND_SMS`), notes (Room), media (MediaSession via NotificationListenerService)
- **Wasm (Chicory, pure JVM v1.0+):** sandboxed — no direct OS access. JSON via shared linear memory. 5s wall-clock timeout, 16MB memory cap, 1MB output limit. HTTP via domain-scoped bridge functions with URL allowlist — never a generic `fetch()`.

Contract-first: define `SkillSchema` JSON schema before logic. Version bump in manifest on every change. Schema injected via `SkillRegistry.buildFunctionDeclarationsJson()`.

## Module structure

| Module | Purpose |
|--------|---------|
| `:app` | Entry point, Hilt DI, navigation, splash |
| `:core:inference` | LiteRT-LM engine wrapper, model manager, hardware tier detection |
| `:core:voice` | STT, TTS, voice mode, push-to-talk |
| `:core:memory` | sqlite-vec JNI, EmbeddingGemma, RAG pipeline |
| `:core:wasm` | Chicory Wasm host, bridge functions, resource limiting |
| `:core:ui` | Shared Compose components, Material 3 theme |
| `:core:skills` | SkillInterface, SkillRegistry, JSON schema generation |
| `:feature:chat` | Chat screen, conversation list, ChatViewModel |
| `:feature:settings` | Memory management, skill store, model info, persona config |
| `:feature:onboarding` | ~~First-launch model download~~ (directory only, not a Gradle module) |
| `:feature:widget` | Glance homescreen widget, VoiceCommandActivity, WidgetTextInputActivity |
| `:feature:convert` | Text conversion utilities |

## Key conventions

- **Kotlin is the host.** Wasm is guest-only; never receives direct OS access.
- **No cloud inference.** All inference through LiteRT — no network calls to external endpoints.
- **`gemma4InitMutex`** guards all E4B init paths — both `initEngineWhenReady()` and `initGemma4()` must hold this lock.
- **`tryExecuteToolCall()`** in `ChatViewModel`: unknown skill or malformed JSON → plain text fallback, never crash.
- **All inference on dedicated LLM dispatcher** — never `Dispatchers.Main`.
- **Explicit Intents only** for SMS/email — never implicit intents.
- **Wasm sandboxing is non-negotiable.** All capabilities via explicit Kotlin host bridge functions only.
- **`LeakCanary`** from day one — catch model weight leaks after conversation close.
- **Verify quantization** via LiteRT Metadata Extractor before assuming OOM is a memory issue.
- **Context window managed, not truncated.** Recursive summarisation at 80% KV capacity.
- **E4B loads eagerly first on GPU** (~20s first boot, ~2s with kernel cache).

## UI/UX

Jetpack Compose + Material 3 Dynamic Color, dark/AMOLED default. Conversations list as home. `KernelDatabase` v48. `ConversationEntity` carries `archivedAt`, `pinned`, `sortOrder`; `observeActive` orders by `pinned DESC, sort_order ASC, updated_at DESC`. Archived conversations are read-only. `ArchiveCleanupWorker` runs daily (default 7-day retention, `ChatPreferences` DataStore).

Voice: push-to-talk + streaming, auto-stop on silence. Per-message TTS (`VolumeUp`) on every assistant bubble. Verbal stop commands ("stop", "cancel", "be quiet", "shut up", "silence") cancel TTS mid-stream. `truncateForSpeech()` uses `KNOWN_ABBREV` + `INITIALS_REGEX`. Sherpa TTS pitch slider (0.5–2.0×), `autoSpeakEnabled` toggle.

Widget: `androidx.glance` in `:feature:widget`. Text pill → `WidgetTextInputActivity`; mic → `VoiceCommandActivity`. Both fire explicit intent to `MainActivity` (`quick_action_input` + `quick_action_is_voice`). `savedStateHandle` boolean `widgetQueryConsumed` prevents re-execution on recompose.

## Agent working model

| Agent | Role |
|-------|------|
| **coordinator** | Orchestrates; decomposes tasks; routes to specialists; synthesises results |
| **android-developer** | Kotlin/Compose/Gradle, native skills, UI, app plumbing |
| **llm-engineer** | LiteRT integration, model cascade, RAG pipeline, prompt engineering |
| **test-writer** | JUnit 5 + MockK unit tests, Compose UI tests — **works from interfaces only, never sees implementation** |
| **spec-writer** | README, specification.md, `.omp/AGENTS.md`, skill schemas |
| **code-reviewer** | Security, memory safety, LiteRT anti-patterns, correctness — **mandatory before every PR merge** |
| **wasm-skill-author** | Rust → Wasm skills, Chicory bridge, Skill Store (Phase 4+) |

### Workflow

1. Analyse issue, explore codebase, form plan
2. Dispatch: `android-developer` or `llm-engineer` (implementation)
3. Parallel: `test-writer` (interfaces only) + `spec-writer` (docs if needed)
4. Raise PR with `Closes #N`
5. Parallel: `code-reviewer` reviews PR + CI runs
6. Push any fixes; `code-reviewer` re-reviews fix commits (scoped — not a full re-review)
7. Tell owner to manually test on S23 Ultra via ADB once CI passes; owner merges

## Branching & PR standards

- Default branch: `main`. Feature branches: `feature/<short-name>`. Always branch from `main`, never chain branches.
- PR body must include `Closes #N`. Never auto-merge — owner reviews and merges.
- After creating a PR, merge `main` into the branch if it's behind. Check CI status and report pass/fail.

## Commit format

```
type(#issue): short description
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`

## Build commands

```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew test
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # requires connected device
./gradlew lint
./gradlew :core:inference:test        # single module
adb logcat -s KernelAI
```

## Testing strategy

- Unit tests: JUnit 5 + MockK (`src/test/`)
- Compose UI tests: `androidx.compose.ui:ui-test-junit4` (`src/androidTest/`)
- **Never load real models in tests** — mock at `InferenceEngine` interface
- CI: lint + unit tests + debug build only (no GPU/NPU, no real models)
- Compose UI tests run on CI via Android Emulator (API 35 system image)

## Working style

- Search before reading; read surgically and minimally
- Note current branch at session start (`git branch --show-current`); stay on it
- Use `lsp` for all code intelligence — definitions, references, hover, rename, diagnostics — not grep
- Reuse already-discovered context; prefer targeted validation
- Prefer small, reviewable diffs; match surrounding code style
- Avoid unrelated refactors alongside functional changes

## Hard constraints

- No external LLM APIs — all inference through LiteRT
- No cloud inference endpoints anywhere in the codebase
- No implicit Intents for SMS/email
- No `Dispatchers.Main` for inference
- No FunctionGemma at startup or wired to new features
- No generic `fetch()` in Wasm skills
- No concurrent E4B init (hold `gemma4InitMutex`)
- No context truncation — recursive summarisation only
- No migrating core Kotlin architecture to another language
- No broad formatting-only diffs; no rewrites for style preferences

## GitHub issue hygiene

Normalise at creation: type, go-state, priority, size, milestone/phase, roadmap label, domain labels. Parent/epic for multi-track work; decompose into child issues. Use `go:needs-research` when architecture is open.

## On-demand reference docs

Load these only when relevant:

- `.docs/agents/debugging.md` — ADB commands, log filtering, common issues
- `.docs/agents/validation.md` — per-scope validation table, CI constraints
- `.docs/agents/decision-heuristics.md` — when multiple valid approaches exist
- `.docs/agents/failure-handling.md` — blockers, escalation, progress reporting
- `.docs/agents/repo-map.md` — key file index by area

## Phase status

1. ✅ LiteRT-LM integration with GPU/NPU acceleration (Gemma-4)
2. ✅ sqlite-vec + EmbeddingGemma RAG
3. 🔄 Resident Agent Architecture
   - ✅ Phase 1A: QuickIntentRouter + 20 regex intents (#354)
   - ✅ Phase 1B: NativeIntentHandler + 23 handlers, 130+ tests (#357)
   - ✅ Homescreen Glance widget (#617, #847)
   - ✅ Phase 2: MiniLM zero-shot classifier (#362)
   - ⬜ Phase 3: FunctionGemma cleanup (#358)
   - ⬜ Phase 4: Chat hybrid mode — Tier 2 intercept in conversations (#360)
   - ⬜ Phase 5: Actions tab re-enable + dual FABs (#361)
   - ⬜ Model download UX overhaul (#363)
4. ⬜ Chicory Wasm Runtime + GitHub-based Skill Store
5. ⬜ 8GB device optimization (dynamic weight loading/unloading)
