# Monthly Quality Scorecard and Review Loop

Status: **First-cut process / lightweight tooling**  
Parent: #1219  
Implements: #1226

---

## 1. Purpose

The monthly quality review is a small feedback loop for Jandal AI development quality. It should identify repeated, preventable patterns early and turn them into better prompts, templates, tests, review gates, or evidence guidance.

This is not a blame exercise and it is not a heavyweight governance process. The output should be a short scorecard plus a small set of concrete follow-up actions.

---

## 2. Review cadence

Run once per calendar month, ideally after the last merged PR for the month or during the first week of the next month.

Recommended scope:

- all PRs merged during the month;
- PRs opened during the month that required significant rework;
- evidence dashboard output generated during the month;
- docs drift warnings during the month;
- follow-up fixes created within seven days of a merge;
- manually tested high-risk changes.

Recommended participants:

- project owner / maintainer;
- local coding agent where useful;
- reviewer/oracle agent where the month included high-risk subsystem work.

---

## 3. Scorecard sections

### 3.1 Delivery mix

| Metric | Why it matters | Suggested source |
|---|---|---|
| Merged PRs | Volume and review load | GitHub PR list |
| Feature PRs | Product movement | PR title/labels/body |
| Fix PRs | Rework / bug pressure | PR title/labels/body |
| Docs/process/test-only PRs | Quality investment | PR title/labels/body |
| Fix vs feature ratio | Signals whether quality debt is rising | Derived |

Guidance:

- A high fix ratio is not automatically bad if it follows a deliberate hardening phase.
- Repeated fix PRs against the same subsystem should produce follow-up action.

### 3.2 Follow-up churn

| Metric | Why it matters | Suggested source |
|---|---|---|
| PRs requiring follow-up fixes within 7 days | Detects merge confidence issues | GitHub PRs/issues/commit messages |
| Repeat review blockers by category | Improves prompts and checklist quality | Review comments / PR summaries |
| Reopened or reverted work | Strong signal of missed validation | GitHub PR/issues |

Guidance:

- Track categories, not blame.
- Useful categories: missing test, missing docs, CI failure, device-specific regression, UX regression, evidence mismatch, scope creep.

### 3.3 Evidence quality

| Metric | Why it matters | Suggested source |
|---|---|---|
| Invalid evidence records | Shows whether dashboard data is trustworthy | `data/metrics.json` from evidence dashboard |
| Missing evidence manifests | Shows whether reviewers had enough context | PR bodies |
| High-risk PRs with appropriate evidence | Confirms #1221/#1222 adoption | PR risk tier + evidence manifest |
| Evidence waivers with rationale | Confirms skipped evidence was intentional | PR evidence manifest |
| Docs drift warnings | Finds behaviour changes without docs | Docs Drift Check |

Guidance:

- Low-risk docs/copy/template-only PRs should not be penalised for not having device evidence.
- A waived evidence item is acceptable when the rationale is explicit and aligned to the risk policy.
- Invalid evidence is a dashboard/data-quality issue first; do not treat it as an app quality failure unless it hides an app regression.

### 3.4 Harness and dashboard signal

| Metric | Why it matters | Suggested source |
|---|---|---|
| Failures by root-cause category | Shows recurring app/harness issues | `metrics.failure_buckets` |
| Flaky harness case count | Shows unreliable automation | Dashboard metrics / manual review |
| Stuck-mode/cascade suspects | Detects repeated wrong-tool patterns | `metrics.stuck_mode` |
| Timeout / retry / harness errors | Separates app issues from environment issues | `metrics.retry_timeout_harness` |
| Artifact availability | Helps reviewers reproduce failures | `metrics.artifacts` |

Guidance:

- Prioritise failure categories that repeat across unrelated PRs.
- Treat harness errors differently from product regressions.
- Avoid adding low-signal telemetry unless it is tied to an active failure pattern.

### 3.5 Device and manual testing use

| Metric | Why it matters | Suggested source |
|---|---|---|
| S21 evidence coverage for medium/high-risk changes | Confirms default device strategy | Evidence manifest / dashboard |
| S23U focused comparison usage | Ensures daily-driver use stays targeted | Evidence manifest / dashboard |
| S21 vs S23U divergence cases | Finds device-specific behaviour | Dashboard/manual notes |
| Manual testing used where it added signal | Confirms manual testing is focused | PR evidence manifest |
| Manual testing overuse on low-risk work | Prevents process drag | PR evidence manifest |

Guidance:

Manual on-device testing should be used for high-risk or hard-to-automate areas such as:

- STT/TTS quality;
- wake word / VAD lifecycle;
- audio focus and playback;
- Android permission flows;
- UI alignment, touch targets, and visual polish where screenshots or automated checks are insufficient;
- ambiguous automated evidence requiring human judgement.

Manual on-device testing should usually be avoided for pure docs, templates, scripts, CI-only checks, and low-risk refactors.

---

## 4. Monthly review process

1. **Collect inputs**
   - PRs merged in the month.
   - PRs with follow-up fixes within seven days.
   - Evidence dashboard `data/metrics.json` if available.
   - Docs Drift Check warnings.
   - Notable manual test evidence from PR manifests.

2. **Fill the scorecard**
   - Use `docs/quality/monthly-quality-scorecard-template.md`.
   - Use `scripts/generate_quality_scorecard.py` to create a starter markdown file when source data is available.
   - Keep unknown values as `TBD`; do not invent precision.

3. **Identify patterns**
   - Look for repeated review blockers or recurring failure buckets.
   - Separate product bugs from harness/data-quality issues.
   - Note where the risk policy was too heavy or too light.

4. **Create follow-ups**
   - Create a small number of action items.
   - Prefer concrete issues: improve a prompt template, add a targeted test, clarify a review gate, fix dashboard evidence shape.
   - Avoid broad, vague quality initiatives.

5. **Close the loop next month**
   - Review previous follow-ups.
   - Mark actions as done, carried forward, or superseded.
   - Tune the process if the scorecard is too noisy.

---

## 5. Action categories

Use these categories for follow-ups:

| Category | Examples |
|---|---|
| Prompt improvement | Add explicit validation instructions to agent prompts; call out no Copilot Review; require oracle review for fragile work |
| Template improvement | Update PR/evidence manifest template; add missing docs-not-needed guidance |
| Test coverage | Add unit test, harness case, UIAutomator check, or dashboard fixture |
| Evidence quality | Fix invalid evidence shape, missing metadata, bad artifact links |
| Review gate | Clarify subsystem-specific gate for voice, LiteRT, permissions, navigation, wallpaper/theme |
| Product defect | Create/fix app bug found during the review |
| Process simplification | Remove unnecessary evidence for low-risk work; reduce S23U usage |

---

## 6. Storage convention

Recommended scorecard path:

```text
reports/quality/YYYY-MM-scorecard.md
```

Examples:

```text
reports/quality/2026-06-scorecard.md
reports/quality/2026-07-scorecard.md
```

Do not commit large raw exports or downloaded logs unless they are intentionally curated evidence.

---

## 7. Relationship to existing process docs

This monthly loop consumes outputs from:

- `.docs/agents/risk-based-evidence-policy.md` — risk tier and device/manual testing expectations;
- `.docs/agents/evidence-manifest.md` — PR evidence summaries and waivers;
- `.docs/agents/review-checklist.md` — review blocker categories;
- `docs/testing/harness-metrics-dashboard-design.md` — dashboard evidence metrics;
- `scripts/build_test_dashboard.py` / `data/metrics.json` — evidence validity and failure buckets;
- `scripts/check_docs_drift.py` — docs drift warnings.

The scorecard should not duplicate these documents. It should summarise whether they worked in practice.

---

## 8. First-cut acceptance mapping

| #1226 acceptance criterion | First-cut coverage |
|---|---|
| Scorecard metrics are documented | Sections 3.1–3.5 |
| Monthly review process is lightweight | Section 4 |
| Produces actionable follow-ups, not blame | Sections 1, 4, 5 |
| Metrics align with #1221 and #1222 | Sections 3.3, 3.5, 7 |
| First review possible from existing PR/commit/evidence data | Template plus `scripts/generate_quality_scorecard.py` |

---

## 9. Local-agent continuation prompt

```text
Repo: NickMonrad/kernel-ai-assistant
Issue: #1226
Parent epic: #1219
Do not request GitHub Copilot Review.

Continue the monthly quality scorecard first cut.

Read:
- docs/quality/monthly-quality-scorecard.md
- docs/quality/monthly-quality-scorecard-template.md
- scripts/generate_quality_scorecard.py
- .docs/agents/risk-based-evidence-policy.md
- .docs/agents/evidence-manifest.md
- docs/testing/harness-metrics-dashboard-design.md

Goal:
Make the scorecard easier to populate from real repo data without making the process heavyweight.

Consider:
- adding a sample input JSON fixture;
- adding more script tests for missing/partial metrics data;
- optionally adding a GitHub Actions/manual workflow later, but only if it stays non-blocking;
- updating README/index docs with a small link if needed.

Validation:
- git diff --check
- python3 -m unittest discover -s scripts/tests

No Android build or device testing required for docs/script-only work.
```
