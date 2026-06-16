# Backlog hygiene and triage policy

This policy keeps Jandal issues ready for agent handoff without adding heavy project-management ceremony. It supports issue #1251 and applies to every open issue unless the issue is intentionally parked with an explicit rationale.

## Required issue metadata

Every open issue should have exactly one label from each required category:

| Category | Allowed labels |
| --- | --- |
| Type | `type:epic`, `type:feature`, `type:bug`, `type:chore`, `type:spike`, `type:performance` |
| Size | `size:XS`, `size:S`, `size:M`, `size:L`, `size:XL` |
| Priority | `priority:high`, `priority:medium`, `priority:low` |
| Launch status | `launch:blocking`, `launch:post`, `launch:deferred` |

Each open issue should also have:

- one or more domain labels, for example `UX`, `ui`, `voice`, `wake-word`, `skills`, `lists`, `meal-planning`, `memory`, `model-management`, `testing`, `technical-debt`, `research`, or `optimisation`;
- a milestone, unless the issue body or comment clearly states why the issue is parked without one;
- a parent epic/workstream reference, or an explicit note that it is standalone;
- clear acceptance criteria;
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

## Device validation expectations

Use the lightest validation that can prove the change safely:

- S21 is the default device for permission, QIR, navigation, and test-harness work.
- S23U should be used only for high-risk model/runtime comparison or where explicitly required.
- Manual device evidence remains important for STT/TTS quality, wake-word behaviour, model availability, UI alignment, and any flow that automated tests cannot prove reliably.
- Routine docs/process-only changes do not need physical device validation; state this explicitly in the issue or PR.

## Saved searches / project views

Create GitHub saved searches or Project views for the following queries:

```text
repo:NickMonrad/kernel-ai-assistant is:issue is:open no:label
repo:NickMonrad/kernel-ai-assistant is:issue is:open no:milestone
repo:NickMonrad/kernel-ai-assistant is:issue is:open label:launch:blocking
repo:NickMonrad/kernel-ai-assistant is:issue is:open label:type:epic
repo:NickMonrad/kernel-ai-assistant is:issue is:open label:priority:high label:launch:post
repo:NickMonrad/kernel-ai-assistant is:issue is:open label:launch:deferred
repo:NickMonrad/kernel-ai-assistant is:issue is:open label:testing
repo:NickMonrad/kernel-ai-assistant is:issue is:open label:technical-debt
```

Recommended dashboard sections:

1. Launch blockers
2. High-priority post-launch
3. Ready for agent
4. Needs design / spike
5. Missing metadata
6. Deferred / dream backlog
7. Epics and parent streams

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
```

## Triage cadence

- Weekly: review the workflow summary plus missing-label and missing-milestone saved searches.
- Before assigning work to an agent: confirm the readiness checklist above.
- After PR merge: close or update the linked issue, and update the parent epic child status.
- Monthly: review `launch:deferred` and old `priority:low` issues for closure, consolidation, or promotion.

## Parent epic update convention

When a child issue is created, completed, superseded, or deferred:

- update the child issue with its parent epic/workstream;
- update the parent epic child list or status summary;
- close duplicate or superseded children with the correct state reason where appropriate;
- leave a short comment when an issue is intentionally parked without a milestone.

## Spike vs implementation issue

Use `type:spike` when the implementation path, risk, licensing, store-policy impact, or test strategy is not yet clear.

Convert or replace the spike with a `type:feature`, `type:bug`, `type:chore`, or `type:performance` implementation issue when the outcome is known and acceptance criteria are concrete.
