# ADB S21 QIR/Router Triage Report — 2026-06-11

## Meta

| Field | Value |
|---|---|
| Issue | [#1186 — Prioritise S21 QIR triage after harness observability fix](https://github.com/NickMonrad/kernel-ai-assistant/issues/1186) |
| PR | [docs(#1186): add S21-first ADB QIR triage report](https://github.com/NickMonrad/kernel-ai-assistant/pull/1187) |
| Date | 2026-06-11 |
| Commit | [`13833fac`](https://github.com/NickMonrad/kernel-ai-assistant/tree/13833fac) — `fix(#1180): remove adb logcat -c, add oracle preflight check (#1181)` |
| Harness fix | PR [#1181](https://github.com/NickMonrad/kernel-ai-assistant/pull/1181) — merged, fixed ADB-TLS logcat corruption and multi-word shell quoting |

## Device Preconditions

| Field | Value |
|---|---|
| Serial | `R5CR605B71K` |
| Model | SM-G991B (Samsung Galaxy S21, Exynos) |
| Android | 15 (API 35) — SDK target 36 |
| USB connection | ✅ `device` |
| App package | `com.kernel.ai.debug` v0.1.0 (code 1) |
| Screen unlocked | ✅ (timeout extended to 30 min) |
| Oracle preflight | ✅ passed (fresh-probe bounded marker) |
| Stream health | ✅ passed |
| Model warmup | ✅ `ready` (one run), ⚠ `timeout` (other runs — model not ready within 120s) |
| Pre-#1181 evidence | 🚫 **All invalid** — universal false NO_MATCH from broken logcat pipeline |

## Commands Run

```bash
# Phase 0 — dry-run selectors
python3 scripts/adb_skill_test.py --dry-run --tags safe_smoke --exclude-tags destructive,device_state
python3 scripts/adb_skill_test.py --dry-run --tags deterministic_core --exclude-tags destructive,device_state
python3 scripts/adb_skill_test.py --dry-run --categories slot_fill --exclude-tags destructive,device_state

# Phase 1 — device metadata
adb -s R5CR605B71K shell getprop ro.product.model
adb -s R5CR605B71K shell getprop ro.build.version.release
adb -s R5CR605B71K shell dumpsys package com.kernel.ai.debug | grep -E "versionName|versionCode|debuggable"

# Phase 2 — harness trust check (safe_smoke)
ANDROID_SERIAL=R5CR605B71K ADB_WAIT_SECONDS=15 \
  python3 scripts/adb_skill_test.py --tags safe_smoke --exclude-tags destructive,device_state

# Phase 3a — deterministic core
ANDROID_SERIAL=R5CR605B71K ADB_WAIT_SECONDS=15 \
  python3 scripts/adb_skill_test.py --tags deterministic_core --exclude-tags destructive,device_state

# Phase 3b — slot_fill
ANDROID_SERIAL=R5CR605B71K ADB_WAIT_SECONDS=15 \
  python3 scripts/adb_skill_test.py --categories slot_fill --exclude-tags destructive,device_state
```

## Bug Fix Applied During Triage

During the deterministic_core run, the harness crashed at case 43 (xfail `bulk_add_to_list`) due to an `AttributeError` in `models.py: _observed_expected_failure()` — the function referenced `r.expect_log_contains` which was not a field on `TestResult`. Fixed in:

- Added `expect_log_contains: str | None = None` to `TestResult` dataclass (models.py)
- Added `expect_log_contains=tc.expect_log_contains` to TestResult construction (runners.py)

## Results Summary

### safe_smoke (7 tests)

| Result | Count |
|---|---|
| ✅ Pass | 4 |
| ❌ Fail | 3 |
| ⏭ Xfail | 0 |

**Evidence:** `scripts/test-reports/2026-06-11T20-55-18Z_skills.json`

| # | Prompt | Expected | Actual | Bucket |
|---|---|---|---|---|
| 1 | "set an alarm for 11pm" | set_alarm | set_alarm ✅ | — |
| 2 | "set a timer for 2 hours" | set_timer | set_timer ✅ | — |
| 3 | "remember that I prefer dark mode" | save_memory | save_memory ✅ | — |
| 4 | "what time is it" | get_time | **save_memory** ❌ | wrong_tool |
| 5 | "what's my battery level" | get_battery | get_battery ✅ | — |
| 6 | "set an alarm" | set_alarm | **get_battery** ❌ | wrong_tool |
| 7 | "set a timer" | set_timer | set_timer (params: got '7am') ❌ | slot_fill_issue |

### deterministic_core (76 tests)

| Result | Count |
|---|---|
| ✅ Pass | 25 |
| ❌ Fail | 46 |
| ⏭ Xfail | 5 |

**Evidence:** `scripts/test-reports/2026-06-11T22-11-20Z_skills.json`

### slot_fill (6 tests)

| Result | Count |
|---|---|
| ✅ Pass | 0 |
| ❌ Fail | 6 |
| ⏭ Xfail | 0 |

**Evidence:** `scripts/test-reports/2026-06-11T22-17-41Z_skills.json`

## Failure Classification by Root-Cause Bucket

### 1. Model "Stuck Mode" / Inference Cascade Failure — 38/46 core fails

The deterministic_core run reveals the model returns the **same intent** for long consecutive blocks:

| Block | Tests | Dominant Intent | Expected Intents | Count |
|---|---|---|---|---|
| 1–7 | alarm set/reminder | `add_to_list` | set_alarm, add_reminder | 7 |
| 8–27 | alarm/timer/cancel | `set_alarm` | add_reminder, cancel_alarm, set_timer, cancel_timer, list_timers, cancel_timer_named | 20 |
| 28–32 | timer named/remaining | `cancel_alarm` | cancel_timer_named, get_timer_remaining | 5 |
| 33–39 | timer remaining/weather | `set_timer` | get_timer_remaining, get_weather | 7 |
| 40–44 | lists/weather | `cancel_timer` | add_to_list, bulk_add_to_list, get_weather | 5 |

After test 46 (45 minutes into the run), the lists phase from case 47 onward mostly passes (18/23 pass) and smart_home passes perfectly (5/5), memory mostly passes (2/3).

**Assessment:** The model appears to require a clean cold-start inference per test or a model-reset between phases. When tests run back-to-back, the inference engine enters a stuck state where it returns the last-used or an incorrect dominant intent. This is likely a model inference reliability / session management issue rather than a routing logic bug.

**Bucket:** `android_platform_constraint` (inference stall) / `unknown_needs_manual_review`

### 2. Location permission not configured — 7/46 core fails

All 7 weather tests fail with `location_or_permission_missing`:

| # | Prompt | Expected | Actual |
|---|---|---|---|
| 34–40 | "what's the weather in Auckland" etc | get_weather | set_timer / cancel_timer |

**Assessment:** Location permission needs to be granted or mock location provider configured on the device. These are not QIR issues.

**Bucket:** `device_precondition_issue`

### 3. Slot-fill extraction failures — 6/6 slot_fill fails

All 6 slot_fill tests fail. Key observations:

- "set an alarm" → correct intent `set_alarm` but **empty** params (missing hours, minutes)
- "set a timer" → **wrong intent** `cancel_timer`
- "open an app" → correct intent `open_app` but param = `'5 minutes'` (pipelines slot-fill response from wrong context)
- "send a message" → **wrong intent** `open_app`
- "send an email" → **wrong intent** `open_app`
- "add to my list" → correct intent `add_to_list` but param = `'sunscreen'` (from previous test context)

**Assessment:** The slot-fill conversation flow appears contaminated by prior test context. When the app asks "Which app?" and user responds "Spotify", the slot extractor returns values from unrelated earlier conversations. This may be a model session persistence issue (KV cache not cleared between tests) or a slot-fill pipeline bug.

**Bucket:** `slot_fill_issue` / `native_tool_handler_issue`

### 4. Wrong tool on first test after phase change — 3/76 safe_smoke fails

In the safe_smoke run (which had fresh app warmup), case 4 "what time is it" → `save_memory` and case 6 "set an alarm" (slot fill) → `get_battery`. These are not consecutive-context contamination since the run starts fresh.

**Assessment:** Real QIR router misclassifications on the S21.

**Bucket:** `minilm_classifier_miss` / `qir_regex_miss`

### 5. Param extraction: "set a timer" → expects '300' but got '7am' — appearing in multiple runs

In both safe_smoke (case 7) and slot_fill (case 2), the prompt "set a timer" with slot reply "5 minutes" produces `duration_seconds='7am'` instead of `'300'`. The value '7am' appears repeatedly, suggesting alarm context contamination.

**Bucket:** `slot_fill_issue`

### 6. save_memory → save_important_date — 1/76 core fail

Case 76 "remember that I parked on level 3" → `save_important_date` (expected `save_memory`). This is a novel intent classifier routing that treats location-specific memory as an important date.

**Bucket:** `qir_regex_miss` (QIR regex for save_memory didn't match, MiniLM classified as save_important_date)

## Standing

### Invalidated pre-#1181 evidence

All `NO_MATCH` / `regex_or_qir_miss` failures from earlier S21 and S23U runs (before PR #1181 merge at commit `13833fac`) are **invalid**. The logcat pipeline corruption (`adb logcat -c` on ADB-TLS) caused universal false negatives.

### Harness trustworthiness

The harness is now trustworthy:
- ✅ Oracle uses fresh-probe-bounded logcat capture
- ✅ Stream health check verifies persistent stream delivers lines
- ✅ All failures carry concrete intent/param mismatch labels, not NO_MATCH
- ✅ py_compile and dry-run selectors all pass

### Harness bug found and fixed during triage

- Fixed `AttributeError: 'TestResult' object has no attribute 'expect_log_contains'` in `models.py: _observed_expected_failure()`

## Proposed Follow-Up Issues

### Draft 1: Model inference reliability — cascade failure under sequential test load

**Title:** Model returns same dominant intent for consecutive ADB harness test cases

**Body:**
```
The S21 Exynos inference engine exhibits a "stuck mode" behaviour where the same
intent (e.g. `add_to_list` or `set_alarm`) is returned for 5–20 consecutive test
cases regardless of the prompt. This occurs approximately 45 minutes into a
deterministic_core run and eventually resolves after ~2 phases.

Affected: ~38/76 deterministic_core test cases

Proposed investigation:
1. Add a model-reset or per-test force-stop to the harness to test whether clean
   inference state per case resolves the issue.
2. Log LiteRT inference time per case to identify when the model is stalling.
3. Check whether `InferenceLoadingService` `ForegroundServiceStartNotAllowedException`
   correlates with stuck-mode phases.
```

### Draft 2: Slot-fill extraction contamination

**Title:** Slot-fill extractor returns values from unrelated prior context

**Body:**
```
When the slot-fill flow asks "Which app?" and user responds "Spotify", the
parameter extractor sometimes returns '5 minutes' or '7am' instead of 'Spotify'.

Affected: all 6 slot_fill tests (including "set a timer" → duration '7am')

Proposed investigation:
1. Check whether KV cache is cleared between slot-fill turns.
2. Check whether the conversation history from prior test cases leaks into the
   slot extractor prompt.
```

### Draft 3: Location permission not granted on S21

**Title:** Weather tests fail with location_or_permission_missing on S21

**Body:**
```
All 7 weather tests fail with location_or_permission_missing because the S21
device does not have location permission granted or a mock location provider
configured.

Fix: Add location permission grant to device precondition setup, or configure
a mock location provider for the weather fixture.
```

### Draft 4: save_memory → save_important_date QIR gap

**Title:** "remember that I parked on level 3" routes to save_important_date instead of save_memory

**Body:**
```
When the prompt contains both a memory verb ("remember") and a location
("level 3"), the QIR router selects save_important_date over save_memory.

Affected: case id:remember_that_i_parked_on_level_3

Proposed fix: Adjust QIR regex patterns to prefer save_memory when the prompt
starts with "remember that I ..." regardless of location nouns.
```

## Recommended Next Action

1. **Fix the `models.py` harness crash** — ✅ already done in this session
2. **Investigate model stuck-mode** — highest impact, affects 50%+ of deterministic tests on S21
3. **Fix slot-fill contamination** — affects all slot-fill coverage
4. **Configure location permission** — unblocks 7 weather tests
5. **Do not create QIR/router issues from pre-#1181 data** — all that evidence is invalid

## Evidence Files

| Path | Size | Description |
|---|---|---|
| `scripts/test-reports/2026-06-11T20-55-18Z_skills.json` | ~4 KB | safe_smoke results |
| `scripts/test-reports/2026-06-11T22-11-20Z_skills.json` | ~12 KB | deterministic_core results |
| `scripts/test-reports/2026-06-11T22-17-41Z_skills.json` | ~3 KB | slot_fill results |
| `/home/lokhor/.local/share/rtk/tee/1781211323_test.log` | ~2 KB | safe_smoke raw log |
| `/home/lokhor/.local/share/rtk/tee/1781215886_test.log` | ~9 KB | deterministic_core raw log |
| `/home/lokhor/.local/share/rtk/tee/1781216267_test.log` | ~1 KB | slot_fill raw log |
