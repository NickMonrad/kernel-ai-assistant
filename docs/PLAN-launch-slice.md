# Launch plan snapshot

This document is retained as a historical snapshot from the May 2026 backlog review. It is **not** the current source of truth for Play Store launch sequencing.

Use the GitHub launch-plan issue instead:

- [#1014 - Play Store Launch Readiness & QA](https://github.com/NickMonrad/kernel-ai-assistant/issues/1014) is the parent launch epic.
- [#1255 - Launch Plan: ordered implementation sequence and release gates](https://github.com/NickMonrad/kernel-ai-assistant/issues/1255) is the canonical ordered launch plan.

## Why this file changed

The launch backlog has been re-groomed since this file was generated. Several items that were originally listed as launch-blocking have since been completed, demoted to post-launch/deferred, or replaced by narrower validation gates.

Keeping the old generated table in active README links was misleading, so the README now points to #1255 for current sequencing.

## Current launch gates

The current plan is organised by gates rather than a single flat issue list:

1. **Launch scope and backlog sanity** - keep `launch:blocking` trustworthy.
2. **Test/evidence foundation** - make agent work and S21-first evidence reliable.
3. **Core app and first-run reliability** - model readiness, chat reliability, and fresh-install flow.
4. **Permissions and Android capability repair** - contextual permission/repair UX.
5. **Voice and wake-word launch-risk validation** - battery, STT/TTS, and voice claims.
6. **Final release QA and store readiness** - verification, docs, attribution, signing, listing, and policy checks.

## Historical context

The original version of this file was generated from a full review of 113 open issues and was useful for the first launch-scope pass. The durable project-management state now lives in GitHub issues because labels, milestones, child status, and PR outcomes change frequently.

Do not update this file with new sequencing unless the project intentionally moves the canonical launch plan back into repository docs.
