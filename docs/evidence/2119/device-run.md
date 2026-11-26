# PR #2119 device evidence (build a669792a, risk HIGH)

Listener: Samsung SM-G991B `R5CR605B71K` (S21). Player: Samsung SM-S911B `RFCW11265XH` (S23U, clips played through VLC). No audio and no screenshots are committed. The owner's voice clips are not in the repo.

Status: PARTIAL. The #1990 timer matrix was not completed; #2000 has one reproduction attempt on each build. Refs only, not Closes.

## Invocation

- Long-press power did not open Jandal on the S21; it opened Google Maps. All runs used `input keyevent KEYCODE_ASSIST`, which opened `VoiceCommandActivity` (the default assistant is Jandal for the run).
- Wake word (`hey_jandal_enabled`) was left ON. DataStore writes failed (`run-as` denied), so it was not switched off. Deviation.

## Timers (#1990): not verified

Voice-submitted timer runs on the branch, unlocked. None produced a correct 30 s timer.

| Run | Clip | Transcript (on-device ASR) | Route | Result |
|---|---|---|---|---|
| U1 | "Set the timer for 30 seconds" | "Set the time after thirty seconds" | fallthrough to chat LLM, `set_timer` score 0.746 | no timer |
| U2 | same | "Sixty time after thirty seconds" | fallthrough, `set_timer` 0.711 | no timer |
| U3 | same, S23U media volume 10 | "Set a time for thirty seconds" | fallthrough, `set_timer` 0.764; LLM called `run_intent set_timer` with `duration_seconds=3` | timer set for **3 s**, not 30 s; speech confirmed "3 seconds" |
| U4 | 2107 clip "Set a timer." + 10 s duration reply | "Set a time" | fallthrough, `best_guess=null` | no timer |

- The ASR consistently drops or misreads "timer". The direct `set_timer` path was never taken, so the LLM tool-argument path (wrong duration) was the only one that ran.
- U3 created a real 3 s alarm on the S21; it fired and was not cancelled by a test step. Not a locked run.
- Not run: locked timers (branch ×3, main ×2), unlocked/locked 2-minute timer, stop and cancel, due-vs-fire delta. There is no timing evidence for this build.

## Speech after screen-off (#2000)

Voice-submitted prompt "Tell me the history of the All Blacks in detail." (ASR: "Tell me the history of the ... detail"; routed to chat LLM). Screen turned off with `KEYCODE_SLEEP` after generation started.

| Run | Build | Screen off | Generation FGS started | "Reply ended with screen off or locked" | Generation FGS stopped | SpeakingStarted | Speech chars | Audio delivered |
|---|---|---|---|---|---|---|---|---|
| C1 | a669792a (branch) | 03:40:40.130 | 03:40:40.636 | 03:40:58.530, `kept promoted for 30000ms` | 03:41:28.563 (30.03 s after reply end) | 03:41:03.214 | 124 | 167,280 frames (~7.0 s) ended 03:41:10 |
| C3 | main (`/tmp/jandal-2119-main.apk`) | 03:48:47.453 | 03:48:48.612 | not logged (no such code on main) | 03:49:19.115 | 03:50:17.070 | 394 | 538,992 frames (~22.5 s) ended 03:50:39 |

- Branch: `SpeechPlaybackService` was allowed to start foreground at 03:41:03.321 (`ActivityManager: Background started FGS: Allowed`, `types=0x2` mediaPlayback) and was destroyed at 03:41:10 when speech ended. No `Speech foreground service start denied` line in either run.
- Branch gap: reply end to SpeakingStarted = 4.7 s; generation linger was 30 s, as designed.
- Main gap: reply end (generation FGS stop) to SpeakingStarted = 58 s. The speech still played in full with the screen off. No denial was logged, so **the #2000 failure was not reproduced on main.**
- Both runs log `ActionsViewModel: ignoring SpeakingStarted after cancelled speech` because the chat screen had stopped earlier (`navigateToChat`). This is in older runs too and the audio still played.
- Not run: a reply that exceeds 30 s of speech; the linger-after-speech case; chunk-gap measurement (the replies were single-chunk, so no chunk gaps to report); the second exchange in an untitled conversation.
- C2 (first main attempt) produced no Jandal activity at all (assist key did not reach Jandal after the main install). Discarded; C3 is the valid main run.

## Deviations and gaps

- No #1990 timer timing for this build (see above). The ASR failure on "timer" blocks the owner clip path on this device.
- 30 s timers were not achieved. House limit 10 s was not met either (no valid timer). Do not treat U3 as a 30 s result.
- Clip rendering for 2-minute and cancel clips failed (no working GPU torch in the render venvs); those runs were not attempted.
- #2000 speech-after-screen-off is evidenced on the branch (speech played with the screen off, generation linger 30 s). The main build did not reproduce the failure; the difference is not explained by these runs.

## Restored device state

- S21: alarm volume 11 (was 1 during runs); default assistant back to `com.google.android.googlequicksearchbox`; lock screen still disabled (`locksettings get-disabled` = true); `stay_on_while_plugged_in` = 2 (unchanged). No PIN was set. The main build (`/tmp/jandal-2119-main.apk`) is the installed Jandal debug build; the branch build is not reinstalled.
- S23U: media volume 4 (was 10 during runs); the test clips `cmd_set_timer.wav`, `reply_ten_seconds.wav`, `chat_all_blacks_history.wav`, `cmd_set_timer_30s.wav` were deleted from `/sdcard/Download/`. Two unrelated files in that folder were left alone.
- Both device locks `/tmp/jandal-s21.lock` and `/tmp/jandal-s23u.lock` released (owner `omp-2119-run`).

Do not merge — wait for review.
