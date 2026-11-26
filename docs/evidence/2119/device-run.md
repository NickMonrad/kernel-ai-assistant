# PR #2119 device evidence (build a669792a, risk HIGH)

Listener: Samsung SM-G991B `R5CR605B71K` (S21). Player (voice clips only, earlier session): Samsung SM-S911B `RFCW11265XH` (S23U). No audio and no screenshots are committed. Owner voice clips are not in the repo.

## Method

- Timers used the debug harness typed route: `am start -n ai.jandal.assistant.debug/com.kernel.ai.MainActivity --es adb_harness_token <token> --es quick_action_input_encoded <URL-encoded prompt>`. With a valid token the launch is trust `DIRECT` and the prompt auto-runs (`external_prompt_trust ... trust=DIRECT`, `NativeIntentHandler.handle: intent=set_timer`, `params={duration_seconds=30}`). The token file was written with `run-as` to `files/adb_harness_token` and removed afterwards.
- Why not voice: the owner voice clip "Set a timer for 30 seconds" was transcribed on-device as "Set the time after thirty seconds", "Sixty time after thirty seconds", "Set a time for thirty seconds", or "Set a time". The direct `set_timer` route never ran. One voice run created a 3 s timer through the LLM tool path (wrong duration). That run is not counted. Deviation.
- Locked runs: temporary PIN `2468` set with `locksettings set-pin` for the run and cleared afterwards. Screen off with `KEYCODE_SLEEP`; `isKeyguardShowing=true`, `mWakefulness=Dozing` before the launch and after the run.
- Due time = logged creation (`NativeIntentHandler.handle`) + duration. Delta = alert timestamp minus due time. The creation log is slightly after the scheduling call, so each delta is an upper bound.
- Alert signal: the `com.kernel.ai.alarm.AlarmBroadcastReceiver` broadcast in `ActivityManager` on the branch. The main build did not log that broadcast under the same tag, so its alert time is the timer notification removal (`EdgeLightingPolicyManager: remove`). Those two signals are not identical; the main numbers are not directly comparable.

## Timers (#1990)

| Run | Build | Keyguard | Created (log) | Duration | Alert (signal) | Delta |
|---|---|---|---|---|---|---|
| T5 | a669792a (branch) | unlocked | 05:00:01.583 | 30 s | broadcast 05:00:31.585 | +2 ms |
| L1 | a669792a (branch) | locked, dozing | 05:01:03.220 | 30 s | broadcast 05:01:33.226 | +6 ms |
| L2 | a669792a (branch) | locked, dozing | 05:01:58.249 | 30 s | broadcast 05:02:28.260 | +11 ms |
| L3 | a669792a (branch) | locked, dozing | 05:02:51.761 | 30 s | broadcast 05:03:21.777 | +16 ms |
| B2M | a669792a (branch) | locked, dozing | 05:06:11.223 | 2 min | broadcast 05:08:11.239 | +16 ms |
| M1 | main (`/tmp/jandal-2119-main.apk`) | locked, dozing | 05:03:57.339 | 30 s | notification removed 05:04:27.707 | +368 ms |
| M2 | main | locked, dozing | 05:04:46.179 | 30 s | notification removed 05:05:16.596 | +417 ms |

- Cancel (branch, locked, BC): timer created 05:08:50.357 (30 s); `cancel_timer` executed at 05:08:52.277; notification removed 05:08:52.283. No alarm broadcast through the 30 s due time (05:09:20.357) or the end of the log (05:09:34). The timer did not fire.
- Not run: stop of a ringing alert, a second 2-minute run, unlocked 2-minute run, main 2-minute run, and any locked voice-submitted timer.
- Measured here: branch alert delivery +2 to +16 ms across unlocked, locked, and 2-minute runs. Main alert +368 to +417 ms (notification-based signal). This session does not show what causes the difference; the branch uses `USE_EXACT_ALARM` exact scheduling (see PR body). Treat as observed, cause not isolated.

## Speech after screen-off (#2000)

Earlier session, same builds (not rerun in this turn). Voice-submitted prompt "Tell me the history of the All Blacks in detail." (ASR garbled, routed to chat LLM). Screen off with `KEYCODE_SLEEP` after generation started.

| Run | Build | Screen off | Generation FGS started | "Reply ended with screen off or locked" | Generation FGS stopped | SpeakingStarted | Speech chars | Audio delivered |
|---|---|---|---|---|---|---|---|---|
| C1 | a669792a (branch) | 03:40:40.130 | 03:40:40.636 | 03:40:58.530, `kept promoted for 30000ms` | 03:41:28.563 (30.03 s after reply end) | 03:41:03.214 | 124 | 167,280 frames (~7.0 s), ended 03:41:10 |
| C3 | main | 03:48:47.453 | 03:48:48.612 | not logged (not in main code) | 03:49:19.115 | 03:50:17.070 | 394 | 538,992 frames (~22.5 s), ended 03:50:39 |

- Branch: `SpeechPlaybackService` allowed to start foreground at 03:41:03.321 (mediaPlayback, `types=0x2`), destroyed at 03:41:10 when speech ended. No `Speech foreground service start denied` line.
- Branch: reply end to SpeakingStarted 4.7 s; linger 30 s.
- Main: reply end (generation FGS stop) to SpeakingStarted 58 s. Speech played in full with the screen off, no denial logged. **The #2000 failure was not reproduced on main.**
- Both: `ActionsViewModel: ignoring SpeakingStarted after cancelled speech` appears (chat screen had stopped earlier); audio still played.
- Not run: reply longer than 30 s of speech, linger after a long speech, chunk-gap measurement (single-chunk replies), second exchange in an untitled conversation.

## Gaps

- #1990 locked voice path: not verified (ASR cannot transcribe "timer" from the owner clip). Timers verified via the typed harness route only.
- #2000: the branch shows the mechanism works (linger and FGS allowed with screen off); the main build did not reproduce the bug in one run. Whether the fix is needed or fixes it is not proven.
- 2-minute and cancel: one branch run each. Not repeated, no main runs.

## Restored device state (S21)

- Temporary PIN cleared (`locksettings clear`), lock screen disabled (`locksettings get-disabled` = true).
- Alarm volume 11 (was 1 during runs).
- Default assistant `com.google.android.googlequicksearchbox` (restored).
- `stay_on_while_plugged_in` = 2 (unchanged).
- `files/adb_harness_token` removed from the app.
- Installed Jandal debug build: branch `a669792a` (`/tmp/jandal-2119-branch.apk`).
- S23U: this turn did not touch it; the earlier-session test clips were removed and media volume restored to 4.
- Locks `/tmp/jandal-s21.lock` and `/tmp/jandal-s23u.lock` released after this record.

Do not merge — wait for review.
