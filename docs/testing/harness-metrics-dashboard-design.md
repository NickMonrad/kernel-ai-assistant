# Harness Metrics and PR Evidence Dashboard Design

Status: **Dashboard integration complete — implementation slice for #1224**
Parent: #1219
Implements: #1224
PR: #1237

> **Authority:** This document records dashboard design, implemented dashboard metrics, and staged follow-up work. It is not the current harness runbook and it does not define the evidence schema contract.
>
> **Evidence schema contract:** [`test-evidence-schema.md`](./test-evidence-schema.md)
>
> **Current run commands:** [`../automated-testing.md`](../automated-testing.md)
>
> **Review gate:** [`.docs/agents/review-gates-test-harness.md`](../../.docs/agents/review-gates-test-harness.md)

---

## 1. Goal

Improve the automated evidence signal reviewers see before merge. The dashboard should answer four questions quickly:

1. **What failed?** Pass/fail/xfail by suite, category, phase, and device.
2. **Why did it fail?** Failure buckets, tool confusion, slot-fill and retry indicators.
3. **Is this evidence valid?** Required metadata, summary/case consistency, oracle/preflight markers, log bounds where present.
4. **Does behaviour differ by device?** Device model, Android version, source, commit, and comparison-friendly groupings.

The dashboard is not a substitute for manual on-device checks where human judgement is required, especially STT/TTS quality, UI alignment, audio focus/playback, and Android permission flows.

---

## 2. Existing evidence shape

The current normalised evidence schema already contains the core fields needed for the first metrics slice:

- top-level context: `source`, `suite`, `timestamp`, `repo`, `branch`, `commit`, `pr`, `release`, `run_id`;
- device context: `device.id`, `device.label`, `device.model`, `device.tier`, `device.android_api`, `device.execution`;
- model context: `model.name`, `model.runtime`, `model.backend`;
- summary: `total`, `passed`, `failed`, `pass_rate`;
- cases: `name`, `passed`, `expected_tool`, `actual_tool`, `expected_result_mode`, `actual_result_mode`, `retry_seen`, `slot_fill_seen`, `failure_category`, `failures`.

The existing dashboard builder, `scripts/build_test_dashboard.py`, already groups evidence by PR/release/device and links source JSON files. This issue should extend that signal rather than replace the dashboard.

---

## 3. Prioritised metrics

### 3.1 Implement now

| Metric | Why it matters | Source |
|---|---|---|
| Evidence validity | Prevents reviewers trusting malformed/incomplete evidence | top-level required fields, summary/case consistency |
| Failure bucket distribution | Shows root-cause pattern quickly | `cases[].failure_category` |
| Intent/tool confusion matrix | Detects wrong-tool routing/model behaviour | `expected_tool` + `actual_tool` |
| Retry/timeout/harness error counts | Separates app failure from environment/harness issues | `retry_seen`, `failure_category` |
| Slot-fill indicators | Highlights slot-fill path and stale carryover suspects | `slot_fill_seen`, `failure_category`, optional future fields |
| Device context summary | Makes S21/S23U/CI comparison obvious | `device`, `source`, `commit`, `timestamp` |
| Stuck-mode/cascade indicator | Detects repeated same wrong intent/tool across unrelated prompts | derived from wrong-tool cases |
| Artifact paths | Helps reviewers jump to screenshots/videos/logs when present | optional `screenshot_path`, `video_path`, `log_path` fields |

### 3.2 Design now, stage implementation

| Metric | Stage reason |
|---|---|
| Flaky detection across repeated runs | Requires history windows and repeat-run semantics. First cut can expose repeated case outcomes by PR/device later. |
| Parameter extraction accuracy by slot | Requires structured expected/actual slot fields. First cut should define the contract but avoid brittle parsing from free-text failures. |
| Oracle preflight / stream health / log bounds | Valuable but fields are not consistently present yet. First cut should surface them when present and mark as `unknown` otherwise. |
| Test duration by suite and case | Add when harness emits `duration_ms`; do not infer from timestamps. |

### 3.3 Explicitly not prioritised

Do not add these unless they become active failure causes:

- GPU utilisation/frequency;
- per-layer LiteRT timing;
- battery/thermal telemetry;
- network latency for local inference flows.

---
## 4. Implementation: summariser + dashboard integration

### 4.1 Metrics summariser

**File:** `scripts/summarise_test_evidence_metrics.py`

An additive metrics summariser. Reads one evidence JSON file or a directory of evidence files and emits compact JSON. The summariser can be run standalone (`--markdown` for a human-readable version) or consumed programmatically.

Output sections:

- `totals`: evidence record count and total/pass/fail/xfail case counts;
- `validity`: valid/invalid record counts and issue buckets;
- `by_suite`: pass/fail/xfail by suite;
- `by_device`: pass/fail/xfail by device, including label/model/tier/android/source;
- `failure_buckets`: count by `failure_category`;
- `confusion_matrix`: expected tool/result vs actual tool/result;
- `result_mode_matrix`: expected vs actual result mode;
- `retry_timeout_harness`: retry, timeout, harness error, and device environment error counts;
- `slot_fill`: slot-fill case count and stale-carryover suspects;
- `stuck_mode`: repeated same wrong actual tool across different expected tools;
- `artifacts`: screenshot/video/log paths when present.

### 4.2 Dashboard data integration

**File:** `scripts/build_test_dashboard.py`

The metrics summariser is imported and called inside `_build_aggregates()` when evidence is present. A `metrics` block is added to the internal aggregates dict and exported as `data/metrics.json`. The import is wrapped in a `try/except ImportError` guard — the dashboard works without the metrics module.

### 4.3 Dashboard cards

The overview page (`index.html`) gains a **Reviewer Metrics** section below the "Latest Results" cards. It includes:

- **Evidence validity** card — valid/invalid counts with a warning badge when issues exist.
- **Validity issue buckets** card — shown only when invalid evidence is present, listing specific issues.
- **Failure buckets** card — top failure categories by count, or "No failures" when empty.
- **Device context** card — per-device table with id, label, tier, API level, source, and pass/total.
- **Stuck-mode warning panel** — shown only when the same wrong actual tool maps to multiple different expected tools, with a cautionary note that this is a suspect signal, not a definitive diagnosis.
- **Artifact links** table — suite/device/case/type/path for up to 20 artifacts, when present.

All sections are tolerant of missing optional fields: missing values render as `—`, empty rows are omitted, and no section appears when there is no data.

### 4.4 Old evidence tolerance

The dashboard remains tolerant of old evidence files that lack newer optional fields. The metrics summariser uses the same `_safe_int`/`_safe_float`/`.get()` patterns as the rest of the dashboard builder. Missing device context, model info, or case-level fields produce empty/unknown values instead of crashing.

---

## 5. Dashboard handoff — implemented vs remaining

### Implemented in this PR (#1237)

| Step | Status |
|------|--------|
| Metrics summariser (`summarise_test_evidence_metrics.py`) | ✅ Complete |
| `metrics` block in `_build_aggregates()` return value | ✅ Complete |
| `data/metrics.json` export | ✅ Complete |
| Dashboard cards (validity, failure buckets, device, stuck-mode, artifacts) | ✅ Complete |
| Old evidence tolerance | ✅ Complete |
| Tests for dashboard integration | ✅ Complete (9 tests) |

### Remaining staged work

These are part of the #1224 scope but were intentionally staged for future PRs:

1. **Flaky detection** across historical repeated runs — requires history windows and repeat-run semantics. The summariser already exposes case outcome data by PR/device, but true flaky detection (same test passing and failing across runs) needs a follow-up pass.
2. **Step-summary rendering** in the evidence publishing workflow — once the metrics JSON shape is stable, wire it into `publish-pr-test-evidence.yml` as a GitHub step summary.
3. **Dashboard device comparison view** — a dedicated page or tab comparing S21 vs S23U vs CI results side-by-side for the same PR or release scope.
4. **Release baseline comparison** — flag regressions by comparing PR evidence against the latest release baseline for the same suite/device.

---

## 6. Evidence validity rules

First-cut validity rules are deliberately simple and explainable:

- all required top-level fields are present;
- `summary` is an object;
- `cases` is a list;
- `summary.total == len(cases)`;
- `summary.passed + summary.failed <= summary.total`;
- each failed case has a `failure_category` or at least one failure message;
- device context includes `id` and at least one of `label`, `model`, or `execution`;
- commit context includes `commit` and `run_id`.

Warnings should be shown in dashboard output; invalid records should not crash dashboard generation.

---

## 7. Acceptance mapping

| #1224 acceptance criterion | Coverage |
|---|---|
| Existing harness metrics are documented | This document references current schema and dashboard builder. |
| Recommended additions are triaged by value/effort | See §3. |
| Dashboard/evidence output highlights validity, failure bucket, and device context | Implemented in summariser JSON output + dashboard cards. |
| Flaky/stuck-mode indicators are designed | Stuck-mode is implemented; flaky detection is staged (§5). |
| No low-signal telemetry is added | Explicitly excluded in §3.3. |

---

## 8. Local agent handoff prompt

Use this as a continuation prompt for remaining #1224 work:

```text
Continue the harness metrics work from PR #1237.

Read docs/testing/harness-metrics-dashboard-design.md first (especially §5 — Remaining staged work).
Read scripts/summarise_test_evidence_metrics.py and scripts/build_test_dashboard.py before editing.

Remaining #1224 work:
- Flaky detection across repeated runs
- Step-summary rendering in publish-pr-test-evidence.yml
- Dashboard device comparison view (S21 vs S23U vs CI)
- Release baseline regression flagging

Keep the implementation additive and low-risk. No Android/device testing required.
```
