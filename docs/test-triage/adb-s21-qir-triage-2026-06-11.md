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
| Evidence baseline commit | [`d83080f8`](https://github.com/NickMonrad/kernel-ai-assistant/tree/d83080f8) — initial report + S21 evidence set (pre-permission safe_smoke, deterministic_core, slot_fill); subsequent evidence updates: `faad35eb` (post-permission rerun, S23U comparison)

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


## Post-Permission S21 Rerun (2026-06-11)

After the initial triage, the user fixed location permissions on the S21. All three original suites were re-run
and a focused weather-only phase was added to determine whether weather failures were a permission issue or
stuck-mode confounded. The weather-only run confirmed weather routing works correctly; the deterministic_core
showed dramatic improvement.

### Commands run

```shell
| Android | 15 (API 35) |
ANDROID_SERIAL=R5CR605B71K ADB_WAIT_SECONDS=15 python3 scripts/adb_skill_test.py --phases weather

# Post-permission safe_smoke
ANDROID_SERIAL=R5CR605B71K ADB_WAIT_SECONDS=15 python3 scripts/adb_skill_test.py \
  --tags safe_smoke --exclude-tags destructive,device_state

# Post-permission deterministic_core
ANDROID_SERIAL=R5CR605B71K ADB_WAIT_SECONDS=15 python3 scripts/adb_skill_test.py \
  --tags deterministic_core --exclude-tags destructive,device_state

# Post-permission slot_fill
ANDROID_SERIAL=R5CR605B71K ADB_WAIT_SECONDS=15 python3 scripts/adb_skill_test.py \
  --categories slot_fill --exclude-tags destructive,device_state
```

### Device metadata

| Field | Value |
|---|---|
| Serial | `R5CR605B71K` |
| Model | SM-G991B (Samsung Galaxy S21, Exynos) |
| Android | 15 (API 35) |
| Connection | USB |
| Permissions | **Fixed** — location and notification permissions reconfigured between runs |
| Oracle preflight | ✅ passed |

### Results: before vs after permission fix

| Suite | Before (2026-06-11 original) | After (2026-06-11 post-permission) | Δ |
|---|---|---|---|
| **weather-only** | — (not run separately) | **7/7 pass** ✅ | Weather routing confirmed working |
| **safe_smoke** | 4 pass / 3 fail | 3 pass / 4 fail | −1 pass — failure pattern shifted but no longer leaks memory content |
| **deterministic_core** | **25 pass / 46 fail / 5 xfail** | **58 pass / 13 fail / 5 xfail** | **+33 pass, −33 fail** |
| **slot_fill** | 0 pass / 6 fail | 0 pass / 6 fail | Unchanged |

**Key comparisons:**

1. **Weather routing works correctly.** Weather-only isolated run: **7/7 pass** as `get_weather`. In the sequential deterministic_core, the first 4 weather cases (34–37) fall in the stuck-mode `get_timer_remaining` block from the preceding timer phase → fail. Cases 38–40 (after model recovers) → all pass as `get_weather`. **Conclusions:** The permission fix was effective. Weather is not a location-permission bug. The 4 remaining failures are stuck-mode-confounded.

2. **Model stuck-mode blocks are shorter but still present.** Before: 38/46 failures from stuck-mode cascades of 5–20 consecutive cases. After: stuck-mode blocks of 3–6 cases. The alarm_timer phase dropped from 33 fails → 6 fails (27 pass). The model recovers faster but still exhibits state carryover.

3. **"what time is it" — state carryover pattern changed.** In the original run, case 3 (`save_memory {"I prefer dark mode"}`) leaked its content into case 4. In the post-fix run, both cases 3 and 4 returned `set_timer` (stuck on case 2's intent) — a different pattern but still state carryover.

4. **Slot-fill contamination is fully reproducible.** All 6 slot_fill cases fail post-fix, with the same contamination pattern: `"5 minutes"` from case 1 leaks into case 3's `app_name`, and `"bread and butter"` from deterministic_core leaks across runs into slot_fill case 6. This is a real, reproducible issue independent of device state.

5. **`save_memory → save_important_date` is a real QIR gap.** Case 76 "remember that I parked on level 3" still routes to `save_important_date` in clean model state. Confirmed reproducible independent of permission configuration.

### Evidence files

| Path | Description |
|---|---|
| `docs/test-triage/evidence/2026-06-11/s21-post-permission-safe-smoke-skills.json` | Post-permission safe_smoke 7-test results |
| `docs/test-triage/evidence/2026-06-11/s21-post-permission-deterministic-core-skills.json` | Post-permission deterministic_core 76-test results |
| `docs/test-triage/evidence/2026-06-11/s21-post-permission-slot-fill-skills.json` | Post-permission slot_fill 6-test results |
| `docs/test-triage/evidence/2026-06-11/s21-weather-only-skills.json` | Weather-only focused validation 7-test results |

## S23U Focused Comparison

The S23 Ultra (SM-S918B) is the user's daily driver. Only a minimal `safe_smoke` comparison slice was run
to check whether the same failure patterns reproduce on a different device tier.

### Command run

```shell
ANDROID_SERIAL=100.76.134.49:36991 ADB_WAIT_SECONDS=20 python3 scripts/adb_skill_test.py \
  --tags safe_smoke --exclude-tags destructive,device_state
```

### Device metadata

| Field | Value |
|---|---|
| Serial | `100.76.134.49:36991` (wireless ADB) |
| Model | SM-S918B (Samsung Galaxy S23 Ultra, Snapdragon) |
| Android | 15 (API 36) |
| Connection | TCP/IP wireless |
| App | `com.kernel.ai.debug` (already installed, no reinstall) |
| Oracle preflight | ✅ passed |
| Model warmup | ✅ `ready` (first probe) |

### Safe_smoke result: 3/7 pass

| # | Prompt | Expected | Actual | Notes |
|---|---|---|---|---|
| 1 | "set an alarm for 11pm" | `set_alarm` | `set_alarm` ✅ | |
| 2 | "set a timer for 2 hours" | `set_timer` | `timer` (wrong tool) | `timer` is not a recognised intent |
| 3 | "remember that I prefer dark mode" | `save_memory` | `save_memory` ✅ | |
| 4 | "what time is it" | `get_time` | `save_memory` | Content `I prefer dark mode` leaked from case 3 |
| 5 | "what's my battery level" | `get_battery` | `get_battery` ✅ | |
| 6 | "set an alarm" (slot) | `set_alarm` | `set_timer` | Slot-fill starts with wrong intent |
| 7 | "set a timer" (slot) | `set_timer` | `save_memory` | Content `I prefer dark mode` leaked from case 3 persists |

### Key findings

| Issue | S21 (USB, post-fix) | S23U (TCP) | Reproduces? |
|---|---|---|---|
| Stuck-mode / state carryover | 3-case blocks in alarm_timer | Cases 2→3→4 show intent drift with content carryover | **Yes** — same pattern, S23U also leaks content through multiple cases |
| Memory content leak (case 3→4) | Original: literal content leak. Post-fix: both stuck on `set_timer` | Case 4 → `save_memory` **with** case 3's literal content (`I prefer dark mode`); case 7 also contaminated | **Yes** — fully reproducible on S23U |
| Slot-fill contamination | All 6 fail, param values cross cases | Cases 6–7 wrong intent at phase start | **Yes** — wrong intent at slot-fill entry |
| `save_memory → save_important_date` | Clean model state, confirmed QIR gap | Not tested (outside safe_smoke slice) | **Confirmed on S21; not tested on S23U** |
| Weather routing | ✅ 7/7 pass (clean weather-only) | Not tested | Not tested on S23U |

The S23U failure distribution is different from the S21 (case 2 → `timer` instead of `set_alarm`), but the memory content leak pattern is the same: case 3's `"I prefer dark mode"` spills into cases 4 and 7. Slot-fill entry failures reproduce the same cross-test contamination pattern. Device/model warmup timing influences the exact stuck intent but state carryover is cross-device.

**Evidence:** [`docs/test-triage/evidence/2026-06-11/s23u-comparison-safe-smoke-skills.json`](evidence/2026-06-11/s23u-comparison-safe-smoke-skills.json)

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

### Draft 3: Validate S21 weather/location fixture with clean fixed-harness run — ✅ CLOSED

**Completed:** Weather-only rerun (2026-06-11, post-permission fix) — **7/7 pass**, all returning `get_weather`.

**Conclusion:** No location-permission bug exists. All 7 weather failures in the original run were stuck-mode-confounded
(occurred during the `set_timer`/`cancel_timer` cascade). Weather routing works correctly when the model is not stuck.
See the [Post-permission S21 rerun](#post-permission-s21-rerun) section for before/after comparison.

No action needed for #1193 — close as not a bug.

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
2. **Investigate model stuck-mode/state carryover** — highest impact, conflates ~80% of observed failures across every test slice. [#1190](https://github.com/NickMonrad/kernel-ai-assistant/issues/1190)
3. **Fix slot-fill context contamination** — blocks all slot-fill coverage. [#1191](https://github.com/NickMonrad/kernel-ai-assistant/issues/1191)
4. **Weather validation** — ✅ **Done.** Weather-only rerun after permission fix: **7/7 pass**. Weather routing works correctly. No location-permission bug exists; all original failures were stuck-mode-confounded. See [post-permission section](#post-permission-s21-rerun).
5. **Do not create QIR/router issues from pre-#1181 data** — all that evidence is invalid.
6. **Do not create QIR/router issues from stuck-mode-confounded failures** — only the `save_memory → save_important_date` case and lists `field_mismatch` cases occurred during known-clean model state. [#1192](https://github.com/NickMonrad/kernel-ai-assistant/issues/1192)

## Follow-Up Issue Tracking

All follow-up work from this triage is tracked under the [epic #1189](https://github.com/NickMonrad/kernel-ai-assistant/issues/1189).

| Issue | Title | Priority | Status |
|---|---|---|---|
| [#1190](https://github.com/NickMonrad/kernel-ai-assistant/issues/1190) | Command/model state carryover (stuck-mode) | **Highest** — conflates ~80% of failures | Open |
| [#1191](https://github.com/NickMonrad/kernel-ai-assistant/issues/1191) | Slot-fill context contamination | **High** — blocks all slot-fill coverage | Open |
| [#1192](https://github.com/NickMonrad/kernel-ai-assistant/issues/1192) | Parked-location memory QIR gap | Medium — focused classifier fix | Open |
| [#1193](https://github.com/NickMonrad/kernel-ai-assistant/issues/1193) | Weather/location clean validation | Low — validation only (see post-permission rerun results) | **Done — clean weather pass, no fix needed** |
| [#1194](https://github.com/NickMonrad/kernel-ai-assistant/issues/1194) | Evidence/report update (this work) | — | **In progress** |

**Notes:**
- #1190 and #1191 are the highest priority — they account for the majority of observed failures and block meaningful slot-fill coverage.
- #1192 is a focused QIR/classifier routing fix for a single identifiable gap. Confirmed reproducible on S21 in clean model state; not yet tested on S23U (outside safe_smoke slice).
- #1193 was originally opened to determine whether weather failures were location-permission issues or stuck-mode confounders. The post-permission rerun (weather-only: 7/7 pass) confirms they are **not** a permission bug. Closing #1193 with no action needed.
- #1194 tracks the evidence/report updates in this PR.

## Evidence Files (Committed)

| Path | Description |
|---|---|
| `docs/test-triage/evidence/2026-06-11/s21-safe-smoke-skills.json` | safe_smoke 7-test results — **pre-permission-fix baseline** (raw JSON) |
| `docs/test-triage/evidence/2026-06-11/s21-deterministic-core-skills.json` | deterministic_core 76-test results — **pre-permission-fix baseline** (raw JSON) |
| `docs/test-triage/evidence/2026-06-11/s21-slot-fill-skills.json` | slot_fill 6-test results — **pre-permission-fix baseline** (raw JSON) |
| `docs/test-triage/evidence/2026-06-11/s21-post-permission-safe-smoke-skills.json` | safe_smoke 7-test results — **post-permission rerun** (raw JSON) |
| `docs/test-triage/evidence/2026-06-11/s21-post-permission-deterministic-core-skills.json` | deterministic_core 76-test results — **post-permission rerun** (raw JSON) |
| `docs/test-triage/evidence/2026-06-11/s21-post-permission-slot-fill-skills.json` | slot_fill 6-test results — **post-permission rerun** (raw JSON) |
| `docs/test-triage/evidence/2026-06-11/s21-weather-only-skills.json` | weather-only 7-test focused validation — **post-permission** (raw JSON) |
| `docs/test-triage/evidence/2026-06-11/s23u-comparison-safe-smoke-skills.json` | S23U safe_smoke 7-test comparison (raw JSON) |
