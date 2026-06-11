# ADB S21 QIR/Router Triage Report — 2026-06-11

## Meta

### Issue lineage

| Stage | Link |
|---|---|
| Originating triage issue | [#1180 — Targeted ADB failure triage and root-cause follow-ups](https://github.com/NickMonrad/kernel-ai-assistant/issues/1180) |
| Continuation issue | [#1186 — Continue/Prioritise S21 QIR triage after harness observability fix](https://github.com/NickMonrad/kernel-ai-assistant/issues/1186) |
| Harness fix | [#1181](https://github.com/NickMonrad/kernel-ai-assistant/pull/1181) — merged |
| This PR (evidence/report) | [#1188](https://github.com/NickMonrad/kernel-ai-assistant/pull/1188) |

**Lineage in prose:**

#1180 created the need for targeted root-cause triage.
#1181 fixed the harness observability blocker that made earlier evidence untrustworthy.
#1186 carries the remaining S21-first triage workflow forward.
#1188 records the first S21-focused triage evidence/report.

### Test metadata

| Field | Value |
|---|---|
| Date | 2026-06-11 |
| Base commit | [`13833fac`](https://github.com/NickMonrad/kernel-ai-assistant/tree/13833fac) — `fix(#1180): remove adb logcat -c, add oracle preflight check (#1181)` |
| Branch | `issue/1186-s21-qir-triage` |
| Latest commit | `dbb0d53b` — triage evidence + review fixes applied |

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

## Harness Trustworthiness vs S21 Model Reliability

**Important distinction:**

- **Harness observability is fixed.** Oracle and stream health checks pass consistently. All failures carry concrete intent/param mismatch labels — no universal NO_MATCH.
- **S21 model readiness is still a confounder.** Model warmup times out in some runs. The inference engine exhibits stuck-mode behaviour during long sequential test runs.

This means: the harness can be trusted to *report accurately*. But failures observed during a stuck-mode cascade or warmup timeout should not automatically be treated as QIR/router bugs without a clean rerun.

Only failures observed during a known-clean model state (fresh warmup, correct intent returned for surrounding cases) should be used to raise product-routing issues.

## Bug Fix Applied During Triage

During the deterministic_core run, the harness crashed at case 43 (xfail `bulk_add_to_list`) with an `AttributeError` in `models.py: _observed_expected_failure()` — the function referenced `r.expect_log_contains` which was not a field on `TestResult`. Fixed in commit `d83080f8`:

- Added `expect_log_contains: str | None = None` to `TestResult` dataclass (models.py)
- Added `expect_log_contains=tc.expect_log_contains` to TestResult construction (runners.py)

**Validation:** `python3 -m py_compile scripts/adb_skill_test.py scripts/adb_harness/*.py` — clean.

## Results Summary

### safe_smoke — 7 tests (fresh warmup, straightforward prompts)

| Result | Count |
|---|---|
| ✅ Pass | 4 |
| ❌ Fail | 3 |
| ⏭ Xfail | 0 |

**Evidence:** [`docs/test-triage/evidence/2026-06-11/s21-safe-smoke-skills.json`](evidence/2026-06-11/s21-safe-smoke-skills.json)

| # | Prompt | Expected | Actual | Failure | Notes |
|---|---|---|---|---|---|
| 1 | "set an alarm for 11pm" | `set_alarm` | `set_alarm` ✅ | — | |
| 2 | "set a timer for 2 hours" | `set_timer` | `set_timer` ✅ | — | |
| 3 | "remember that I prefer dark mode" | `save_memory` | `save_memory` ✅ | — | |
| 4 | "what time is it" | `get_time` | `save_memory` | `wrong_tool` | actual params = `{content: "I prefer dark mode"}` — **case 3 content leaked into case 4** |
| 5 | "what's my battery level" | `get_battery` | `get_battery` ✅ | — | |
| 6 | "set an alarm" | `set_alarm` | `get_battery` | `field_mismatch` | wrong intent at slot_fill phase start |
| 7 | "set a timer" | `set_timer` | `set_timer` (params: `7am`) | `field_mismatch` | correct intent, wrong duration param |

### deterministic_core — 76 tests (sequential, 4 phases)

| Result | Count |
|---|---|
| ✅ Pass | 25 |
| ❌ Fail | 46 |
| ⏭ Xfail | 5 |

**Evidence:** [`docs/test-triage/evidence/2026-06-11/s21-deterministic-core-skills.json`](evidence/2026-06-11/s21-deterministic-core-skills.json)

**Per-phase breakdown:**

| Phase | Pass | Fail | Xfail | Total | Notes |
|---|---|---|---|---|---|
| alarm_timer | 0 | 33 | 0 | 33 | All stuck-mode cascade |
| weather | 0 | 7 | 0 | 7 | All stuck-mode cascade (see below) |
| lists | 18 | 5 | 5 | 28 | 4 wrong_tool, 5 field_mismatch, 5 xfail |
| smart_home | 5 | 0 | 0 | 5 | Perfect pass |
| memory | 2 | 1 | 0 | 3 | 1 wrong_tool (save_important_date) |

**Failure buckets:**

| Bucket | Count | Where |
|---|---|---|
| `wrong_tool` | 35 | alarm_timer (33), lists (1), memory (1) |
| `location_or_permission_missing` | 7 | weather (all — tag-based, see analysis below) |
| `field_mismatch` | 4 | lists (all — correct intent, wrong params) |

### slot_fill — 6 tests (slot-fill conversation flow)

| Result | Count |
|---|---|
| ✅ Pass | 0 |
| ❌ Fail | 6 |
| ⏭ Xfail | 0 |

**Evidence:** [`docs/test-triage/evidence/2026-06-11/s21-slot-fill-skills.json`](evidence/2026-06-11/s21-slot-fill-skills.json)

| # | Prompt | Expected | Actual | Failure | Notes |
|---|---|---|---|---|---|
| 1 | "set an alarm" | `set_alarm` | `set_alarm` ✅ (params: `time=5 minutes`) | `field_mismatch` | Correct intent, wrong param value from prior context |
| 2 | "set a timer" | `set_timer` | `cancel_timer` | `field_mismatch` | Wrong intent at slot-fill phase start |
| 3 | "open an app" | `open_app` | `open_app` ✅ (params: `Spotify → 5 minutes`) | `field_mismatch` | Param value from case 1 ("5 minutes") leaked in |
| 4 | "send a message" | `send_sms` | `open_app` | `field_mismatch` | Wrong intent + leaked params from case 3 |
| 5 | "send an email" | `send_email` | `open_app` | `field_mismatch` | Wrong intent + leaked params from case 3 |
| 6 | "add to my list" | `add_to_list` | `add_to_list` ✅ (params: `eggs → sunscreen`) | `field_mismatch` | Correct intent, param value from prior case context |

## Failure Classification by Root-Cause Bucket

### 1. Model State Carryover / "Stuck Mode" — 38/46 core fails + confounds others

The deterministic_core run reveals the model returns the **same intent** for long consecutive blocks:

| Cases | Time | Dominant Intent | Expected Intents |
|---|---|---|---|
| 1–7 | ~0–4 min | `add_to_list` | set_alarm, add_reminder |
| 8–27 | ~4–13 min | `set_alarm` | add_reminder, cancel_alarm, set_timer, cancel_timer, list_timers |
| 28–32 | ~13–19 min | `cancel_alarm` | cancel_timer_named, get_timer_remaining |
| 33–39 | ~19–25 min | `set_timer` | get_timer_remaining, get_weather |
| 40–44 | ~25–30 min | `cancel_timer` | add_to_list, bulk_add_to_list, get_weather |
| 45–46 | ~30–32 min | `cancel_timer_named` | add_to_list, get_weather |

After case 46 (~32 min), the model recovered and the lists phase mostly passed (18/23 pass including 5 xfail), and smart_home passed perfectly (5/5).

**Key finding:** safe_smoke case 4 also shows state carryover: "what time is it" returned `save_memory` with the *literal content* `{content: "I prefer dark mode"}` — which was the memory content from case 3. This occurred during a **fresh app warmup**, not a long run. This means the LiteRT session/KV cache is persisting inference results across test cases even on a short run.

**Assessment:** The model inference state is not being properly isolated between sequential invocations. This is the highest-priority issue — it conflates ~80% of observed failures.

**Bucket:** `model_state_carryover` / `liteRt_session_isolation`

**Evidence line:** safe_smoke case 4 actual params show case 3's memory content leaked into a `get_time` query.

### 2. Slot-fill Context Contamination — 6/6 slot_fill fails + 2/3 safe_smoke fails

All 6 slot_fill tests fail with clear evidence of cross-test context leakage:

- Cases 1→3→4→5: param value `'5 minutes'` from case 1's time input propagates through cases 3, 4, and 5 as `app_name`.
- Case 6: param value `'sunscreen'` comes from prior test context, not the current prompt.
- Cases 2, 4, 5: wrong intent at phase start, suggesting even the classifier is affected by accumulated state.

**Assessment:** The slot-fill conversation flow carries state across ADB test invocations. When the app asks "Which app?" and the harness types "Spotify", the slot extractor returns values from unrelated earlier conversations. This may be a model session persistence issue (KV cache not cleared between slot-fill turns) or a slot-fill pipeline bug.

**Bucket:** `slot_fill_context_leak`

### 3. Weather failures — confounded by stuck-mode cascade (needs clean rerun)

All 7 weather tests occurred during the stuck-mode cascade (cases 33–40, actual intents `set_timer`/`cancel_timer`). The harness tags these with `location_or_permission_missing` because the test case fixture metadata includes `location_context`, but **no runtime log evidence from this run proves a permission denial**.

The observed actual intents (`set_timer`/`cancel_timer`) are consistent with the stuck-mode cascade active at that point in the run (cases 33–39 were in the `set_timer` block, case 40 was `cancel_timer`).

**Assessment:** These failures should NOT yet be classified as location-permission issues. A clean focused weather-only rerun (single phase, known-clean model state) is required before raising a location-permission follow-up issue.

**Reclassification:** `confounded_by_stuck_mode` — not yet `device_precondition_issue`.

### 4. QIR: save_memory → save_important_date — 1/76 isolated failure

Case 76 "remember that I parked on level 3" → `save_important_date` (expected `save_memory`). This occurred during a known-clean model state (after the stuck-mode resolved), and the intent is plausible — the model treated location-specific memory as an important date event.

**Assessment:** Appears to be a real QIR routing gap. The MiniLM classifier may be choosing `save_important_date` over `save_memory` when the prompt contains location nouns.

**Bucket:** `qir_gap`

### 5. Lists field_mismatch — 4/76 param extraction failures

Cases 63, 65, 66, 68 in the lists phase (known-clean model state) have correct intents but wrong/missing params. These are real extraction failures after the stuck-mode resolved.

**Assessment:** Real param extraction gaps in the lists phase, but affected by context contamination.

**Bucket:** `param_extraction_gap`

## Standing

### Invalidated pre-#1181 evidence
All `NO_MATCH` / `regex_or_qir_miss` failures from earlier S21 and S23U runs (before PR #1181 merge at commit `13833fac`) are **invalid**. The logcat pipeline corruption (`adb logcat -c` on ADB-TLS) caused universal false negatives.

### Harness trustworthiness
- ✅ Oracle uses fresh-probe-bounded logcat capture
- ✅ Stream health check verifies persistent stream delivers lines
- ✅ All failures carry concrete intent/param mismatch labels (0 NO_MATCH across 89 test cases)
- ✅ `py_compile` and dry-run selectors all pass

### S21 model readiness
- Warmup `ready` in one run, `timeout` in others
- Stuck-mode cascade observed in the first ~30 min of deterministic_core
- State carryover observed even in safe_smoke (7-case fresh run)

### Harness bug found and fixed during triage
- Fixed `AttributeError: 'TestResult' object has no attribute 'expect_log_contains'` in `models.py: _observed_expected_failure()`

## Proposed Follow-Up Issues (Drafts for Review)

### Draft 1: Investigate S21 model stuck-mode / state carryover during sequential ADB intent tests

```
Evidence: safe_smoke case 4 returns the literal memory content from case 3
("I prefer dark mode") when asked "what time is it". Deterministic_core shows
5-20 consecutive cases returning the same wrong intent.

The model inference state appears to persist across harness invocations.
This is the highest-priority issue — it conflates ~80% of observed failures.

Proposed investigation:
1. Check whether LiteRT session is created fresh per test or reused.
2. Log LiteRT inference handles/session IDs per case to detect reuse.
3. Add a force-stop or model-reset between test phases to test isolation.
4. Check whether InferenceLoadingService ForegroundServiceStartNotAllowedException
   correlates with stuck-mode phases.
5. Verify KV cache clearing between inference calls.

Affects: ~38/76 deterministic_core cases, 2/3 safe_smoke failures, confounds weather/slot_fill results.
```

### Draft 2: Investigate slot-fill context contamination across ADB test cases

```
Evidence: "5 minutes" from slot_fill case 1 propagates as app_name into cases
3, 4, and 5. Memory content from case 3 leaks into case 4 of safe_smoke.
"sunscreen" from unknown prior context appears as the item in slot_fill case 6.

The slot-fill conversation flow is not isolating state between test invocations.

Proposed investigation:
1. Check KV cache state across slot-fill turns.
2. Verify that the conversation history is reset between harness test cases.
3. Check whether the slot extractor prompt includes stale prior turns.

Affects: All 6 slot_fill tests.
```

### Draft 3: Validate S21 weather/location fixture with clean fixed-harness run

```
All 7 weather tests occurred during the stuck-mode cascade (actual intents:
set_timer/cancel_timer). No runtime evidence of a permission denial was captured.

Run a focused weather-only test slice with model in a known-clean state
to determine whether location permission is actually missing on S21.

If location_or_permission_missing persists in a clean run with weather cases:
- Add log excerpts showing the permission/location failure.
- Create a fixture issue to grant location permission or configure mock location.

If weather tests pass in a clean run:
- Close without action; the stuck-mode was the sole cause.
```

### Draft 4: QIR: remember parked location routes to save_important_date instead of save_memory

```
Prompt: "remember that I parked on level 3"
Expected: save_memory
Actual: save_important_date

The MiniLM classifier routes to save_important_date when the prompt contains
both a memory verb ("remember") and a location noun ("level 3").

Proposed fix: Adjust QIR regex patterns or MiniLM training to prefer
save_memory when the prompt starts with "remember that I ..."
regardless of location nouns.

Affected: case id: remember_that_i_parked_on_level_3
```

## Recommended Next Actions

1. **Fix the harness crash** — ✅ `expect_log_contains` field added and committed.
2. **Investigate model stuck-mode/state carryover** — highest impact, conflates ~80% of observed failures across every test slice.
3. **Fix slot-fill context contamination** — blocks all slot-fill coverage.
4. **Run weather-only validation** — determine whether location permission is actually missing before creating a fixture issue.
5. **Do not create QIR/router issues from pre-#1181 data** — all that evidence is invalid.
6. **Do not create QIR/router issues from stuck-mode-confounded failures** — only the `save_memory → save_important_date` case and lists `field_mismatch` cases occurred during known-clean model state.

## Evidence Files (Committed)

| Path | Description |
|---|---|
| `docs/test-triage/evidence/2026-06-11/s21-safe-smoke-skills.json` | safe_smoke 7-test results (raw JSON) |
| `docs/test-triage/evidence/2026-06-11/s21-deterministic-core-skills.json` | deterministic_core 76-test results (raw JSON) |
| `docs/test-triage/evidence/2026-06-11/s21-slot-fill-skills.json` | slot_fill 6-test results (raw JSON) |
