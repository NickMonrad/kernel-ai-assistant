# Automated Testing

This document is the current automation overview for Kernel AI. It complements the deeper
planning/spec work in [`docs/testing/automated-test-specification.md`](./testing/automated-test-specification.md)
and the device setup guide in [`docs/adb-testing.md`](./adb-testing.md).

## Current automated coverage

| Layer | Tooling | What it covers | Entry point |
|-------|---------|----------------|-------------|
| Unit tests | Gradle + JUnit 5 + MockK | Core Kotlin logic, routing, parsing, repositories, presenters | `./gradlew testDebugUnitTest` |
| Instrumented UI tests | Gradle + Compose/AndroidX test | Compose UI and connected-device Android tests, including S21 `permission_flows` | `./gradlew connectedDebugAndroidTest` |
| Device regression harness | `adb` + Python | End-to-end intent routing, profile extraction, and on-device chat/action flows | `python3 scripts/adb_skill_test.py` |
| Permission scenario runner | `adb` + Python | Physical-device permission journeys with step traces, screenshots, focused logcat, and UX-friction counters | `python3 scripts/run_permission_scenarios.py --device-id s21-exynos --scenarios …` |
| Permission report publisher | Python + `gh` | Explicitly publish an existing permission report bundle to `test-results` and one sticky PR comment | `python3 scripts/publish_permission_scenario_report.py --report-dir … --pr … --commit … --device-id s21-exynos` |
| Evidence generators | Python | Convert CI/connected outputs into #1113 normalised evidence | `python3 scripts/generate_permission_flow_evidence.py` |
| Acoustic wake reliability runner | `adb` + Python `unittest` | Paired source-to-target journal waits, strict source/snapshot contracts, independent matrix positions, environment invalidation, normalised evidence, and sanitised artifact export | `python3 scripts/acoustic_wake_reliability_runner.py fixture` / `python3 -m unittest scripts.tests.test_acoustic_wake_reliability_runner` |


## Permission scenario runner

The first-slice permission runner lives at [`scripts/run_permission_scenarios.py`](../scripts/run_permission_scenarios.py).
It is a **local physical-device** runner for permission journeys that cross the app / Android
boundary. It stays local-only by default: it does **not** auto-post GitHub comments or publish
durable evidence unless you explicitly run the publisher step.

Use it when you need:

- Android runtime permission dialogs
- Android Settings repair round-trips
- app resume after external permission changes
- screenshots and reviewer-friendly step traces
- UX-friction counters on a real device

Policy:

- **S21 first** (`s21-exynos`)
- **Do not use the S23U by default**
- if no S21 / ADB device is available, stop on on-device validation rather than faking success

See [`docs/testing/permission-scenario-runner.md`](./testing/permission-scenario-runner.md) for
the local run command, explicit publish flow, stale-report protections, and artifact layout.
## ADB regression harness

The main device automation entry point is [`scripts/adb_skill_test.py`](../scripts/adb_skill_test.py).

Useful commands:

```bash
# Preview the run plan without touching a device
python3 scripts/adb_skill_test.py --dry-run

# Run the full skill-routing suite
python3 scripts/adb_skill_test.py

# Run only selected phases
python3 scripts/adb_skill_test.py --phases weather,lists

# Run profile-extraction checks
python3 scripts/adb_skill_test.py --profile

# Post a summary comment back to the open PR for the current branch
python3 scripts/adb_skill_test.py --post-pr
```

Supported harness phases today:

1. `alarm_timer`
2. `weather`
3. `media`
4. `lists`
5. `smart_home`
6. `memory`
7. `navigation`
8. `system`
9. `misc`
10. `slot_fill`
11. `orchestrator_recovery`
12. `false_positives`  — 16 false-positive / negative-routing cases
The `llm_tools` harness is a separate special mode (see below) — it is not one of the
QIR skill-routing phases and is not included in a normal full-suite run.

Reports are written to [`scripts/test-reports/`](../scripts/test-reports/) as JSON artifacts.

### Reports and result inspection

Each run produces a timestamped JSON report:

| Suite | File pattern | Example |
|-------|-------------|---------|
| Full QIR skill routing | `<timestamp>_skills.json` | `2026-06-07T01-39-59Z_skills.json` |
| LLM tool-call generation | `<timestamp>_llm_tools.json` | `2026-06-08T04-28-55Z_llm_tools.json` |
| Partial in-progress snapshot | `<timestamp>_<suite>_partial.json` | `2026-06-08T04-28-55Z_llm_tools_partial.json` |

The HTML report generator (`scripts/generate_report.py`) automatically converts `skills` JSON
reports to HTML when present.

**Finding the latest report:**

```bash
latest=$(ls -t scripts/test-reports/*_skills.json | head -1)
latest_llm=$(ls -t scripts/test-reports/*_llm_tools.json | head -1)
```

**Inspecting with `jq`:**

```bash
# Summary fields
jq '.summary' "$latest"

# Per-result: passed, intent matching, param detail
jq '.results[] | {index, message, expect_intent, actual_intent, intent_passed, params_passed, status}' "$latest"

# Only failures
jq '.results[] | select(.status == "fail") | {message, expect_intent, actual_intent, param_failures}' "$latest"

# llm_tools — per-result summary (mode is embedded in skill_result_marker)
jq '.results[] | {name, passed, expected_top_level_tool, actual_top_level_tool, route_marker, skill_result_marker, failures}' "$latest_llm"
# llm_tools — only failures with failure details
jq '.results[] | select(.passed == false) | {name, expected_top_level_tool, actual_top_level_tool, failures}' "$latest_llm"
```

**Report schema (skills suite):**

```json
{
  "suite": "skills",
  "status": "complete",         // or "in_progress" for partial
  "timestamp": "2026-06-07T01-39-59Z",
  "summary": { "total": 199, "passed": 73, "xfail": 5, "xpass": 0, "failed": 121, "indeterminate": 0 },
  "results": [
    {
      "index": 1,
      "message": "set an alarm for 11pm",
      "expect_intent": "set_alarm",
      "actual_intent": "set_alarm",
      "intent_passed": true,
      "params_passed": true,
      "param_failures": [],
      "xfail": false,
      "reply_warn": null,
      "log_check_warn": null,
      "status": "pass"          // "pass", "fail", "xfail", "xpass", or "indeterminate"
    }
  ]
}
```

**Report schema (llm_tools suite):**

```json
{
  "suite": "llm_tools",
  "status": "complete",
  "timestamp": "2026-06-08T04-28-55Z",
  "elapsed_seconds": 0,
  "summary": { "total": 3, "passed": 2, "failed": 1 },
  "results": [
    {
      "index": 1,
      "name": "query_wikipedia_natural",
      "message": "Look up the history of the Battle of Hastings...",
      "expected_top_level_tool": "query_wikipedia",
      "actual_top_level_tool": "query_wikipedia",
      "route_marker": "result=fallthrough ...",
      "native_tool_marker": null,
      "legacy_tool_marker": "<|tool_call|>call:query_wikipedia...",
      "skill_result_marker": "skill={\"name\":\"query_wikipedia\",...}",
      "message_saved_marker": "id=7e195582-... tool=query_wikipedia",
      "retry_seen": false,
      "slot_fill_seen": false,
      "chip_text": "tool=query_wikipedia",
      "reply_text": null,
      "passed": true,
      "failures": []
    }
  ]
}
```

### `false_positives` phase

The `false_positives` harness phase (phase 11) validates that the model does NOT trigger a
forbidden native intent for queries that merely *resemble* an intent-driven command. Each case
defines `forbidden_intents` (intents that must NOT fire), optional `allowed_intents` (safe
native routes that are acceptable), and `expect_llm_fallthrough` (whether the model must fall
through to a conversational LLM reply).

**Oracle semantics priority (first match wins):**

| Condition | Status | Meaning |
|-----------|--------|---------|
| Forbidden intent observed | `fail` / `xfail` | Forbidden intent fired — product regression (#1272) |
| Allowed safe native route | `pass` / `xpass` | Safe intent took precedence |
| `expect_llm_fallthrough` + fallthrough observed | `pass` / `xpass` | Model fell through to LLM generation |
| `expect_llm_fallthrough` + no fallthrough | `indeterminate` | Oracle/observability failure — exit non-zero |
| No forbidden, no fallthrough required | `pass` | No regression |

**`indeterminate` status** always produces a non-zero exit code, unlike `xpass` which is
informational only.

**Additional report fields (present when `forbidden_intents` was set on the case):**

| Field | Type | Description |
|-------|------|-------------|
| `forbidden_intents` | `list[str]` | Intents that must not fire |
| `forbidden_intent_triggered` | `bool` | Whether a forbidden intent was observed |
| `forbidden_intent_observed` | `list[str]` | Which forbidden intents fired |
| `allowed_intent_observed` | `str | null` | Safe native route that fired, if any |
| `fallthrough_observed` | `bool` | Whether LLM fallthrough evidence was found |
| `expect_llm_fallthrough` | `bool` | Whether fallthrough was required |

**Run commands:**

```bash
# Preview without a device
python3 scripts/adb_skill_test.py --dry-run --phases false_positives
python3 scripts/adb_skill_test.py --dry-run --categories false_positive

# Run on device
python3 scripts/adb_skill_test.py --phases false_positives
```

**16 cases:** date/time negative disambiguation (6), calendar (1), alarm/timer disambiguation
(1: `Set a 5 minute egg timer`), list operations (3), memory (2), weather (3).

### `llm_tools` phase

The `llm_tools` harness phase validates E2E model tool-call generation after the query bypasses
QIR/classifier deterministic routing and falls through to Gemma. It covers the
`Tier 2 → FallThrough → Tier 3 (Gemma)` path.

**What it validates per case:**

- The harness confirms a `llm_tools_route` marker was emitted and that deterministic
  QIR/classifier/slot-fill paths did not win before Gemma tool-call generation
- Gemma generated a native **or** legacy text-format tool call
- The expected tool was actually called
- The tool result is observable via `llm_tools_skill_result` marker
- The tool call is persisted in chat history
- A UI chip for the tool call is visible
- No unexpected hallucination retry path was triggered
- No unexpected QIR slot-fill path was used

**Run commands:**

```bash
# Preview without a device
python3 scripts/adb_skill_test.py --dry-run --phases=llm_tools

# Run on device
python3 scripts/adb_skill_test.py --phases=llm_tools
```

**Golden prompts (3 cases):**

| Case | Prompt | Expected tool | Assertions |
|------|--------|--------------|------------|
| Wikipedia query | "Look up the history of the Battle of Hastings on Wikipedia for me" | `query_wikipedia` | No regex matching, no classifier, no slot fill, no retry |
| Memory save | "Here is a lasting fact I want you to know: my preferred dry cleaner is Star Dry Cleaning" | `save_memory` | Same + `content` field present |
| System info | "Can you inspect this device and summarise its current system status?" | `get_system_info` | Same, no args expected |

### `false_positives` phase

The `false_positives` phase validates that ambiguous phrasings do **not** trigger
forbidden native intents. This is a P0 regression suite (issue #1272) covering:

- **Date/Time** (6 cases): Phrases containing time words that should not trigger `get_time`
  — e.g. *"What year is this movie set in"*, *"What time should I leave"*
- **Calendar** (1 case): *"Send a calendar invite to John"* must not trigger `create_calendar_event`
- **Alarm/Timer** (1 case): *"Set a 5 minute egg timer"* must not trigger `set_alarm`;
  `allowed_intents` includes `set_timer` for disambiguation
- **List** (3 cases): *"List all the capitals of Europe"* must not trigger `create_list`
  or `get_list_items`; *"Create a plan for my week"* must not trigger `create_list`;
  *"Add some detail to your explanation"* must not trigger `add_to_list`
- **Memory** (2 cases): *"I remember when we talked about this"* and
  *"Don't forget to add milk"* must not trigger `save_memory`
- **Weather** (3 cases): *"How hot was the summer of '69"*, *"Is it going to be a long winter"*,
  *"What's the weather like in Game of Thrones"* — must not trigger `get_weather`

**What validates per case:**

- Sends prompt, captures logcat with extended timeout (30s+) to wait for LLM fallthrough
- Scans **all** `NativeIntentHandler.handle: intent=...` lines in the captured output
- **Fails** if any `forbidden_intent` appears — forbidden intent **always wins**, even
  when an allowed safe native route was also observed
- **Passes via allowed safe route** when `allowed_intents` is configured and the safe
  intent was observed — no fallthrough evidence required
- **Passes via LLM fallthrough** when `expect_llm_fallthrough=True` and positive evidence
  is found (`NO_MATCH` intent or `Generation complete` marker)
- **Reports indeterminate/oracle failure** when `expect_llm_fallthrough=True` but no
  forbidden intent and no fallthrough evidence (possible observability gap or timeout)
- **Passes without evidence** when `expect_llm_fallthrough=False` and no forbidden
  intent observed — "didn't fire" is sufficient

**Oracle semantics (priority order):**

| Condition | Result | Rationale |
|-----------|--------|-----------|
| Forbidden intent observed (with or without allowed intent) | `fail` | Forbidden always wins |
| Allowed safe route observed (`allowed_intents` set) | `pass` | Safe native route handled the query |
| `expect_llm_fallthrough=True` + fallthrough evidence | `pass` | LLM handled query correctly |
| `expect_llm_fallthrough=True` + no fallthrough | `indeterminate` | Oracle gap — fails suite exit code |
| `expect_llm_fallthrough=False` + no forbidden | `pass` | "Didn't fire" is sufficient |

**Status values for false-positive results:**

| Status | Meaning |
|--------|---------|
| `pass` | No forbidden intent + pass criteria met (fallthrough, allowed route, or no requirement) |
| `fail` | A forbidden intent was triggered (product regression) |
| `indeterminate` | No forbidden intent but required fallthrough evidence missing (oracle gap) |
| `xfail` / `xpass` | Same semantics as normal tests, applied to false-positive assertions |
**Failure buckets:**

| Bucket | Meaning |
|--------|---------|
| `forbidden_intent_fired` | A forbidden intent was dispatched (product regression) |
| `false_positive_no_fallthrough` | No forbidden intent but no LLM fallthrough evidence (observability concern) |

**Run commands:**

```bash
# Preview without a device
python3 scripts/adb_skill_test.py --dry-run --phases false_positives
python3 scripts/adb_skill_test.py --dry-run --categories false_positive

# Run on device (selective — ~2 min for 16 cases)
python3 scripts/adb_skill_test.py --phases false_positives

# With S21 USB ADB
ANDROID_SERIAL=<S21_SERIAL> python3 scripts/adb_skill_test.py --phases false_positives
```

16 false-positive cases + 1 complementary positive test in the `lists` phase
(*"Don't forget to add milk"* routes to `add_to_list`, not `save_memory`).

### On-device validation

Run the harness against a physical device by setting `ANDROID_SERIAL`:

```bash
# USB-connected device (serial from `adb devices`)
ANDROID_SERIAL=R5CR605B71K python3 scripts/adb_skill_test.py --phases=llm_tools

# Wireless device (IP:port from `adb connect`)
ANDROID_SERIAL=100.76.134.49:44599 python3 scripts/adb_skill_test.py --phases=llm_tools
```

**Reference and tracked devices:**

| Device | SoC | RAM | Inference backend | Role |
|--------|-----|-----|-------------------|------|
| Samsung Galaxy S23 Ultra | Snapdragon 8 Gen 2 (SM8550) | 12 GB | NPU (Adreno GPU fallback) | Reference device — primary target |
| Samsung Galaxy S21 (Exynos) | Exynos 2100 | 8 GB | GPU | Tracked reliability signal — see #1089 / #684 |
| Honor Magic8 Pro | Snapdragon 8 Elite Gen 5 | 12 GB | NPU | Experimental reference candidate (Android API 36) |
| Google Pixel 10 | Tensor G5 | 12 GB | GPU | Reference device — GPU-only |

See [`docs/adb-testing.md`](./adb-testing.md) for device setup, USB/wireless debugging, and
gotcha troubleshooting.

**Timer/alarm cleanup:** The `false_positives` phase includes a `Set a 5 minute egg timer`
case that can legitimately route to `set_timer` as an allowed safe native route. When this
happens, a real 5-minute timer is created on the device. The harness performs automatic
clock alert cleanup using **checked ADB commands** (verified via exit code):

1. **Pre-run:** Cancels any active Jandal ClockAlertService alerts and force-stops
   third-party clock packages. Uses checked ADB commands — if this fails the run aborts
   with exit code **46** to avoid buzzing the device during testing.
2. **Post-case:** After any test case that routes to a timer or alarm intent, cleanup is
   attempted immediately. If cleanup fails the failure is tracked and causes a non-zero
   exit code at the end of the run.
3. **Post-run:** Final cleanup stops all timer/alarm alerts, dismisses notifications, and
   force-stops clock packages as a last resort. Failure returns exit code **46**.
4. **On cleanup failure:** The harness returns exit code **46** (EXIT_CLEANUP_FAILED) and
   prints detailed error output including which ADB command failed and why.

If cleanup fails and the device is still buzzing after a run:
```bash
adb shell am force-stop com.kernel.ai.debug
```

This is tracked in [#1275](https://github.com/NickMonrad/kernel-ai-assistant/issues/1275)
for the product-side fix (timers should auto-stop, alarms should auto-snooze/expire).

**Expected pass-rate variance:** Model tool-call generation reliability differs across SoCs
and inference backends. A case that passes on S23 Ultra NPU may fail on S21 Exynos GPU due
to differences in model output distribution across backends. Track device-specific flakes
in issues, not by relaxing harness assertions.

### CI vs on-device evidence

| Validation type | What it verifies | Limitations |
|----------------|-----------------|-------------|
| `--dry-run` | Phase definitions, test structure, CLI parsing | No device interaction, no model inference |
| Unit tests (Gradle) | Kotlin logic, routing, parsing | No E2E device behaviour |
| On-device run | Full E2E model tool-call generation | Requires physical device; results vary by SoC/backend |
| CI (GitHub Actions) | Build, lint, unit tests | No physical device available — no `--phases=llm_tools` or live ADB tests |

> **CI cannot validate physical-device model/tool-call reliability** unless a self-hosted
> device runner is added. On-device results must be reported separately from CI results.
> Model/tool-call generation flakes must not be hidden as harness passes.

Evidence normalisation and a dashboard view (combining CI and on-device runs, tracking
pass-rate trends per device) are tracked in [#1113](https://github.com/NickMonrad/kernel-ai-assistant/issues/1113).


### Acoustic wake reliability evidence

The runner writes private state beneath `scripts/private-acoustic-runs/<run-id>/` and
exports a publishable bundle beneath its `sanitized/` directory only after cleanup.
The bundle contains `evidence-<target>.json`, `run-summary.md`, and only the artifacts
explicitly referenced by the evidence record. Raw ADB selectors, private paths, device
boot IDs, credentials, and unreferenced raw artifacts must not enter this tree.

Local contract checks:

```bash
python3 -m py_compile scripts/acoustic_wake_reliability_runner.py
python3 -m unittest scripts.tests.test_acoustic_wake_reliability_runner
python3 scripts/acoustic_wake_reliability_runner.py fixture
```

Physical modes require distinct `--source-selector` and `--target-selector` values.
Run `preflight` first; later `smoke`, `diagnostic`, `regression`, and `feasibility`
modes consume its hash-verified `--preflight-manifest`. `smoke` is one bounded
wake-only position. `diagnostic` preserves valid failures. `feasibility` is always
non-gating. Only a complete, provenance-verified S21 `regression` run can report
`release_gate_success: true`.

The runner emits the authoritative normalised record directly; do not pass it through
a lossy ad-hoc converter. Validate the generated `evidence-<target>.json` against
`scripts/testdata/test_evidence.schema.json`, then publish only when explicitly
instructed, using the normal publisher and the actual Pull Request number:

```bash
python3 scripts/publish_test_evidence.py \
  --input-dir scripts/private-acoustic-runs/<run-id>/sanitized \
  --source on_device \
  --pr <pull-request-number> \
  --commit <tested-commit-sha>
```

The evidence schema keeps matrix completeness, behavioural success, and release-gate
success separate. Dashboard and metric consumers render required-position completion,
valid pass/fail/invalid counts, target-device clock-domain latencies, failure buckets,
cleanup/provenance status, and referenced artifacts without changing existing `1.0`
suite behaviour.

## Evidence lifecycle

The current test evidence lifecycle is:

```text
CI generates normalised evidence artifact
  → reviewer publishes selected snapshot via Publish test evidence workflow
  → evidence lands in test-results branch
  → dashboard auto-refreshes via repository_dispatch
```

For on-device evidence:


```text
ADB harness run produces JSON report
  → agent normalises report to evidence schema (when instructed)
  → agent publishes locally with scripts/publish_test_evidence.py
  → evidence lands in test-results branch
  → agent may manually trigger Publish test dashboard workflow if gh auth is available and user instructed publication
```

**Key points:**

- Evidence publishing is reviewer/agent-controlled, not automatic. Agents gather metadata and publish when instructed.
- CI and on-device evidence are kept distinct in the schema (`source` field).
- Publishing is not a merge gate — PRs can merge without published evidence.
- The dashboard is a historical record and trend view, not a CI gate.

Detailed agent guidance: [`.docs/agents/test-evidence-workflow.md`](../.docs/agents/test-evidence-workflow.md).
Evidence schema reference: [`docs/testing/test-evidence-schema.md`](./testing/test-evidence-schema.md).

### Failure interpretation

| Failure pattern | Meaning | Likely cause |
|---|---|---|
| No native/legacy tool marker | Model did not call any tool | Model missed the tool-call form in its output |
| Wrong actual tool | Model called a different tool than expected | Intent confusion from the LLM |
| Missing `tool_chip_visible` | UI chip for the tool call not found | UI rendering delay, missing chip, or timing issue |
| Missing `llm_tools_skill_result` | Tool execution result not observed | Result logging missing or routing failure |
| Wrong result mode | Expected `success` / `direct_reply` / `failure` mismatch | Tool implementation divergence |
| `NO_MATCH` / conversational response | Model gave plain-text instead of a tool call | Model/tool-call generation miss, not a harness bug |
| Retry marker present | Unexpected hallucination retry path triggered | Spurious retry from the model |
| Slot-fill marker present | QIR slot-fill path used instead of LLM tool call | Prompt did not stay on the expected LLM tool-call path |
| `forbidden_intent_fired` | False-positive test triggered a forbidden intent | QIR/regex/classifier incorrectly routed an ambiguous phrase |
| `false_positive_no_fallthrough` | No forbidden intent but no LLM generation evidence either | Timeout, observability gap, or harness issue |
| `indeterminate` status | False-positive test with no evidence either way | Oracle observability concern — see [#1272](https://github.com/NickMonrad/kernel-ai-assistant/issues/1272) |

### Runtime markers

The harness reads structured logcat markers emitted by the app. These are the signals that
determine pass/fail for `llm_tools` cases:


| Marker | Report field | When it appears | Required to pass |
|--------|-------------|----------------|:---:|
| `llm_tools_route:` | `route_marker` | After QIR/classifier — confirms query fell through to Gemma | ✓ |
| `llm_tools_native_tool:` | `native_tool_marker` | Tool call dispatched via the native SDK tool-call path | one of these ✓ |
| `llm_tools_legacy_tool:` | `legacy_tool_marker` | Tool call dispatched via legacy Gemma text-format path | one of these ✓ |
| `llm_tools_skill_result:` | `skill_result_marker` | Tool execution result captured (includes tool name, args, mode, success) | ✓ |
| `llm_tools_message_toolcall_saved:` | `message_saved_marker` | Tool call message persisted in chat history (UUID + tool name) | ✓ |
| `tool_chip_visible` (in chip_text) | `chip_text` | UI chip for the tool call is visible on screen | ✓ |
| `raw_tool_call_retry_succeeded` / `hallucination_retry_succeeded` | `retry_seen` | Unexpected retry path activated | must be `false` |
| `NeedsSlot` / `ConfirmationFastPath:` | `slot_fill_seen` | QIR slot-fill or confirmation path used | must be `false` |

## What is still planned

The repository already contains a much larger long-form test specification in
[`docs/testing/automated-test-specification.md`](./testing/automated-test-specification.md),
including proposed UI Automator coverage and future suite expansion. Not every item in that
document is wired into a single runnable repo command yet.

**Recently implemented:**

| Item | Status | Issue/PR |
|------|--------|----------|
| False-positive / negative-routing ADB suite (§2 of test spec) | Implemented as `false_positives` phase (16 cases) | [#1272](https://github.com/NickMonrad/kernel-ai-assistant/issues/1272) |
| Contextual permission-flow connected suite | Implemented as S21-first `permission_flows` UI Automator smoke tests plus #1113 evidence generator | [#1157](https://github.com/NickMonrad/kernel-ai-assistant/issues/1157) |

Treat this file as the "what exists today" index, and the detailed testing specification as
the "where we want to grow next" design document.

## Related docs

| Document | Purpose |
|----------|---------|
| [`docs/testing/README.md`](./testing/README.md) | Index of all testing documentation |
| [`docs/testing/llm-tools-harness.md`](./testing/llm-tools-harness.md) | Deep reference for the `llm_tools` harness |
| [`docs/adb-testing.md`](./adb-testing.md) | Device setup, build & install, logcat filters, benchmarks, and `permission_flows` S21 run instructions |
| [`scripts/adb_skill_test.py`](../scripts/adb_skill_test.py) | The ADB harness script (source of truth for CLI args) |
| [`scripts/generate_permission_flow_evidence.py`](../scripts/generate_permission_flow_evidence.py) | Normalises `permission_flows` connected-test XML into #1113 evidence |
| [`scripts/generate_report.py`](../scripts/generate_report.py) | HTML report generator |
| [#1113](https://github.com/NickMonrad/kernel-ai-assistant/issues/1113) | GitHub-native test evidence dashboard |
| [#1118](https://github.com/NickMonrad/kernel-ai-assistant/issues/1118) | This documentation update |
