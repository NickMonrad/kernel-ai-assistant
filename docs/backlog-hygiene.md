# Backlog Hygiene & Triage Policy

> **Status:** Active policy (2026-06-15)  
> **Related:** [`docs/agents/issue-hygiene.md`](agents/issue-hygiene.md) (agent-facing issue conventions)  
> **Related:** #1251 (implementation issue)

## Purpose

Keep the Jandal AI backlog trustworthy for agent-driven development. Agents rely on issue metadata (labels, milestone, parent epic, acceptance criteria) to find work, understand scope, and produce reviewable evidence. Unlabelled or stale issues waste agent turns and produce unreliable launch planning.

## Required metadata

Every open issue **must** have:

| Field | Values | Notes |
|-------|--------|-------|
| **Type label** | `type:epic`, `type:feature`, `type:bug`, `type:chore`, `type:spike`, `type:performance` | Exactly one. |
| **Size label** | `size:XS`, `size:S`, `size:M`, `size:L`, `size:XL` | Exactly one. |
| **Priority label** | `priority:high`, `priority:medium`, `priority:low` | Exactly one. Never duplicate. |
| **Launch-status label** | `launch:blocking`, `launch:post`, `launch:deferred` | Exactly one for launch-track issues. Epics and long-term roadmap items may omit. |
| **Domain label(s)** | `UX`, `ui`, `voice`, `wake-word`, `skills`, `lists`, `meal-planning`, `memory`, `model-management`, `testing`, `technical-debt`, `research`, `optimisation` | One or more. Match the actual affected area. |
| **Milestone** | Current or next planned milestone | Required unless intentionally parked with an explicit comment explaining why. |
| **Parent epic** | `#NNN` in the issue body | Required when the issue belongs to a multi-issue workstream. Epic issues reference #1014 or the launch plan issue #1255. |

### When to create a spike vs implementation issue

- **Spike** (`type:spike`): architecture is uncertain, multiple approaches exist, or research is needed before implementation can be estimated. A spike produces a comment or child issue recommending an approach, not production code.
- **Implementation** (`type:feature`, `type:bug`, `type:chore`): the approach is understood. Acceptance criteria and test expectations should be clear enough for an agent to implement.

## Issue template checklist

When creating an issue, include in the body:

- [ ] Type label (exactly one)
- [ ] Size label (exactly one)
- [ ] Priority label (exactly one)
- [ ] Launch-status label (one, if on launch track)
- [ ] Domain label(s) (one or more)
- [ ] Parent epic reference (if applicable)
- [ ] Milestone assignment
- [ ] Acceptance criteria (bullet list of observable outcomes)
- [ ] Testing expectations and device requirements (S21 default, S23U only where explicitly needed)
- [ ] Evidence expectations (logs, screenshots, ADB evidence paths)

See `.github/ISSUE_TEMPLATE/agent-implementation.md` for the canonical template.

## Triage cadence

| Frequency | Activity |
|-----------|----------|
| **Weekly** (or before agent handoff) | Run the missing‑label and missing‑milestone saved searches. Fix any issues found. Run the metadata audit script. |
| **Before agent assignment** | Verify the issue has: parent epic, labels, milestone, acceptance criteria, and test expectations. Add a comment if anything is missing — do not let the agent discover it mid‑turn. |
| **After PR merge** | Update or close the linked issue. Update the parent epic's child checklist. If the child closes an epic, close the epic too. |
| **Monthly** | Review `launch:deferred` and old `priority:low` items for closure, consolidation, or promotion to active work. Run the audit script. |

## Saved searches

Create these GitHub saved searches for the repository:

| Search | Purpose |
|--------|---------|
| `repo:NickMonrad/kernel-ai-assistant is:issue is:open no:label` | Issues with no labels |
| `repo:NickMonrad/kernel-ai-assistant is:issue is:open no:milestone` | Issues with no milestone |
| `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:launch:blocking` | Current launch blockers |
| `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:type:epic` | All active epics |
| `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:priority:high label:launch:post` | High-priority post-launch work |
| `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:launch:deferred` | Deferred / dream backlog |
| `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:testing` | Testing and test-infrastructure issues |
| `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:technical-debt` | Technical debt items |

## Dashboard sections (GitHub Project or saved-search groups)

1. **Launch blockers** — `label:launch:blocking`, sorted by priority.
2. **High-priority post-launch** — `priority:high` + `launch:post`.
3. **Ready for agent** — epics with clear next-child issues, labelled, milestone set, acceptance criteria present.
4. **Needs design / spike** — `type:spike` or issues without acceptance criteria.
5. **Missing metadata** — issues missing labels, milestone, or parent epic.
6. **Deferred / dream backlog** — `launch:deferred`.
7. **Epics and parent streams** — `type:epic`, showing child progress.

## Audit script

A local audit script is available at `scripts/audit_backlog.py`. Run it to check open issues for:

- Missing labels
- Missing milestone
- Duplicate priority labels (e.g. both `priority:medium` and `priority:high`)
- Duplicate launch-status labels
- `launch:blocking` issues that may be stale (no activity in 60+ days and no recent PR)
- Issues without a parent epic where one is expected (type:feature, type:bug, type:chore at size M+)

Usage:

```bash
python3 scripts/audit_backlog.py
python3 scripts/audit_backlog.py --owner NickMonrad --repo kernel-ai-assistant
python3 scripts/audit_backlog.py --json > audit-report.json
```

See the script header for full options.

## Weekly triage checklist

- [ ] Run `no:label` saved search — fix any found.
- [ ] Run `no:milestone` saved search — assign milestone or add parked comment.
- [ ] Run `scripts/audit_backlog.py` — address findings.
- [ ] Check `label:launch:blocking` for stale items — reclassify or comment.
- [ ] Verify that issues assigned for agent implementation this week have clear acceptance criteria and test expectations.

## Reclassification rules

When reclassifying an issue's launch status:

- **Keep `launch:blocking`** if the issue affects first-run reliability, Store compliance, model readiness, permission UX, or any launch claim.
- **Move to `launch:post`** if the issue is valuable but not launch-critical. It should not block release.
- **Move to `launch:deferred`** if the issue is a dream feature, depends on unavailable technology, or has no clear implementation path within the next two milestones.
- **Close as `not_planned`** if the issue is superseded, a duplicate, or no longer relevant.
- **If uncertain**, leave the current label and add a comment explaining what decision is needed and who should make it.

## Device validation expectations

- **S21 (SM-G991B)** is the default device for permission, QIR, test-harness, and model-readiness work.
- **S23U** is reserved for high-risk model/runtime/voice comparison or where explicitly required by the issue.
- Document the device used and Android version in every PR that includes device evidence.
