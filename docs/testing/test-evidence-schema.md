# Normalised Test Evidence Schema

Status: **Design / Draft**  
Parent: #1113 — GitHub-native test evidence dashboard for CI and on-device results  
Implements: #1115 — Define normalised test result schema and device registry  

---

## 1. Purpose

Every downstream consumer — PR summaries, CI step summaries, GitHub Pages dashboards, release snapshots, regression analysis — reads data in this shape. By normalising all test sources (CI, physical-device `llm_tools`, skills harness) into one schema, the dashboard and tools can compare results across commits, PRs, releases, suites, devices, models, and runtimes.

## 2. Sources

Every normalised result carries an explicit `source` field:

| Value | Meaning |
|---|---|
| `on_device` | Real physical device. ADB serial, SoC, model/runtime metadata included. |
| `ci` | GitHub Actions runner or other CI host. No model inference performed. |

These two must never be conflated. CI can validate build/static/dry-run results only; on-device results capture model behaviour, tool-call generation, and runtime reliability.

## 3. Device registry

Device metadata is defined in `scripts/testdata/devices.yaml`. Keyed by short device ID (`s23-ultra`, `s21-exynos`, `ubuntu-latest`, etc.).

Tiers:

| Tier | Meaning |
|---|---|
| `reference` | Can block merge/release once stable |
| `tracked` | Non-blocking reliability signal |
| `experimental` | Exploratory only |
| `ci` | Not a physical device — CI runner |

Fields: `label`, `manufacturer`, `model`, `soc`, `tier`, `android_api`, `execution`.

## 4. Schema

### Top-level

```json
{
  "schema_version": "1.0",
  "source": "on_device",
  "suite": "llm_tools",
  "timestamp": "2026-06-08T02:31:34Z",
  "repo": "NickMonrad/kernel-ai-assistant",
  "branch": "feature/1107-llm-tools-harness",
  "commit": "29c56a12dadc88e1464c0fe5d1ff32130782cea5",
  "pr": 1111,
  "release": null,
  "run_id": "local-2026-06-08T02-31-34Z-S23",
  "device": { },
  "model": { },
  "summary": { },
  "cases": []
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `schema_version` | string | yes | Semver for the schema. `"1.0"` initially, bumped on breaking changes. |
| `source` | string | yes | `"on_device"` or `"ci"`. Never both. |
| `suite` | string | yes | Test suite identifier, e.g. `"llm_tools"`, `"skills"`, `"unit"`. |
| `timestamp` | string (ISO 8601) | yes | When the test run started (`2026-06-08T02:31:34Z`). |
| `repo` | string | yes | GitHub `"owner/repo"`. |
| `branch` | string | yes | Git branch the run was against. |
| `commit` | string (SHA) | yes | Full 40-character commit SHA. |
| `pr` | number or null | yes | PR number, or `null` for non-PR runs (local development, release branches). |
| `release` | string or null | yes | Release tag, or `null`. |
| `run_id` | string | yes | Unique identifier. Convention: `"<source>-<timestamp>-<device-id>"`. |
| `device` | object | yes | Device or runner metadata. See §4.1. |
| `model` | object | yes | Model/runtime metadata. See §4.2. |
| `summary` | object | yes | Pass/fail counts. See §4.3. |
| `cases` | array | yes | Per-case results. See §4.4. |

### 4.1 `device` object

**Physical device example:**

```json
{
  "device": {
    "id": "s23-ultra",
    "serial": "100.76.134.49:44599",
    "label": "S23 Ultra",
    "manufacturer": "Samsung",
    "model": "SM-S918B",
    "soc": "Snapdragon 8 Gen 2",
    "tier": "reference",
    "android_api": 35,
    "execution": "physical"
  }
}
```

**CI runner example:**

```json
{
  "device": {
    "id": "ubuntu-latest",
    "serial": null,
    "label": "ubuntu-latest",
    "manufacturer": "GitHub",
    "model": "Actions runner",
    "soc": "x86_64",
    "tier": "ci",
    "android_api": null,
    "execution": "github_hosted_runner"
  }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes | Short device key matching device registry. |
| `serial` | string or null | no | ADB serial or TCP address for physical devices; null for CI. |
| `label` | string | yes | Human-readable device name. |
| `manufacturer` | string | yes | OEM name. |
| `model` | string | yes | Device model code. |
| `soc` | string | yes | SoC identifier. |
| `tier` | string | yes | `reference`, `tracked`, `experimental`, or `ci`. |
| `android_api` | number or null | yes | API level or null for non-Android environments. |
| `execution` | string | yes | `physical` for real devices, `github_hosted_runner` for CI. |

### 4.2 `model` object

```json
{
  "model": {
    "name": "Gemma E4B",
    "runtime": "LiteRT",
    "backend": "GPU"
  }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Model name, e.g. `"Gemma E4B"`, `"EmbeddingGemma-300M"`. |
| `runtime` | string | yes | Inference runtime, e.g. `"LiteRT"`, `"ONNX"`. |
| `backend` | string | yes | Hardware backend: `"GPU"`, `"NPU"`, `"CPU"`. |

For CI runs that do not execute any model, set to:

```json
{
  "model": {
    "name": null,
    "runtime": null,
    "backend": null
  }
}
```

### 4.3 `summary` object

```json
{
  "summary": {
    "total": 3,
    "passed": 3,
    "failed": 0,
    "pass_rate": 1.0
  }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `total` | number | yes | Total number of test cases. |
| `passed` | number | yes | Number of passed cases. |
| `failed` | number | yes | Number of failed cases. |
| `pass_rate` | number | yes | Pass rate as a float between 0.0 and 1.0. |

### 4.4 `cases[]` object

**Passing example:**

```json
{
  "name": "query_wikipedia_natural",
  "passed": true,
  "expected_tool": "query_wikipedia",
  "actual_tool": "query_wikipedia",
  "expected_result_mode": "direct_reply",
  "actual_result_mode": "direct_reply",
  "chip_present": true,
  "skill_result_present": true,
  "message_saved": true,
  "retry_seen": false,
  "slot_fill_seen": false,
  "failure_category": null,
  "failures": []
}
```

**Failing example (model tool-call miss):**

```json
{
  "name": "save_memory_durable_fact",
  "passed": false,
  "expected_tool": "save_memory",
  "actual_tool": null,
  "expected_result_mode": "success",
  "actual_result_mode": "unknown",
  "chip_present": false,
  "skill_result_present": false,
  "message_saved": false,
  "retry_seen": false,
  "slot_fill_seen": false,
  "failure_category": "model_tool_generation_miss",
  "failures": [
    "No tool_chip_visible marker found",
    "tool name: expected 'save_memory', got None",
    "No native-tool or legacy-tool marker found",
    "No ChatMessage.toolCall persistence marker found",
    "result mode: expected 'success', got 'unknown'"
  ]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Case identifier, matches harness test case name. |
| `passed` | boolean | yes | Pass/fail. |
| `expected_tool` | string or null | yes | Tool the test expected. |
| `actual_tool` | string or null | yes | Tool the model actually called. Null on model failures. |
| `expected_result_mode` | string | yes | `"success"` or `"direct_reply"`. |
| `actual_result_mode` | string | yes | `"success"`, `"direct_reply"`, or `"unknown"`. |
| `chip_present` | boolean | yes | Whether the tool chip marker was found in the UI stream. |
| `skill_result_present` | boolean | yes | Whether the skill result marker appeared. |
| `message_saved` | boolean | yes | Whether a `message_saved_marker` was emitted. |
| `retry_seen` | boolean | yes | Whether the retry marker was detected. |
| `slot_fill_seen` | boolean | yes | Whether a slot-fill UI interaction was detected instead of a direct tool call. |
| `failure_category` | string or null | yes | Standardised category for failed cases (see §5). `null` on pass. |
| `failures` | array of strings | yes | Raw failure messages from the harness. Empty on pass. |

## 5. Failure categories

| Category | When it applies |
|---|---|
| `harness_error` | The harness itself encountered an error — setup failure, script crash, missing dependency. |
| `missing_marker` | An expected marker was absent after a tool call was made (chip, skill result, message saved). |
| `model_tool_generation_miss` | The model responded without emitting any tool call — no native or legacy tool marker. |
| `wrong_tool` | The model called a different tool than expected. |
| `wrong_result_mode` | The result mode did not match expectations (e.g. expected `success`, got `direct_reply`). |
| `field_mismatch` | An expected field in the tool call arguments was missing or had the wrong value. |
| `conversational_fallback` | The model responded conversationally instead of using a tool at all. |
| `retry_seen` | A retry loop was triggered during the test case. |
| `slot_fill_seen` | Slot-fill UI was invoked instead of a direct tool call. |
| `device_environment_error` | Device unavailable, app crashed, ADB failure, or environment setup problem. |
| `timeout` | The test case exceeded its allowed runtime. |

### Suggested mapping from raw harness markers

The normalisation script (to be implemented in #1116) should map raw harness fields to these categories as follows:

- No `native_tool_marker` AND no `legacy_tool_marker` → **`model_tool_generation_miss`** (model didn't produce a tool call)
- Tool called but `chip_text` is null → **`missing_marker`** (chip marker)
- Tool called but `skill_result_marker` is null → **`missing_marker`** (skill result)
- `actual_top_level_tool != expected_top_level_tool` → **`wrong_tool`**
- `actual_result_mode != expected_result_mode` → **`wrong_result_mode`**
- Non-empty `retry_seen` after a tool call → **`retry_seen`**
- `slot_fill_seen` after a tool call → **`slot_fill_seen`**
- Timeout failures in harness → **`timeout`**
- Device connection errors → **`device_environment_error`**

### Field transformation table

The normaliser (to be implemented in #1116) should map raw `llm_tools` report fields
(from `save_llm_tools_report()` in `scripts/adb_skill_test.py`) to normalised fields
as shown below. Fields marked "not stored" are consumed during categorisation but do
not appear in the normalised output. Fields marked "(derived)" are computed from
multiple raw fields or harness configuration — the raw report does not contain them
directly.

| Raw field | Normalised field | Transformation |
|---|---|---|
| `expected_top_level_tool` | `expected_tool` | Direct rename |
| `actual_top_level_tool` | `actual_tool` | Direct rename |
| `expected_result_mode` | `expected_result_mode` | (derived) — from harness case config; `#1116` may add to raw reports |
| `actual_result_mode` | `actual_result_mode` | (derived) — parse mode from `skill_result_marker` value (`"success"` / `"direct_reply"`) |
| `chip_text` | `chip_present` | Non-null `chip_text` ⇒ `true`; `null` ⇒ `false` |
| `skill_result_marker` | `skill_result_present` | Non-null marker ⇒ `true`; `null` ⇒ `false` |
| `message_saved_marker` | `message_saved` | Non-null marker ⇒ `true`; `null` ⇒ `false` |
| `retry_seen` | `retry_seen` | Pass-through |
| `slot_fill_seen` | `slot_fill_seen` | Pass-through |
| `failures` | `failures` | Direct rename (list of strings) |
| `native_tool_marker` / `legacy_tool_marker` | (not stored) | Consumed for `model_tool_generation_miss` categorisation |
| `expected_chip_arg` / `expected_skill_arg` | (not stored) | Consumed for `field_mismatch` categorisation |

## 6. Full example — passing run

```json
{
  "schema_version": "1.0",
  "source": "on_device",
  "suite": "llm_tools",
  "timestamp": "2026-06-08T02:31:34Z",
  "repo": "NickMonrad/kernel-ai-assistant",
  "branch": "feature/1107-llm-tools-harness",
  "commit": "29c56a12dadc88e1464c0fe5d1ff32130782cea5",
  "pr": 1111,
  "release": null,
  "run_id": "on_device-2026-06-08T02-31-34Z-s23-ultra",
  "device": {
    "id": "s23-ultra",
    "serial": "100.76.134.49:44599",
    "label": "S23 Ultra",
    "manufacturer": "Samsung",
    "model": "SM-S918B",
    "soc": "Snapdragon 8 Gen 2",
    "tier": "reference",
    "android_api": 35,
    "execution": "physical"
  },
  "model": {
    "name": "Gemma E4B",
    "runtime": "LiteRT",
    "backend": "GPU"
  },
  "summary": {
    "total": 3,
    "passed": 3,
    "failed": 0,
    "pass_rate": 1.0
  },
  "cases": [
    {
      "name": "query_wikipedia_natural",
      "passed": true,
      "expected_tool": "query_wikipedia",
      "actual_tool": "query_wikipedia",
      "expected_result_mode": "direct_reply",
      "actual_result_mode": "direct_reply",
      "chip_present": true,
      "skill_result_present": true,
      "message_saved": true,
      "retry_seen": false,
      "slot_fill_seen": false,
      "failure_category": null,
      "failures": []
    },
    {
      "name": "save_memory_durable_fact",
      "passed": true,
      "expected_tool": "save_memory",
      "actual_tool": "save_memory",
      "expected_result_mode": "success",
      "actual_result_mode": "success",
      "chip_present": true,
      "skill_result_present": true,
      "message_saved": true,
      "retry_seen": false,
      "slot_fill_seen": false,
      "failure_category": null,
      "failures": []
    },
    {
      "name": "get_system_info_natural",
      "passed": true,
      "expected_tool": "get_system_info",
      "actual_tool": "get_system_info",
      "expected_result_mode": "direct_reply",
      "actual_result_mode": "direct_reply",
      "chip_present": true,
      "skill_result_present": true,
      "message_saved": true,
      "retry_seen": false,
      "slot_fill_seen": false,
      "failure_category": null,
      "failures": []
    }
  ]
}
```


## 7. Semantic invariants

These invariants MUST hold for every normalised report. The normalisation script (#1116) guarantees them; any consumer MAY assert them.

### 7.1 Summary counts

- `summary.total == len(cases)`
- `summary.total == summary.passed + summary.failed`
- `summary.pass_rate == passed / total` (when `total > 0`)
- When `total == 0`, `pass_rate` SHOULD be `0.0` (no evidence to rate).

### 7.2 Pass/fail consistency

- When `passed == true`: `failure_category` MUST be `null`, `failures` MUST be empty.
- When `passed == false`: `failure_category` MUST be non-null, `failures` MUST be non-empty.

### 7.3 Tool call fields

- When `passed == true`: `actual_tool` SHOULD equal `expected_tool` (unless the test case explicitly tests for a fallback).
- When `actual_tool` is `null`: `chip_present` and `skill_result_present` MUST be `false`; `message_saved` SHOULD be `false`.
- `chip_present` and `skill_result_present` may each be true only when `actual_tool` is non-null.

### 7.4 Source invariants

- `source == "ci"` → `device.execution == "github_hosted_runner"`, `device.tier == "ci"`, all model fields `null`.
- `source == "on_device"` → `device.execution == "physical"`, model fields are non-null (name, runtime, backend each non-null).

## 8. Related documents

- `scripts/testdata/devices.yaml` — device registry consumed by normalisation scripts
- `scripts/testdata/test_evidence.schema.json` — machine-verifiable JSON Schema
- `docs/testing/automated-testing.md` — test harness documentation
- `docs/testing/llm-tools-harness.md` — `llm_tools` test details
- [Issue #1115](https://github.com/NickMonrad/kernel-ai-assistant/issues/1115) — this issue
- [Issue #1113](https://github.com/NickMonrad/kernel-ai-assistant/issues/1113) — parent epic
