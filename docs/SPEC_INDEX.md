# Spec index

> **Purpose:** Use this index to find the authoritative design and specification source before changing behaviour. GitHub issues track work and decisions; repo specs define durable product, technical, and evidence contracts.

---

## How to use this index

Before medium-risk or high-risk work, read the relevant authoritative spec first. If no authoritative spec exists for the changed behaviour, either create one in the same PR or call out the gap in the issue and PR.

A PR must update the relevant repo spec when it changes any of the following:

- user-visible behaviour;
- lifecycle behaviour;
- permission flow;
- navigation behaviour;
- model availability state or wording;
- evidence schema;
- test harness semantics;
- architectural constraints.

Small docs-only, copy-only, test-only, and refactor-only changes do not need a spec update unless they change the contract above.

---

## Spec categories

### Authoritative specs

These documents are source-of-truth contracts. Agents and implementations must follow them unless the PR explicitly updates the spec.

| Path | Scope | Notes |
|---|---|---|
| `docs/SPECIFICATION.md` | Technical architecture | Local-first architecture, module boundaries, inference, memory, prompt assembly, tools, and runtime behaviour. |
| `docs/UX_PATTERNS.md` | App-wide UX patterns | Navigation, Tools, Learn, settings access, examples, and confirmation rules. |
| `docs/model-availability-ux-patterns.md` | Model availability UX | Model discovery, access, lifecycle states, copy, and provider flows. |

### Subsystem behaviour specs

These documents define one behaviour area in more detail than the global specs.

| Path | Scope | Status |
|---|---|---|
| `docs/specs/permissions-ux.md` | Android permissions, microphone, wake word, repair flows | Canonical source for permission UX and lifecycle behaviour. |

Create new subsystem specs under `docs/specs/` when a behaviour area is too detailed for `docs/SPECIFICATION.md` or `docs/UX_PATTERNS.md`.

### Operational guides

These documents explain how to run, inspect, or verify something. They should point to authoritative specs when they depend on product behaviour.

| Path | Scope |
|---|---|
| `docs/automated-testing.md` | Current automated testing commands, report shapes, and harness operations. |
| `docs/adb-testing.md` | ADB setup, device setup, logcat filters, and manual device commands. |
| `.docs/agents/test-evidence-workflow.md` | Evidence publishing workflow for agents. |
| `.docs/agents/validation.md` | Validation hierarchy and CI constraints. |
| `.docs/agents/debugging.md` | Debugging commands and operational tips. |

### Review gates

Review gates define evidence and review expectations for PRs. They are not product behaviour specs unless they explicitly link to a subsystem spec.

| Path | Scope |
|---|---|
| `.docs/agents/review-gates-permissions.md` | Permission PR evidence and review checklist. |
| `.docs/agents/review-gates-test-harness.md` | Test harness and evidence PR checklist. |
| `.docs/agents/review-gates-navigation-ui.md` | Navigation and UI PR checklist. |
| `.docs/agents/review-gates-voice.md` | Voice, STT, TTS, VAD, and wake-word review gate. |
| `.docs/agents/review-gates-litert.md` | LiteRT and model-runtime review gate. |
| `.docs/agents/review-gates-wallpaper-theme.md` | Wallpaper and theme review gate. |

### Research and design drafts

Research docs are useful context. Treat them as draft or historical unless they say they are authoritative, are linked from this index as authoritative, or are promoted by an issue or PR.

| Path | Scope |
|---|---|
| `docs/research/` | Technical research, design exploration, and feature-specific draft specs. |
| `docs/testing/automated-test-specification.md` | Deeper test design and target coverage. Some sections are implemented; some remain draft. |
| `docs/testing/harness-metrics-dashboard-design.md` | Dashboard design and staged follow-up work. |

### Evidence schemas

Evidence schemas are contracts for generated artifacts. Update schema docs and compatibility rules in the same PR when generated evidence changes.

| Path | Scope |
|---|---|
| `docs/testing/test-evidence-schema.md` | Normalised evidence schema for CI, physical device runs, dashboards, and PR summaries. |

### Roadmap and status docs

Roadmap documents track delivery state and sequencing. They do not replace authoritative specs.

| Path | Scope |
|---|---|
| `docs/ROADMAP.md` | Product roadmap, phases, status, and sequencing. |
| GitHub issues | Work tracking, priority, acceptance criteria, discussion, and follow-up decisions. |

---

## GitHub issue, spec, and PR responsibilities

| Artifact | Responsibility |
|---|---|
| GitHub issue | Tracks the work, priority, acceptance criteria, discussion, screenshots, and links to specs. |
| Repo spec | Defines durable behaviour, architecture, lifecycle, UX, or evidence contract. |
| PR | Implements the change and updates specs when the contract changes. |
| Review gate | Defines the minimum evidence and review checklist for the subsystem touched by the PR. |

Avoid duplicating full designs in issue bodies when a repo spec exists. Link the spec from the issue and summarise only the change needed.

---

## Promotion rule for drafts

A draft or research document becomes authoritative only when a PR does one of the following:

- moves the relevant content into an authoritative spec;
- links the document from this index as authoritative;
- adds an explicit status block saying the document is authoritative for a defined scope.

Until then, agents should treat drafts as context, not as binding implementation contracts.

---

## OpenSpec note

Do not migrate the repo to OpenSpec folders yet. The current approach is repo-native: clear spec categories, explicit update rules, and PRs that include spec deltas when behaviour changes. Revisit an OpenSpec-style structure only if this lightweight process does not reduce agent drift enough.
