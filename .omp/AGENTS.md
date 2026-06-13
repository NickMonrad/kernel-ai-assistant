# Jandal AI Assistant — Agent Context

Android-native, local-first AI assistant. All inference on-device via LiteRT. Kotlin host; Wasm guest-only. **Repo:** `NickMonrad/kernel-ai-assistant` | **Min SDK:** 35 (Android 15)

## Architecture

```
User input → Tier 2: QuickIntentRouter (regex + MiniLM, <5ms, 20+ intents)
           ↘ no match → Tier 3: Gemma-4 E-4B/E-2B (GPU resident, TTFT ~2.3s)
```

**FunctionGemma-270M is deprecated.** Do not load at startup or wire new features to it.

## Model inventory

| Model | Role | Loading |
|-------|------|---------|
| Gemma-4 E-4B / E-2B | Reasoning + tool calling (~3.4GB) | Eager — E4B first |
| EmbeddingGemma-300M | 768-dim semantic RAG embeddings | Lazy on first RAG query |
| all-MiniLM-L6-v2 int8 | Zero-shot intent classifier (~15MB) | Lazy, null fallback |
| FunctionGemma-270M | ~~Intent router~~ **Deprecated** | Not loaded |

Batch fallback: NPU → GPU (Adreno 740) → CPU. E-4B and E-2B support thinking mode. `safeTokenCount()` nudges powers-of-2 down ~2.4% — Adreno reshape buffer bug.

## Memory

- **Short-term:** LiteRT KV cache. At 80% capacity → recursive summarisation into prompt, not truncation.
- **Long-term:** sqlite-vec + Room. `vec_distance_cosine()`, top 3-5 fragments prepended to system prompt. EmbeddingGemma 768-dim (256-dim on 8GB via Matryoshka).

## Module structure

| Module | Purpose |
|--------|---------|
| `:app` | Entry point, Hilt DI, navigation, splash |
| `:core:inference` | LiteRT-LM engine, model manager, HW tier detection |
| `:core:voice` | STT, TTS, voice mode, push-to-talk |
| `:core:memory` | sqlite-vec JNI, EmbeddingGemma, RAG pipeline |
| `:core:wasm` | Chicory Wasm host, bridge functions, resource limits |
| `:core:model-availability` | ModelAvailabilityState, StateBadge, ModelCard, GatedModelStatusRepo |
| `:core:ui` | Shared Compose components, Material 3 theme |
| `:core:skills` | SkillInterface, SkillRegistry, JSON schema gen |
| `:feature:chat` | Chat screen, conversation list, ChatViewModel |
| `:feature:settings` | Memory management, skill store, model info, persona |
| `:feature:widget` | Glance widget, VoiceCommandActivity, WidgetTextInputActivity |
| `:feature:convert` | Text conversion utilities |

## Key conventions

- No cloud inference — all inference through LiteRT, no network endpoints
- `gemma4InitMutex` guards all E4B init paths
- `tryExecuteToolCall()`: unknown skill or malformed JSON → plain text fallback, never crash
- Inference on dedicated LLM dispatcher — never `Dispatchers.Main`
- Explicit Intents only for SMS/email — never implicit
- Wasm sandboxing is non-negotiable — capabilities via Kotlin host bridge functions only
- Context window managed, not truncated — recursive summarisation at 80% KV capacity
- E4B loads eagerly first on GPU (~20s first boot, ~2s with kernel cache)
- Minimum touch target: 48×48dp. Color contrast: 4.5:1 for body text, 3:1 for large text (18sp+). 8dp spacing grid.

## Agent working model

| Agent | Role |
|-------|------|
| **coordinator** | Orchestrates; decomposes; routes; synthesises |
| **android-developer** | Kotlin/Compose/Gradle, native skills, UI, plumbing |
| **llm-engineer** | LiteRT integration, model cascade, RAG, prompt engineering |
| **test-writer** | JUnit 5 + MockK unit tests, Compose UI tests — interfaces only |
| **spec-writer** | README, specification.md, AGENTS.md, skill schemas |
| **code-reviewer** | Security, memory safety, LiteRT anti-patterns — mandatory before PR merge |
| **wasm-skill-author** | Rust → Wasm skills, Chicory bridge, Skill Store |

**Workflow:** Analyse → dispatch (android-developer / llm-engineer) → parallel test-writer + spec-writer → PR with `Closes #N` → parallel code-reviewer + CI → push fixes → owner tests via ADB → owner merges.

### Subagent code changes — recovery pattern

Task agents run in **ephemeral, isolated worktrees** that are cleaned up on completion.
Their file writes never reach your worktree. To extract their changes, use one of:

**Option A — diff output (preferred):** Add this to the end of every code-changing assignment:
```
LAST STEP — output your changes as a patch:
1. Run `git diff` (do NOT omit this step).
2. Copy the ENTIRE diff output into your final message verbatim,
   wrapped in a ```diff code block.
Do NOT summarise your changes — I need the raw diff to `git apply`.
```
Then apply in your worktree: pipe the diff block into `git apply`.

**Option B — raw file content:** Instruct the agent to `cat` each modified file.
The artifact output will contain the full content; copy it with `write`.

**Option C — GitHub push:** For larger changes, tell the agent to `git push` its branch,
then `git fetch` + `git merge` from your worktree.

**Never** assume a `task` agent's file modifications are visible in your worktree.

## Branch isolation

**Do not modify the main checkout directly.** Every session that touches code must use a dedicated worktree:

```bash
git worktree add ~/.omp/wt/<task-name> <branch>
```

Before starting: `git branch --show-current` to verify you're on the expected branch. Check `git worktree list` before creating a new one — avoid duplicating an existing branch checkout.

Rationale: 50+ worktrees across sessions/agents exist in this repo. Direct checkout modifications cause conflicts with concurrent agent work.

## Build commands

```bash
./gradlew assembleDebug / installDebug / test / testDebugUnitTest / :core:inference:test / lint
adb logcat -s KernelAI
connectedDebugAndroidTest   # requires device
```

## rtk — MUST use for output-heavy commands

`rtk` filters tool output before it enters context. Every build, test, lint, log, or diff command producing >5 lines MUST use `rtk`.

| Instead of | Use | Saves |
|------------|-----|-------|
| `./gradlew test` | `rtk test ./gradlew test` | ~70% (failures only) |
| `./gradlew :core:inference:test` | `rtk test ./gradlew :core:inference:test` | ~70% |
| `./gradlew lint` | `rtk lint ./gradlew lint` | ~60% (grouped) |
| `./gradlew assembleDebug / installDebug` | `rtk cargo ./gradlew ...` | ~50% |
| `adb logcat -s KernelAI` | `rtk log adb logcat -s KernelAI` | ~70% (dedup) |
| `git status / diff` | `rtk git status / rtk git diff` | ~50% |
| `npx vitest run <path>` | `rtk test npx vitest run <path>` | ~70% |
| `grep -r <pattern>` | `rtk grep <pattern>` | ~50% |
| Only errors needed | `rtk err <cmd>` | ~90% |
| Quick summary | `rtk summary <cmd>` | ~80% |
| Any diff output | `rtk diff <cmd>` | ~60% |
| Raw JSON output | `rtk json <cmd>` | Schema/compact |

**Standalone:** `rtk gain` shows token savings. `rtk env` shows filtered env vars. **Hard rule:** If output is >5 lines, use `rtk`.

## context-mode — routing

context-mode is installed globally (MCP tools + native OMP hooks). Full routing rules at `~/.omp/agent/SYSTEM.md` (if available).

**Fallback (no SYSTEM.md):** Use `ctx_execute(language, code)` for count/filter/parse/transform analysis. Only stdout enters context — keep analysis in code, not raw data. A one-liner replaces 10+ `read`/`bash` calls. Avoid reading large data into context when code can summarise it.

## Branching & PR standards

Default: `main`. Feature branches: `feature/<short-name>`. Branch from `main` only. PR body: `Closes #N`. Never auto-merge. After creating a PR, merge `main` into the branch if behind and check CI status.

**Commit:** `type(#issue): short description` — types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`.

## Testing strategy

- Unit: JUnit 5 + MockK (`src/test/`). Compose UI: `ui-test-junit4` (`src/androidTest/`)
- Never load real models in tests — mock at `InferenceEngine` interface
- CI: lint + unit tests + debug build only (no GPU/NPU, no real models)
- Coroutine tests: use `MainDispatcherRule` (replaces `Dispatchers.Main` with `TestDispatcher`) to avoid `IllegalStateException`. Use Turbine library for StateFlow/Flow assertion in tests.

### Test evidence workflow

Feature PRs let normal CI generate test evidence artifacts. Do not publish durable evidence unless explicitly instructed.

**For CI evidence:** Use the "Publish PR test evidence" workflow — requires only the PR number.
The workflow resolves the CI run, artifact, and commit SHA automatically.
See \`.docs/agents/test-evidence-workflow.md\` for the full flow and manual fallback.

> **⚠️ PR number, not issue number:** For evidence publishing, \`--pr\` is always the GitHub Pull Request number,
> not the issue number from \`Closes #N\`. Derive it mechanically: \`gh pr view --json number --jq .number\`.
> Publishing under the wrong number misroutes dashboard evidence to the wrong PR.

**For on-device evidence:** Requires a physical device run. Do not imply on-device evidence is covered by CI.
If on-device testing is needed, ask which device tier is required (S21 tracked signal or S23U reference signal)
and whether the results should be published to `test-results`.

Details: `.docs/agents/test-evidence-workflow.md`.

## Working style

- Search before reading; read surgically and minimally
- Use `lsp` for all code intelligence — not grep
- Reuse existing context; prefer targeted validation
- Prefer small, reviewable diffs; match surrounding style


## Performance targets

| Metric | Target | Max / Threshold |
|--------|--------|----------------|
| Cold start | < 1s | 2s (show progress beyond this) |
| Frame time | ≤ 16ms (60 FPS) | 700ms frozen frame = critical |
| Crash rate | < 1.09% | Google Play Vitals threshold |
| ANR rate | < 0.47% | Google Play Vitals threshold |
| Jank rate | < 1% of frames | Warning level |

## Hard constraints

No external LLM APIs | No cloud inference endpoints | No implicit Intents for SMS/email | No `Dispatchers.Main` for inference | No FunctionGemma at startup | No generic `fetch()` in Wasm skills | No concurrent E4B init (hold `gemma4InitMutex`) | No context truncation | No broad formatting-only diffs

## Memory

Write to memory (`memory://root/skills/<name>/SKILL.md`) after discovering:
- Non-obvious file locations or module boundaries
- Build/debug quirks (tool flags, adb incantations, test setup)
- Architectural invariants that caused a bug (e.g. "gemma4InitMutex required")
- Tool invocation patterns that save tokens (rtk, context-mode)
Consult memory via `memory://root` before starting work in an unfamiliar module.
Existing entries: model_loading_order, test_patterns, branch_isolation, rtk_token_saver, adreno_buffer_workaround, github_api_pagination, meal_planner_state, documentation_sync, model_availability_state.

## On-demand reference docs

Load these only when relevant:

- `docs/agents/save-memory-routing.md` — memory routing table, normaliseSaveContent()
- `docs/agents/thinking-mode.md` — channel registration, CHANNEL_WRAPPER_RE, generateOnce()
- `docs/agents/skill-reference.md` — native and Wasm skill listings, Chicory bridge contract
- `docs/agents/issue-hygiene.md` — issue normalisation checklist
- `.docs/agents/debugging.md` — ADB commands, log filtering, common issues
- `.docs/agents/validation.md` — per-scope validation table, CI constraints (updated with risk tiers)
- `.docs/agents/decision-heuristics.md` — when multiple valid approaches exist
- `.docs/agents/failure-handling.md` — blockers, escalation, progress reporting
- `.docs/agents/repo-map.md` — key file index by area
- `docs/UX_PATTERNS.md` — canonical UI/UX patterns (read before any new screen)
- `.docs/agents/test-evidence-workflow.md` — test evidence lifecycle, CI vs on-device, publishing workflow, agent guidance
- `docs/model-availability-ux-patterns.md` — model-facing screen patterns
- `.docs/agents/risk-based-evidence-policy.md` — risk tiers (low/medium/high), device guidance, evidence requirements
- `.docs/agents/evidence-manifest.md` — evidence manifest format for PR bodies
- `.docs/agents/review-checklist.md` — code review checklist
- `.docs/agents/review-gates-voice.md` — voice PR review gate (STT/TTS/VAD/wake-word)
- `.docs/agents/review-gates-litert.md` — LiteRT/model PR review gate
- `.docs/agents/review-gates-permissions.md` — permissions PR review gate
- `.docs/agents/review-gates-test-harness.md` — test harness PR review gate
- `.docs/agents/review-gates-navigation-ui.md` — navigation/UI PR review gate
- `.docs/agents/review-gates-wallpaper-theme.md` — wallpaper/theme PR review gate

**Phase status:** see `docs/ROADMAP.md` for the full tracker.
