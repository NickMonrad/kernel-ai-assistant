# Harness Metrics and PR Evidence Dashboard Design

Status: **First-cut design / implementation handoff**  
Parent: #1219  
Implements: #1224

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

## 4. First implementation slice

This PR adds `scripts/summarise_test_evidence_metrics.py` as an additive metrics summariser.

The script reads one evidence file or a directory of evidence JSON files and emits a compact JSON summary that can later be consumed by `scripts/build_test_dashboard.py`, GitHub step summaries, or local reviewer tooling.

Initial output sections:

- `totals`: evidence record count and total/pass/fail/xfail case counts;
- `validity`: valid/invalid record counts and issue buckets;
- `by_suite`: pass/fail/xfail by suite;
- `by_device`: pass/fail/xfail by device, including label/model/tier/android/source;
- `failure_buckets`: count by `failure_category`;
- `confusion_matrix`: expected tool/result vs actual tool/result;
- `retry_timeout_harness`: retry, timeout, harness error, and device environment error counts;
- `slot_fill`: slot-fill case count and stale-carryover suspects;
- `stuck_mode`: repeated same wrong actual tool across different expected tools;
- `artifacts`: screenshot/video/log paths when present.

This first cut intentionally does **not** modify existing dashboard rendering. That keeps the PR safe and gives the local agent a clear next step: wire the summary into `build_test_dashboard.py` cards/tables.

---

## 5. Dashboard handoff plan

Recommended next implementation steps after this PR:

1. Run the summariser against current `test-results` evidence and inspect `summary.json`.
2. Add a `metrics` block to `dashboard-data.json` generated by `build_test_dashboard.py`.
3. Add dashboard cards for:
   - evidence validity;
   - top failure buckets;
   - device context / S21 vs S23U / CI signal;
   - stuck-mode warning;
   - artifact links.
4. Add a compact PR-focused section at the top of the dashboard for the latest PR scope.
5. Add optional step-summary rendering in the evidence publishing workflow only after the JSON shape is stable.

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

| #1224 acceptance criterion | First cut coverage |
|---|---|
| Existing harness metrics are documented | This document references current schema and dashboard builder. |
| Recommended additions are triaged by value/effort | See §3. |
| Dashboard/evidence output highlights validity, failure bucket, and device context | Implemented in the summariser JSON output. |
| Flaky/stuck-mode indicators are designed | Stuck-mode is implemented; flaky detection is staged. |
| No low-signal telemetry is added | Explicitly excluded in §3.3. |

---

## 8. Local agent handoff prompt

Use this after the PR lands or as a continuation branch:

```text
Repo: NickMonrad/kernel-ai-assistant
Issue: #1224
Do not request GitHub Copilot Review.

Continue the first-cut harness metrics work. Read docs/testing/harness-metrics-dashboard-design.md and scripts/summarise_test_evidence_metrics.py first.

Goal: wire the metrics summariser into scripts/build_test_dashboard.py without changing the evidence schema or adding low-signal telemetry.

Implement:
- add a metrics block to dashboard-data.json;
- add dashboard HTML cards for validity, top failure buckets, device context, and stuck-mode warnings;
- keep dashboard generation tolerant of old evidence files;
- add focused script tests for the new dashboard data block.

Validation:
- python3 -m unittest discover -s scripts/tests
- run scripts/build_test_dashboard.py against a small fixture results directory if available
- no Android/device testing required.
```
