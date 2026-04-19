# Contributing to Jandal AI

Welcome! This document describes how development on this project works — branching, CI, documentation, labelling, and everything in between.

---

## Table of Contents

1. [Project Structure](#project-structure)
2. [Development Setup](#development-setup)
3. [Branch Strategy](#branch-strategy)
4. [Commit Messages](#commit-messages)
5. [Pull Request Workflow](#pull-request-workflow)
6. [CI / Build Checks](#ci--build-checks)
7. [Testing](#testing)
8. [Issue Lifecycle](#issue-lifecycle)
9. [Labels](#labels)
10. [Milestones](#milestones)
11. [Documentation](#documentation)
12. [Code Style](#code-style)

---

## Project Structure

Multi-module Gradle project. Each feature or concern lives in its own module:

```
app/                        → Application module, NavHost, Hilt entry point
core/
  inference/                → LiteRT-LM engine, model loading, JandalPersona, QIR
  memory/                   → Room DB, RAG pipeline, vector search (sqlite-vec NDK)
  skills/                   → Skill registry, SkillExecutor, tool call parsing
  data/                     → DataStore preferences, repositories
  ui/                       → Shared Compose components
feature/
  chat/                     → ConversationListScreen, ChatScreen, ChatViewModel
  settings/                 → SettingsScreen, ModelManagementScreen, MemoryScreen
  actions/                  → Quick Actions tab, ActionsViewModel, slot-fill state machine
  lists/                    → Lists CRUD UI and ViewModel
  alarms/                   → Alarms screen, AlarmViewModel
  contacts/                 → People & Contacts screen
docs/
  ROADMAP.md                → Living roadmap — keep in sync with issues
  SPECIFICATION.md          → Authoritative technical reference
scripts/                    → ADB test harness, device automation tools
```

---

## Development Setup

### Requirements

- Android Studio Meerkat or newer
- Android SDK 35 (Android 15) — min SDK
- JDK 17+
- A physical device with Snapdragon 8 Gen 2+ or equivalent for on-device testing (emulator cannot run LiteRT GPU inference)

### First run

```bash
git clone https://github.com/NickMonrad/kernel-ai-assistant.git
cd kernel-ai-assistant
./gradlew assembleDebug
```

First build compiles the NDK SQLite + sqlite-vec shared library (`libkernelvec.so`) which takes a few minutes. Subsequent builds are incremental.

### Tested Devices

| Device | Chip | RAM | Backend |
|--------|------|-----|---------|
| Samsung Galaxy S23 Ultra | Snapdragon 8 Gen 2 (SM8550) | 12 GB | NPU (Hexagon) |
| Google Pixel 10 | Tensor G5 | 12 GB | GPU |

---

## Branch Strategy

**`main` is protected.** Direct pushes are blocked. All changes go through a PR with a passing CI check.

### Naming convention

| Prefix | When to use |
|--------|-------------|
| `feat/NNN-short-description` | New feature tied to an issue |
| `fix/NNN-short-description` | Bug fix tied to an issue |
| `docs/short-description` | Documentation only — no code change |
| `refactor/short-description` | Refactor with no behaviour change |
| `chore/short-description` | Build, deps, tooling |

Examples:
```
feat/602-nav-restructure
fix/618-seed-memories-stale-flag
docs/triage-phase-labels
```

### Rules

- Branch off `main` — never off another feature branch
- One issue per branch where practical; complex epics may span one branch but should still be scoped
- Delete the branch after merge (`--delete-branch` flag on `gh pr merge`)

---

## Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
type(scope): short description (#issue-number)

Optional longer body if the change is non-obvious.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

**Types:** `feat`, `fix`, `docs`, `refactor`, `chore`, `test`, `perf`

**Scope:** optional; use the module name (e.g. `memory`, `qir`, `settings`, `chat`)

Examples:
```
feat(memory): add countBySource to CoreMemoryDao (#618)
fix(qir): re-seed kiwi truths when DB wiped — stale SharedPrefs flag (#618)
docs: update ROADMAP with 3G slot-fill sub-issues
```

Always include the `Co-authored-by` trailer for Copilot-assisted commits.

---

## Pull Request Workflow

### Steps

1. **Create branch** — from `main`, using the naming convention above
2. **Implement** — write code, tests, and update docs in the same PR where possible
3. **Build locally** before opening PR:
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. **Open PR** via `gh pr create --body-file` (use a file to avoid shell escaping issues with multi-line bodies)
5. **CI must pass** — "Build & Test" check is required; PRs without a green check cannot merge
6. **Code review** — run the Copilot code-reviewer agent on non-trivial PRs
7. **Address findings:**
   - **Major** (bugs, data loss risk, crashes, logic errors) → fix on the branch, request scoped re-review
   - **Minor** (cosmetic, trivial, style) → fix and go straight to merge
8. **Merge** — always squash merge:
   ```bash
   gh pr merge NNN --squash --admin --delete-branch
   ```

### PR Title Format

```
type(scope): short description (#issue-number)
```

Matches commit convention. The issue number in the title links the PR to the issue in GitHub's UI.

### PR Body Template

```markdown
## Summary
What this PR does and why.

## Changes
- Change 1
- Change 2

## Testing
- [ ] Builds clean (`./gradlew :app:assembleDebug`)
- [ ] Manual testing on device
- [ ] Unit tests pass

## Related issues
Closes #NNN
```

---

## CI / Build Checks

The **"Build & Test"** GitHub Actions workflow runs on every PR and push to `main`:

| Job | What it does |
|-----|-------------|
| `build` | `./gradlew assembleDebug` — full app build including NDK |
| `unit-test` | `./gradlew testDebugUnitTest` — all JVM unit tests |

**Required to merge:** the `Build & Test` check must be green. PRs with a failing build or failing unit tests cannot be merged.

### Running CI locally

```bash
# Full build
./gradlew assembleDebug

# Unit tests only (faster)
./gradlew testDebugUnitTest

# Specific module
./gradlew :core:memory:testDebugUnitTest
```

### NDK note

The NDK (`libkernelvec.so`) is compiled as part of `:core:memory`. If you change `CMakeLists.txt` or the NDK sources, allow extra build time.

---

## Testing

### Unit tests

- Location: `<module>/src/test/java/`
- Framework: JUnit 5 + MockK
- Reference examples: `core/skills/src/test/`, `core/memory/src/test/`

**What to test:** parsers, use cases, repositories, ViewModels, state machines, intent routing logic. Anything with branching logic that doesn't require Android framework.

**What not to test:** UI composition (Compose preview tests are not required), simple data classes, generated Room DAOs.

### On-device testing

Use the ADB test harness in `scripts/` for intent routing regression:

```bash
# Run full NL test suite via ADB
python3 scripts/adb_skill_test.py --device <serial>
```

See `docs/adb-testing.md` for full harness documentation.

### Writing tests for new features

- New intent patterns in `QuickIntentRouter` → add to the regex test suite
- New slot-fill paths → add `ActionsViewModelTest` cases
- New skill implementations → add `SkillExecutorTest` cases
- New memory operations → add DAO and repository tests

---

## Issue Lifecycle

1. **File** — open an issue with a clear title. Link to parent epic if applicable
2. **Triage** — add labels (phase, type, priority, size), set milestone
3. **Assign** — add `go:yes` when ready to implement; `go:needs-research` for spikes
4. **Branch** — create a `feat/NNN-` or `fix/NNN-` branch
5. **PR** — open PR, reference issue with `Closes #NNN`
6. **Merge** — PR merge auto-closes the issue
7. **Roadmap** — mark the issue done in `docs/ROADMAP.md` (same PR or follow-up `docs/` PR)

Sub-issues are tracked using GitHub's native sub-issue relationship. Parent epics use the `type:epic` label.

---

## Labels

### Phase

| Label | Meaning |
|-------|---------|
| `Phase 3` | Phase 3: Resident Agent + Native Skills |

### Priority

| Label | Meaning |
|-------|---------|
| `priority:p0` | Blocking release — fix immediately |
| `priority:p1` | This sprint |
| `priority:p2` | Next sprint |
| `priority:high` | Important, tackle soon (non-sprint) |
| `priority:medium` | Useful, schedule when reasonable |
| `priority:low` | Nice to have, no urgency |

### Size / Complexity

| Label | Effort |
|-------|--------|
| `size:XS` | < 2 hours — trivial change |
| `size:S` | ~half day — well-defined |
| `size:M` | 1–2 days — moderate complexity |
| `size:L` | 3–5 days — significant work |
| `size:XL` | Week+ — major/architectural |

### Type

| Label | Meaning |
|-------|---------|
| `type:feature` | New capability |
| `type:bug` | Something broken |
| `type:chore` | Maintenance, refactoring |
| `type:docs` | Documentation |
| `type:epic` | Parent issue decomposed into sub-issues |
| `type:spike` | Research — produces a plan, not code |

### Status / Routing

| Label | Meaning |
|-------|---------|
| `go:yes` | Ready to implement |
| `go:needs-research` | Needs investigation before implementing |
| `go:no` | Not pursuing |
| `roadmap` | Incorporated into `docs/ROADMAP.md` |
| `release:v0.x.x` | Targeted for a specific release |
| `release:backlog` | Not yet scheduled |

---

## Milestones

| Milestone | Status | Focus |
|-----------|--------|-------|
| Phase 3: Resident Agent + Native Skills | 🔄 Active | Skills, multi-turn dialog, memory, QIR improvements |
| Phase 4: Dreaming Engine | ⬜ Planned | Overnight distillation, semantic cache, self-healing identity |
| Phase 5: Wasm Runtime + Skill Store | ⬜ Planned | Community-extensible Wasm plugins |
| Phase 6: Device Optimisation | ⬜ Planned | 8GB RAM support, dynamic model loading |
| Tech Debt & Research | 🔄 Active | Spikes, cleanup, investigations |

Assign new issues to the relevant milestone when labelling. Issues with no target yet get `release:backlog`.

---

## Documentation

Three key docs — keep them in sync:

| File | Purpose | When to update |
|------|---------|----------------|
| `README.md` | Public-facing overview, feature list, roadmap summary | When shipping a significant new feature; move it from "Coming Soon" to "Delivered" |
| `docs/ROADMAP.md` | Technical living roadmap with sub-issue tables and design decisions | When an issue is filed (add to Ideas table), implemented (mark ✅), or phased (add to sub-section) |
| `docs/SPECIFICATION.md` | Authoritative architecture and behaviour reference | When architecture or system behaviour changes — update in the same PR as the code |

### README Delivered/Coming Soon

- **Delivered** — shipped features with a merged PR. Add when you merge.
- **Coming Soon** — issues with `go:yes` or `priority:p1/p2`. Include the issue number and phase tag.

---

## Code Style

- **Kotlin** — follow standard Kotlin idioms; no explicit style linter enforced but match surrounding code
- **Compose** — Material 3 components throughout; no custom theming layers
- **No commented-out code** — delete dead code rather than commenting it out
- **No TODOs in merged code** — convert to a GitHub issue instead
- **Imports** — no wildcard imports; organise with IDE auto-format before commit
- **Module boundaries** — `feature/` modules must not depend on each other; go through `core/` interfaces

