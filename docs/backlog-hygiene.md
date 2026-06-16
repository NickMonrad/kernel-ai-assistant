# Backlog hygiene and triage policy

> **Status:** Active policy
> **Related:** #1251 and launch plan #1255

This policy keeps the Jandal AI backlog trustworthy for agent-driven development without adding heavy project-management ceremony. Agents rely on issue metadata, milestones, parent epics, acceptance criteria, and test evidence expectations to pick the right work and produce reviewable changes.

## Required issue metadata

Every open issue should have exactly one label from each required category:

| Category | Allowed labels | Notes |
| --- | --- | --- |
| Type | `type:epic`, `type:feature`, `type:bug`, `type:chore`, `type:spike`, `type:performance` | Exactly one. |
| Size | `size:XS`, `size:S`, `size:M`, `size:L`, `size:XL` | Exactly one. |
| Priority | `priority:high`, `priority:medium`, `priority:low` | Exactly one. Never duplicate. |
| Launch status | `launch:blocking`, `launch:post`, `launch:deferred` | Exactly one for launch-track work. Parked/dream backlog items should normally be `launch:deferred`. |

Each open issue should also have:

- one or more domain labels, for example `UX`, `ui`, `voice`, `wake-word`, `skills`, `lists`, `meal-planning`, `memory`, `model-management`, `testing`, `technical-debt`, `research`, or `optimisation`;
- a milestone, unless the issue body or a comment clearly states why the issue is parked without one;
- a parent epic/workstream reference, or an explicit note that it is standalone;
- observable acceptance criteria;
- testing expectations, including when S21 or S23U validation is required.

## Issue readiness checklist

Before giving an issue to an agent, confirm:

- [ ] The issue has one `type:*`, one `size:*`, one `priority:*`, and one `launch:*` label.
- [ ] The issue has at least one domain label.
- [ ] The issue has a milestone, or a clear parked/no-milestone rationale.
- [ ] The issue links to its parent epic/workstream, or says it is standalone.
- [ ] The issue has observable acceptance criteria.
- [ ] The issue states automated, manual, and device validation expectations.
- [ ] The issue is implementation-ready; otherwise create or keep it as a `type:spike`.

## Spike vs implementation issue

Use `type:spike` when the implementation path, risk, licensing, store-policy impact, or test strategy is not yet clear. A spike should produce a decision, recommendation, evidence bundle, or concrete child issue; it should not quietly turn into broad production implementation.

Use `type:feature`, `type:bug`, `type:chore`, or `type:performance` when the approach is understood and the acceptance criteria are concrete enough for an agent to implement and test.

## Device validation expectations

Use the lightest validation that can prove the change safely:

- S21 is the default device for permission, QIR, navigation, test-harness, and model-readiness work.
- S23U should be used only for high-risk model/runtime/voice comparison or where explicitly required.
- Manual device evidence remains important for STT/TTS quality, wake-word behaviour, model availability, UI alignment, and flows that automated tests cannot prove reliably.
- Routine docs/process-only changes do not need physical device validation; state this explicitly in the issue or PR.
- Every PR with device evidence should state the device used, Android version when relevant, commands run, and evidence artifact paths.

## Project views and saved searches

Preferred durable backlog dashboard: create a GitHub Project named **Jandal Launch Backlog** and add views using the queries below. A local/browser agent can create and maintain this Project if it has GitHub Project management permissions. Personal saved searches are optional UI shortcuts and may still need to be created manually by the signed-in user.

Recommended Project views:

| View | Query / filter | Purpose |
| --- | --- | --- |
| Launch blockers | `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:launch:blocking` | Current release blockers |
| High-priority post-launch | `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:priority:high label:launch:post` | Valuable work that should not block launch |
| Epics and parent streams | `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:type:epic` | Parent workstreams and child status |
| Missing labels | `repo:NickMonrad/kernel-ai-assistant is:issue is:open no:label` | Metadata cleanup queue |
| Missing milestones | `repo:NickMonrad/kernel-ai-assistant is:issue is:open no:milestone` | Planning cleanup queue |
| Testing / evidence | `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:testing` | Test harness and evidence reliability |
| Technical debt | `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:technical-debt` | Maintenance work |
| Deferred backlog | `repo:NickMonrad/kernel-ai-assistant is:issue is:open label:launch:deferred` | Dream/deferred backlog |

If the Project implementation cannot express one of the filters as a saved Project view, keep the query in this document and create it as a personal saved search instead. The setup agent should comment on #1271 with the Project URL and any filters left as manual saved searches.

## Project setup follow-up

The backlog dashboard can be created after this PR lands. Create or reuse **Jandal Launch Backlog**, link it to this repository, add the views above, and add existing matching issues so the views are useful immediately.

## Automated warning signal

The repository includes `scripts/check_issue_hygiene.py` and the `Backlog hygiene` workflow.

The workflow runs:

- when issues are opened, edited, reopened, labeled, unlabeled, milestoned, or demilestoned;
- weekly on a schedule;
- manually via `workflow_dispatch`.

By default the workflow is warning-only. Maintainers can run it manually with `fail_on_violations=true` when they want a hard audit before a cleanup pass.

Local usage:

```bash
python3 scripts/check_issue_hygiene.py --repo NickMonrad/kernel-ai-assistant
python3 scripts/check_issue_hygiene.py --repo NickMonrad/kernel-ai-assistant --issue-number 1251
python3 scripts/check_issue_hygiene.py --repo NickMonrad/kernel-ai-assistant --fail-on-violations
python3 -m unittest scripts/tests/test_check_issue_hygiene.py
```

## Triage cadence

| Frequency | Activity |
| --- | --- |
| Weekly, or before agent handoff | Review workflow warnings plus the missing-label and missing-milestone Project views or saved searches. Fix anything that blocks clean agent handoff. |
| Before assigning work to an agent | Verify parent epic/workstream, labels, milestone, acceptance criteria, and test expectations. Add a comment if anything is missing; do not let the agent discover ambiguity mid-turn. |
| After PR merge | Close or update the linked issue, update the parent epic child status, and close completed child trackers where appropriate. |
| Monthly | Review `launch:deferred` and old `priority:low` items for closure, consolidation, or promotion to active work. |

## Parent epic update convention

When a child issue is created, completed, superseded, or deferred:

- update the child issue with its parent epic/workstream;
- update the parent epic child list or status summary;
- close duplicate or superseded children with the correct state reason where appropriate;
- leave a short comment when an issue is intentionally parked without a milestone.

## Launch reclassification rules

When reclassifying an issue's launch status:

- Keep `launch:blocking` if the issue affects first-run reliability, Store compliance, model readiness, permission UX, release evidence, or any launch claim.
- Move to `launch:post` if the issue is valuable but not launch-critical and should not block release.
- Move to `launch:deferred` if the issue is a dream feature, depends on unavailable technology, or has no clear implementation path within the next two milestones.
- Close as `not_planned` if the issue is superseded, a duplicate, or no longer relevant.
- If uncertain, leave the current label and add a comment explaining what decision or evidence is needed.

## Launch-blocking audit baseline

The #1255 Gate 0 audit grouped launch-track issues as follows:

**Keep as `launch:blocking`:**

- #1014 — Launch readiness parent epic
- #441 — Play Store publish
- #427 — Verification matrix
- #824 — Voice QA gate
- #1142 — Wake-word battery drain
- #1140 — Permission UX epic
- #428 — Memory profiling decision spike

**Already non-blocking / post-launch / deferred:**

- #928 — Lists UX, `launch:post`
- #885 — Messaging reply, `launch:post`
- #886 — Group chat, `launch:post`
- #756 — Piper voice, `launch:deferred`
- #713 — Vision, `launch:deferred`
- #432 — Compat swap, `launch:post`
- #430 — Dynamic loading, `launch:post`

**Closed / completed baseline:**

- #868 — completed
- #1245 — completed by PR #1256

Use this baseline as a starting point, not a permanent truth. Reclassify launch blockers when evidence changes, especially after S21 model-readiness and verification-matrix work lands.
