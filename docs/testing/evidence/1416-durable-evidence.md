# PR #1416 Durable Evidence

## Evidence key

| Type | Tag |
|---|---|
| Unit test | UT |
| Acoustic journal | AJ |
| Logcat (KernelAI tag) | LC |
| ADB device-state capture | ADB |
| Human audibility observation | HO |

## Tested commits

- Application code (physical matrix): `38c590f0c756e781a651f901a4c1e940a9f5a931`
- Snooze fix + targeted retest: `8191b2425076a289c2904c24e7226f6ae23acf7d`
- Main snooze comparison: `5ca1c4fad772e3b29e19e8d1798ded36c5d34b57`
- Real suspend snooze orchestration: `79a0e89e640e3a5a89f84825553f57e886c36c7d`

## Clock-alert dismiss — S21 (AJ)

**Journal snapshot:**

| Seq | Event | Metadata |
|-----|-------|----------|
| 1 | `VOICE_SESSION_STARTED` | generation=0, session=1 |
| 2 | `STT_READY` | — |
| 3 | `CUE_REQUESTED` | context=clock_alert, policy_version=2026-07-cue-v1 |
| 4 | `CUE_PLAYBACK_STARTED` | context=clock_alert, stream=4 (STREAM_ALARM), current_volume=10, max_volume=15, route=built_in_speaker |
| 5 | `SESSION_COMPLETED` | — |

## Wake-word capture — S21 (HO, LC)

**5 trials (normal volume):** All 5 wake triggered, cue exactly once, capture worked.

## Wake-word capture — S23U (HO, LC)

**5 trials (normal volume):** All 5 wake triggered, cue exactly once, capture worked. 2 trials required multiple wake-word attempts.

## Foreground capture — Chat mic (UT, HO)

**Chat uses `VoiceCaptureMode.Command` exclusively.**
- Chat slot-fill voice replies use the existing Command mic path (user taps mic).
- `SlotReply` mode cue is handled by `ActionsViewModel` for the Actions screen only.
- S21: One cue (FOREGROUND), capture works. UT: `ChatViewModelVoiceTest`
- S23U: One cue (FOREGROUND). HO: "very quiet" at media 8/15.

## Chat back-and-forth re-listening (HO + LC)

**S23U fresh conversation:** ✅ PASS
- Cue per auto-relisten: yes (FOREGROUND)
- Multi-turn flow completed: "set a timer" → TTS → rearm → cue → "5 minutes" → done

## Snooze regression — now fixed (UT)
- `main` SHA `5ca1c4fa`: snooze button works ✅
- PR branch before fix: snooze failed (regression)
- Fix: `runSnoozeAction()` runs the real suspend snooze operation and dismisses only on success
- `ClockAlertSnoozeRegressionTest` validates orchestration (5 tests using `runTest`)

## S21 Bluetooth route (HO + ADB)

**SHA:** `8191b242`
- Active Bluetooth audio device connected to S21
- Wake-word capture triggered ("Hey Jandal" → "What time is it?")
- Cue heard: **handset and Bluetooth device both** (STREAM_ALARM routes to both)
- Response played: **Bluetooth device only** (STREAM_MUSIC routes to active BT)
- Route metadata confirmed `Devices: speaker(2), bt_a2dp(80)`
- Bluetooth disconnected and restored after test

## Audio-policy summary (HO + ADB)

| Condition | S21 | S23U |
|-----------|-----|------|
| Low media (1/15) foreground | Inaudible, capture works | Same |
| Low alarm (1/15) wake-word | Inaudible, capture works | Same |
| DND + wake-word | Cue audible (STREAM_ALARM bypasses) | Same |
| Zero/min volume | No app volume raise | Same |
| BT A2DP wake-word | PASS — cue from handset+BT | PASS — cue from handset+BT |
| Clock-alert stop+duck | Alert ducked, dismiss works | Same |

## Unit tests (all passing at HEAD)

| Suite | Key tests |
|-------|-----------|
| `ClockAlertSessionTest` | Ownership guard, cue rules, journal |
| `ClockAlertSnoozeRegressionTest` | Successful snooze dismisses, failed snooze does not |
| `WakeWordCueTest` | Wake-word cue ordering, retry |
| `ChatViewModelVoiceTest` | Owned Command cue, unowned ignored, transcript/error/stopped no-cue |
