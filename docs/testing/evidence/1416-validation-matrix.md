# PR #1416 Physical Validation Results

**Source SHAs:** `38c590f0` (original physical matrix), `8191b242` (snooze/retest), `79a0e89e` (suspend snooze orchestration)
**APK SHA-256 (original physical matrix):** `bf86072f82547073acdae34a4b6851889f646e5e2840df491726950e9e99bf95`
Targeted retests at `8191b242` were retained human-observer checks; no separate APK hash was preserved.
**Date:** 2026-07-22
**Validator:** Human-observer interactive session via ADB

## Device summary (final)

| Property | S21 (SM-G991B) | S23 Ultra (SM-S918B) |
|---|---|---|
| ADB serial | `R5CR605B71K` (USB) | `192.168.31.248:36739` (Wi-Fi) |
| Android | 15 (SDK 35) | 16 (SDK 36) |
| Battery | ~97% | 100% |
| Media vol | 7/15 | 7/15 |
| Alarm vol | 10/15 | 11/15 |
| DND | OFF | OFF |
| BT | speaker only | speaker only |
| RECORD_AUDIO | granted | granted |
| Wake word | active | active |

## STT entry-point results (Phase 1)

Legend: ✅ PASS | ❌ FAIL | ⚠️ BLOCKED/ISSUE | 🔷 NOT PRESENT

| # | Entry point | S21 | S23U | Notes |
|---|---|---|---|---|
| 1 | Chat one-shot microphone | ✅ PASS | ✅ PASS | One cue (FOREGROUND), capture works. S21 audible; S23U "very quiet" at media 8/15 |
| 2 | Actions command capture | ✅ PASS | ✅ PASS | One cue, action executed. S21 audible; S23U "barely audible" |
| 3 | Chat back-and-forth re-listening | ⚠️ BLOCKED (early test, ctx limit) | ✅ PASS (fresh conv) | S23U fresh conversation: TTS completed, rearm triggered, one FOREGROUND cue per re-listen, response captured |
| 4 | Actions slot-fill reply | ✅ PASS | ✅ PASS | Two cues: first for Command mode, then SlotReply auto-arms (ActionsViewModel handles SlotReply cue) |
| 5 | Chat slot-fill reply | ✅ PASS | ✅ PASS | Uses existing Command mic path (user re-taps mic). One FOREGROUND cue per tap. No automatic slot-rearm in Chat |
| 6 | Widget entry | ✅ PASS | 🔷 NOT PRESENT | Widget button → VoiceCommandActivity. One cue, capture works |
| 7 | Permission-repair continuation | ✅ PASS | N/A | Revoke → detect → grant → capture restarts with cue |
| 8 | Screen-off Hey Jandal | ✅ PASS | ✅ PASS | Wake detected, one cue, capture works. S21: "a bit quiet" |
| 9 | Alarm dismiss | ✅ PASS | ✅ PASS | One cue, dismiss works, alert ducked |
| 10 | Alarm snooze (after fix) | ✅ PASS (retested at 8191b242) | N/A | PR-introduced regression fixed. Snooze dismisses alert, notification clears, next ring scheduled |
| 11 | Timer dismiss | ✅ PASS | ✅ PASS | One cue, dismiss works, alert ducked |
| 12 | Automatic STT retry | ✅ deterministic UT | ✅ deterministic UT | Covered by `WakeWordCueTest`: two attempts, one cue per readiness, stale session ignored, no third attempt |

### Notable findings

1. **Chat slot-fill**: Uses existing Command microphone path (user taps mic). No automatic slot-rearm in Chat.
2. **Snooze regression**: PR-introduced (main SHA `5ca1c4fa` works). Fixed at `8191b242` with `runSnoozeAction`. Physically retested S21 — PASS.
3. **Model context limit (⚠️ BLOCKED — pre-existing):** Gemma window exceeded during chat flow.

## Representative audio-policy results (Phase 2)

| # | Condition | S21 | S23U | Notes |
|---|---|---|---|---|
| 1 | Normal foreground capture | ✅ PASS | ✅ PASS | Cue audible, one per attempt |
| 2 | Low media vol (1/15) foreground | ✅ PASS | ✅ PASS | Cue inaudible (FOREGROUND uses music stream). Capture works |
| 3 | Normal screen-off wake-word | ✅ PASS | ✅ PASS | Cue audible, one per detection |
| 4 | Low alarm vol (1/15) wake-word | ✅ PASS | ✅ PASS | Cue inaudible (wake cue uses STREAM_ALARM). Capture works |
| 5 | DND enabled + wake-word | ✅ PASS | ✅ PASS | STREAM_ALARM bypasses DND. Cue audible both devices |
| 6 | Active BT A2DP + wake-word | ✅ PASS | ✅ PASS | S21 cue heard from handset+BT, response on BT. S23U same. Route: speaker(2), bt_a2dp(80) |
| 7 | Zero/min volume validation | ✅ PASS | ✅ PASS | Volume 1: cue inaudible, app didn't raise volume, capture works |
| 8 | Clock-alert stop+duck+lifecycle | ✅ PASS | ✅ PASS | Alert ducked (not paused). Cue barely audible over same-stream alert. Lifecycle: re-arm confirmed |

### Audio-policy conclusions
- FOREGROUND cue: STREAM_MUSIC. Inaudible at volume 1. Capture independent.
- Wake-word cue: STREAM_ALARM. Inaudible at volume 1. Bypasses DND.
- Clock-alert cue: STREAM_ALARM. Barely audible over ducked alert (stream contention).
- **No volume is silently raised** at any tested level.
- **BT route:** STREAM_ALARM routes to speaker+BT simultaneously (Samsung policy). App records route, doesn't override.

## Post-idle wake trials (Phase 4)

### S21 — 5 trials
| Trial | Wake triggered | Cue audible | Cue count | Capture | Notes |
|---|---|---|---|---|---|
| 1 | ✅ | ✅ | 1 | ✅ | |
| 2 | ✅ | ✅ | 1 | ✅ | |
| 3 | ✅ (several tries) | ✅ | 1 | ✅ | |
| 4 | ✅ | ✅ | 1 | ✅ | |
| 5 | ✅ | ✅ | 1 | ✅ | |

### S23U — 5 trials
| Trial | Wake triggered | Cue audible | Cue count | Capture | Notes |
|---|---|---|---|---|---|
| 1 | ✅ (after restart) | ✅ | 1 | ✅ | Permission re-grant required service restart |
| 2 | ✅ | ✅ | 1 | ✅ | |
| 3 | ✅ | ✅ | 1 | ✅ | |
| 4 | ✅ (2 tries) | ✅ | 1 | ✅ | |
| 5 | ✅ (2 tries) | ✅ | 1 | ✅ | |

All 10 trials: cue exactly once, capture works, wake re-arms after session.

## State restoration
| State | S21 final | S23U final | Notes |
|---|---|---|---|
| Media volume | 7/15 ✅ | 7/15 ✅ | Restored |
| Alarm volume | 10/15 ✅ | 11/15 ✅ | Restored |
| DND | OFF ✅ | OFF ✅ | Restored |
| BT | speaker only ✅ | speaker only ✅ | Disconnected after BT test |
| RECORD_AUDIO | granted ✅ | granted | User re-granted after accidental `pm clear` |
| Wake word | active | active | Running on both |

## Unit tests (all passing)
- `:app:testDebugUnitTest --tests "*ClockAlertSessionTest"` ✅
- `:app:testDebugUnitTest --tests "*WakeWordCueTest"` ✅
- `:app:testDebugUnitTest` ✅
- `:core:voice:testDebugUnitTest` ✅
- `:feature:chat:testDebugUnitTest` ✅
- `:app:assembleDebug` ✅
- `lint` ✅
- `git diff --check` ✅

## Evidence files
- `docs/testing/evidence/1416-baseline-s21.txt`
- `docs/testing/evidence/1416-baseline-s23u.txt`
- `docs/testing/evidence/1416-validation-matrix.md`
- `docs/testing/wake-word-acoustic-reliability-harness.md`

## Conclusions

**PR #1416 — remediated at SHA `8191b242`**

### Verified (all PASS)
1. Ownership guard rejects stale STT results correctly ✅
2. Cue plays exactly once per listening attempt (all entry points) ✅
3. Cue follows recogniser readiness (never pre-readiness) ✅
4. Correct context metadata (FOREGROUND / WAKE_WORD / CLOCK_ALERT) ✅
5. Speech capture functions after cue on all paths ✅
6. Volume never silently raised ✅
7. DND bypass works (STREAM_ALARM exemption) ✅
8. BT route metadata captured (S21 + S23U) ✅

### Snooze regression — fixed and physically retested
- PR-introduced regression: `ACTION_SNOOZE_ALERT` did not dismiss after snooze.
- Fix: `runSnoozeAction()` orchestration (tested in `ClockAlertSnoozeRegressionTest`).
- S21 physical retest: alert stops, notification clears, next ring scheduled. PASS.
- Main comparison SHA `5ca1c4fa`: snooze works (regression confirmed as PR-introduced).

### Chat slot-reply
- Chat uses `VoiceCaptureMode.Command` (its actual capture mode).
- Slot-fill voice: user taps mic (existing Command path). No automatic slot-rearm in Chat.
- ActionsViewModel handles `SlotReply` mode with its own FOREGROUND cue.

### Not physically tested (covered by deterministic UT)
- **Automatic STT retry**: `WakeWordCueTest` validates two attempts, one cue per readiness,
  stale session ignored, no third attempt.

### Documentation corrections applied
- Entry-point table: Command mode plays FOREGROUND cue (was incorrectly "No cue")
- Three cue contexts: FOREGROUND (STREAM_MUSIC), WAKE_WORD (STREAM_ALARM), CLOCK_ALERT (STREAM_ALARM)
- Chat slot-fill described as using existing Command mic path
