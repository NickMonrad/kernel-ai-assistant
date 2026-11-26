# PR #2119 device evidence (S21, debug build 43cbdb89)

Device: Samsung SM-G991B `R5CR605B71K` (S21). Build: debug APK from `43cbdb89`, installed with `adb install -r`, `lastUpdateTime 2026-11-26 18:58:05`. No audio and no screenshots are committed.

## Timers, exact alarm path (#1990)

Timer phrase "Set a timer for 30 seconds." through the debug harness. Timer created when `ADB_TOOL_COMPLETE set_timer` is logged. Fire time is the start of `ClockAlertService` from `ALARM_MANAGER_WHILE_IDLE`. Due time is the creation time plus 30 s, so the delta is an upper bound: the scheduling call ran a few ms before the logged completion.

| Run | Condition | Created | Alert service start | Delta |
|---|---|---|---|---|
| T1 | screen off, keyguard showing (temp PIN) | 18:58:20.974 | 18:58:51.094 | +120 ms |
| T2 | screen off, keyguard showing | 19:02:29.223 | 19:02:59.320 | +97 ms |
| T3 | screen off, keyguard showing | 19:03:33.557 | 19:04:03.625 | +68 ms |
| D1 | forced deep Doze (`deviceidle force-idle`, state reported `IDLE`), unplugged | 19:04:44.441 | 19:05:14.543 | +102 ms |

- System `FIRST_ALARM_TRIGGER_ACTION` for T1 at 18:58:50.971.
- No Jandal next alarm clock: `dumpsys alarm` lists no clock alert and `settings get system next_alarm_formatted` is unchanged (`Thu 9:20 pm` existed before the run and is not from this build).
- After the runs: no `ClockAlertService` running, no test alarms left.

## Spoken reply and generation service promotion (#2000)

Typed prompt "Tell me one short fact about octopuses." (LLM route; `What time is it?` takes the direct intent path and never starts generation). Screen turned off after `generation_lock_wait_ms` was logged.

| Run | Generation foreground start | Reply ended (screen off) | Service stopped | Linger |
|---|---|---|---|---|
| G1 | 19:08:17.144 | 19:08:34.034 | 19:09:04.084 | 30.05 s |
| G2 (`voice_auto_speak` forced on, test only) | 19:10:10.917 | 19:10:29.752 | 19:10:59.831 | 30.08 s |

- The linger is verified: the generation service stays promoted for 30 s after a normally completed reply with the screen off, then stops.
- NOT verified: speech. In both runs no `SpeechPlaybackService` foreground state was captured, and there were no Jandal audio-focus requests during the reply window, so no speech playback was observed. The speech-foreground claim (mediaPlayback promotion covering speech started after screen-off) is not evidenced by this run.

## Forced Doze

- `dumpsys deviceidle force-idle` reported `IDLE` (deep) while the timer was pending. The alert started 102 ms after its due time.
- Restored with `dumpsys deviceidle unforce` and `dumpsys battery reset`. Deep state read `ACTIVE` afterwards.

## Deviations and limits

- Timers were 30 s, as requested by the owner, not the house 10 s cap.
- #1989 locked invocation was not rerun in this session (see the earlier record).
- Speech path (#2000) not reproduced; see above.

## Restored device state

- Wake word (`hey_jandal_enabled`) was true; set false for the run; DataStore restored byte-identical; the service no longer runs.
- `voice_output_preferences` restored byte-identical after the test-only `voice_auto_speak` entry.
- Temporary PIN set and cleared; lock screen disabled as before (`locksettings get-disabled` = true).
- `stay_on_while_plugged_in` 0 during the run, restored to 2.
- Alarm volume 1 during the run, restored to 11.
- Default assistant left as GSA; `adb_harness_token` removed.
- Lock `/tmp/jandal-s21.lock` released after this record.
