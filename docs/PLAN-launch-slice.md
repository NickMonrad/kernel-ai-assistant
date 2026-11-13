# Launch plan snapshot

This document is retained as a historical snapshot from the May 2026 backlog review. It is **not** the current source of truth for Play Store launch sequencing.

Use GitHub launch tracking instead:

- [#1014 - Play Store Launch Readiness & QA](https://github.com/NickMonrad/kernel-ai-assistant/issues/1014) is the parent launch epic and current launch tracking issue.
- The `launch:blocking` label and [Jandal Launch Backlog](https://github.com/users/NickMonrad/projects/5) project views are the live work queue.

> Note: older references to `#1255` as the canonical launch plan are stale. #1255 is now the completed model-readiness preflight tracker, not the active launch-plan source of truth.

## Why this file changed

The launch backlog has been re-groomed since this file was generated. Several items that were originally listed as launch-blocking have since been completed, demoted to post-launch/deferred, or replaced by narrower validation gates.

Keeping the old generated table in active README links was misleading, so active launch tracking now belongs in GitHub issues and project views rather than this static snapshot.

## Current launch gates

The current plan is organised by gates rather than a single flat issue list:

1. **Launch scope and backlog sanity** - keep `launch:blocking` trustworthy.
2. **Test/evidence foundation** - make agent work and S21-first evidence reliable.
3. **Core app, accessibility, and first-run reliability** - model readiness, chat reliability, accessibility/readability, and fresh-install flow.
4. **Permissions and Android capability repair** - contextual permission/repair UX.
5. **Voice and wake-word launch-risk validation** - battery, STT/TTS, and voice claims.
6. **Final release QA and store readiness** - verification, docs, attribution, signing, listing, and policy checks.

## Historical context

The original version of this file was generated from a full review of 113 open issues and was useful for the first launch-scope pass. The durable project-management state now lives in GitHub issues and project views because labels, milestones, child status, and PR outcomes change frequently.

Do not update this file with new sequencing unless the project intentionally moves the canonical launch plan back into repository docs.
