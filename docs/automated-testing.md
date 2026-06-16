# Automated Testing

This document is the current automation overview for Kernel AI. It complements the deeper
planning/spec work in [`docs/testing/automated-test-specification.md`](./testing/automated-test-specification.md)
and the device setup guide in [`docs/adb-testing.md`](./adb-testing.md).

## Current automated coverage

| Layer | Tooling | What it covers | Entry point |
|-------|---------|----------------|-------------|
| Unit tests | Gradle + JUnit 5 + MockK | Core Kotlin logic, routing, parsing, repositories, presenters | `./gradlew testDebugUnitTest` |
| Instrumented UI tests | Gradle + Compose/AndroidX test | Compose UI and connected-device Android tests | `./gradlew connectedDebugAndroidTest` |
| Device regression harness | `adb` + Python | End-to-end intent routing, profile extraction, and on-device chat/action flows | `python3 scripts/adb_skill_test.py` |

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
  "elapsed_seconds": 3545.0,
  "summary": { "total": 199, "passed": 73, "xfail": 5, "failed": 121 },
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
      "phase": "alarm_timer",
      "status": "pass"          // "pass", "fail", or "xfail"
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
- **Fails** if any `forbidden_intent` appears in any intent line
- **Passes** only when no forbidden intent is observed **and** positive fallthrough
  evidence is found (`NO_MATCH` intent or `Generation complete` logcat marker)
- **Reports indeterminate/oracle failure** when no forbidden intent fires but no
  fallthrough evidence is observed either (possible observability gap or timeout)

**Status values for false-positive results:**

| Status | Meaning |
|--------|---------|
| `pass` | No forbidden intent triggered + fallthrough evidence confirmed |
| `fail` | A forbidden intent was triggered |
| `indeterminate` | No forbidden intent, but no fallthrough evidence either |
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
| Honor Magic 8 Pro | Snapdragon 8 Elite | 12 GB | NPU | Future tracked / reference candidate |
| Google Pixel 10 | Tensor G5 | 12 GB | GPU | Reference device — GPU-only |

See [`docs/adb-testing.md`](./adb-testing.md) for device setup, USB/wireless debugging, and
gotcha troubleshooting.

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
| False-positive / negative-routing ADB suite (§2 of test spec) | ✅ Implemented as `false_positives` phase (16 cases) | [#1272](https://github.com/NickMonrad/kernel-ai-assistant/issues/1272) |

Treat this file as the "what exists today" index, and the detailed testing specification as
the "where we want to grow next" design document.

## Related docs

| Document | Purpose |
|----------|---------|
| [`docs/testing/README.md`](./testing/README.md) | Index of all testing documentation |
| [`docs/testing/llm-tools-harness.md`](./testing/llm-tools-harness.md) | Deep reference for the `llm_tools` harness |
| [`docs/adb-testing.md`](./adb-testing.md) | Device setup, build & install, logcat filters, benchmarks |
| [`scripts/adb_skill_test.py`](../scripts/adb_skill_test.py) | The harness script (source of truth for CLI args) |
| [`scripts/generate_report.py`](../scripts/generate_report.py) | HTML report generator |
| [#1113](https://github.com/NickMonrad/kernel-ai-assistant/issues/1113) | GitHub-native test evidence dashboard |
| [#1118](https://github.com/NickMonrad/kernel-ai-assistant/issues/1118) | This documentation update |
