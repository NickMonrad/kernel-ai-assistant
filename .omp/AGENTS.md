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

Work inline by default when the request is one coherent feature, defect, review, or remediation task. Multi-file scope, expected duration, and generic complexity are not reasons to delegate.

The active agent owns issue interpretation, scope, architecture and design decisions, cross-component contracts, implementation sequencing, the integrated change, final validation, complete-diff review, and PR handoff.

| Agent | Optional capability |
|-------|---------------------|
| **coordinator** | Coordinates genuinely independent workstreams under an agreed shared contract |
| **android-developer** | Kotlin/Compose/Gradle, native skills, UI, plumbing |
| **llm-engineer** | LiteRT integration, model cascade, RAG, prompt engineering |
| **test-writer** | Focused JUnit 5 + MockK and Compose UI test work |
| **spec-writer** | README, specification, agent docs, and skill schemas |
| **code-reviewer** | Security, correctness, memory safety, and LiteRT anti-pattern review |
| **wasm-skill-author** | Rust → Wasm skills, Chicory bridge, Skill Store |

Delegate only when the user explicitly requests it, or when at least two genuinely independent workstreams can progress without first resolving a shared decision. Otherwise do the work directly.

Do not spawn agents merely to reinterpret, plan, research, test, document, or review the same coherent task. Read the issue first, identify concrete unknowns before research, and avoid nested delegation unless explicitly requested or required to clear a real blocker. The active agent defines any shared contract, integrates delegated results, and remains accountable for the final outcome.

### Task briefs

Include the exact objective and source issue/PR; only non-canonical repository context; explicit scope boundaries; observable behaviour and acceptance criteria; focused automated tests; realistic manual or physical-device validation; instructions to inspect current code and report deviations or genuine blockers; and `Do not merge — wait for review.`

Do not repeat this repository context, prescribe every file/tool/step, require a new planning phase after an accepted design, request competing architectures without an unresolved decision, create a fixed specialist pipeline, or add speculative work outside the issue.

### OMP delegation and recovery

When using OMP's `task` tool:
- omit `effort` unless the user explicitly requested per-task effort; importance alone does not justify a high-effort override;
- do not pass a per-call `model` field: the current task schema does not expose one;
- do not change agent definitions or `task.agentModelOverrides` for a task unless the user explicitly requests a child-model override; never pass a default-valued model selector to mean configured routing;
- let configured agent definitions, `task.agentModelOverrides`, and the `task` model role determine the child model and reasoning selector;
- keep handoffs compact and do not replay the parent transcript or canonical instructions;
- prefer the configured task/workhorse role for routine execution and use the resolved-model display for routing diagnosis when available;
- do not use nested delegation by default.

OMP isolated tasks normally integrate changes through automatic patch application or branch-mode cherry-pick. Review returned status and patch/branch metadata. Recover from a retained patch artifact or task branch if integration fails; request raw diff or full-file output only as a final fallback. See `.docs/agents/failure-handling.md`.

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

## rtk — output control

Use `rtk` for output-heavy builds, tests, lint, logs, diffs, searches, and JSON when supported so raw output does not consume the context. Common forms: `rtk test <cmd>`, `rtk lint <cmd>`, `rtk log <cmd>`, `rtk git status`, `rtk git diff`, `rtk grep <pattern>`, `rtk err <cmd>`, `rtk summary <cmd>`, and `rtk json <cmd>`. Do not wrap short or interactive commands where filtering provides no benefit.

## context-mode — routing

context-mode is installed globally (MCP tools + native OMP hooks). Follow `~/.omp/agent/SYSTEM.md` when available. Otherwise use `ctx_execute(language, code)` for count/filter/parse/transform analysis so only concise stdout enters context; do not read large raw data when code can summarise it.

## Branching & PR standards

**Review path:** Human/ChatGPT review plus repo evidence. Do not request GitHub Copilot Review — treat any Copilot-generated PR review as non-authoritative.

Default: `main`. Feature branches: `feature/<short-name>`. Branch from `main` only. PR body: `Closes #N`. Never auto-merge. After creating a PR, merge `main` into the branch if behind and check CI status.

**Commit:** `type(#issue): short description` — types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`.

End every implementation handoff with: **Do not merge — wait for review.**

## Testing strategy

- Unit: JUnit 5 + MockK (`src/test/`). Compose UI: `ui-test-junit4` (`src/androidTest/`)
- Never load real models in tests — mock at `InferenceEngine` interface
- CI: lint + unit tests + debug build only (no GPU/NPU, no real models)
- Coroutine tests: use `MainDispatcherRule` (replaces `Dispatchers.Main` with `TestDispatcher`) to avoid `IllegalStateException`. Use Turbine library for StateFlow/Flow assertion in tests.

### Test evidence workflow

Feature PRs let normal CI generate test evidence artifacts. Do not publish durable evidence unless explicitly instructed.

**For CI evidence:** Use the "Publish PR test evidence" workflow — requires only the PR number.
The workflow resolves the CI run, artifact, and commit SHA automatically.
See `.docs/agents/test-evidence-workflow.md` for the full flow and manual fallback.

> **⚠️ PR number, not issue number:** For evidence publishing, `--pr` is always the GitHub Pull Request number,
> not the issue number from `Closes #N`. Derive it mechanically: `gh pr view --json number --jq .number`.
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
No GitHub Copilot Review | Human/ChatGPT review plus repo evidence is the expected review path

## Agent memory

Consult `memory://root` before unfamiliar-module work when available. Store only durable, non-obvious repository knowledge such as module locations, build/debug quirks, architectural invariants, and high-value tool patterns. Do not duplicate information already present here or in an on-demand document.

Existing entries include: model_loading_order, test_patterns, branch_isolation, rtk_token_saver, adreno_buffer_workaround, github_api_pagination, meal_planner_state, documentation_sync, model_availability_state.

## On-demand reference docs

Load these only when relevant:

- `docs/agents/save-memory-routing.md` — memory routing table, normaliseSaveContent()
- `docs/agents/thinking-mode.md` — channel registration, CHANNEL_WRAPPER_RE, generateOnce()
- `docs/agents/skill-reference.md` — native and Wasm skill listings, Chicory bridge contract
- `docs/agents/issue-hygiene.md` — issue normalisation checklist
- `.docs/agents/debugging.md` — ADB commands, log filtering, common issues
- `.docs/agents/validation.md` — per-scope validation table, CI constraints (updated with risk tiers)
- `.docs/agents/decision-heuristics.md` — when multiple valid approaches exist
- `.docs/agents/failure-handling.md` — blockers, escalation, progress reporting, isolated-task recovery
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
