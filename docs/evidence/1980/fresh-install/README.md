# #1980 fresh-install, Hey Jandal and dictation evidence

Verdict: **Part of #1980.** AC1 is partial, AC3 fails on the S23U target, AC4 passes on both phones. Details and gaps below.

## Build under test

- Source: `origin/main` at `e2d9ccc8` (worktree `docs-1980-device-evidence`, branch `docs/1980-fresh-install-evidence`).
- Artifact: `app/build/outputs/apk/debug/app-debug.apk`, sha256 `e4e3c7cccaa38ddfe4a44a7ca13bb31a74dba2b3b9675e9de3d0af16aaa8ac5e`.
- Package: `ai.jandal.assistant.debug`.
- Fresh install on both phones: uninstall, reinstall, setup (Terms, Automatic speech recognition, Wi-Fi download, Hey Jandal on at Finish, mic and notification granted).

## Devices

| Role | Device | Serial | Model |
|---|---|---|---|
| Listener and PTT under test | Samsung S21 | R5CR605B71K | SM_G991B |
| Listener and PTT under test | Samsung S23U | RFCW11265XH | SM_S918B |

Paired playback: the source phone plays the approved acoustic fixtures (`natural_wake`, `qwen_command`) through `AcousticStimulusReceiver`. The listener phone captures. Fixture audio is private and not committed. Only fixture IDs and trial IDs are recorded here; no transcripts of the cloned voice.

## Acceptance criteria

| AC | S21 | S23U | Result |
|---|---|---|---|
| AC1 fresh install, Auto, online fallback off, asr4all after download | asr4all M download complete; route `Asr4all` logged after download (PTT 18:59:49 and 19:00:13; AlertCommand 18:57:01) | asr4all M download complete; route `Asr4all` logged after download (PTT 18:54:11 and 18:54:42) | **Partial.** Post-download route passes on both. Pre-download route not captured on either phone. Zipformer download not observed on either phone. |
| AC3 Hey Jandal, `natural_wake` then command routed to asr4all | Target: PASS. Wake 18:57:01.264, route `Asr4all mode=AlertCommand` 18:57:01.337, asr4all audio focus 18:57:01.351, `get_time` 18:57:10.848, reply played 18:57:16.97. | Target: **FAIL.** No wake detection (see below). | **Fail.** S21 passes the route and command, but the Whisper verifier never ran (`verifier=0 lowVerifyEntered=false`). S23U did not detect. |
| AC4 PTT command and dictation-style turn, `route=Asr4all` | Command PASS 18:59:49 (`get_time`). Dictation-style PASS 19:00:13 (route `Asr4all`, routed to LLM fallthrough). | Command PASS 18:54:11 (`get_time`). Dictation-style PASS 18:54:42 (route `Asr4all`, routed to LLM fallthrough). | **Pass**, with the caveat under "Interpretation" below. |

## Gaps

1. **AC1 pre-download route.** The `Auto STT route` line logs only when a capture starts, and asr4all was ready before any capture. No pre-download route evidence exists on either phone.
2. **AC1 Zipformer.** No Zipformer download appears in the logs on either phone.
3. **AC3 Whisper verification.** The wake on S21 was accepted at confidence 0.758 (high threshold 0.65). The low-band verifier was not entered, so no verification step was exercised.
4. **AC3 S23U target.** After the S23U wake listener started (19:00:39, restarted 19:04:50), four `natural_wake` replays from S21 produced no detection. The detector logged no `WakeWordDiag` lines at all, while S21 logged 19. The detector is not processing audio in a way that gates. Not diagnosed further. Logs: `logs/s23u-wake-no-detection.log`.
5. **Invalid self-wake run.** S21 playing `natural_wake` from its own speaker woke S21 (confidence 0.906, 18:54:44). The command capture then got no speech and a toast appeared at 18:54:54. The toast text was not captured. Kept as an observation only; it is not counted toward AC3.
6. **Duplicate asr4all queue.** `VoiceDefaults` queues the asr4all M model twice per launch on both phones.
7. **Gemma load time on S21.** Gemma E-2B (2.6 GB) finished after the asr4all engine. Chat was ready about 18:59.

## Interpretation: "dictation"

Main has no dictation mode or UI. `VoiceCaptureMode` is `Command`, `SlotReply`, or `AlertCommand`. The chat PTT path submits the transcript as a message. This run uses PTT with a free-form utterance (`natural_wake` fixture) as the dictation-style turn. The turn routed to LLM fallthrough. **Confirm this is the intended meaning of "dictation" before closing AC4.**

## Logs

All under `logs/`. Filters: asr4all and voice route lines, `ADB_*` decisions, `AcousticStimulus` events, and wake lines. Transcript-bearing lines removed.

- `s23u-asr4all-download.log` — asr4all M download and engine load, S23U.
- `s21-asr4all-download.log` — asr4all M download and route, S21.
- `s23u-ptt-turns.log` — S23U PTT command and dictation-style turns (18:54).
- `s21-ptt-turns.log` — S21 PTT command and dictation-style turns (18:59–19:00).
- `s23u-wake-no-detection.log` — S23U wake listener and S21 source replays, no detection.

## Screenshots

All under `screenshots/`. Transcript-bearing screenshots (command bubbles, dictation replies, AC3 command screens) are excluded. Only the reply region of the S23U command is kept.

- `s23u-chat-ready.png`, `s21-chat-loading.png` — ready and loading states.
- `s23u-ptt-command-reply.png` — crop of the S23U `get_time` tool chip and reply.
- `s23u-voice-settings-on.png`, `s23u-heyjandal-off-source.png`, `s23u-heyjandal-on-target.png` — S23U Hey Jandal states.
- `s21-voice-settings-on.png`, `s21-heyjandal-off-source.png`, `s21-heyjandal-restored.png` — S21 Hey Jandal states.

## Teardown

- Background logcat jobs stopped; their output is not committed.
- S21 `svc power stayon` restored to `false`. S23U `stay_on_while_plugged_in` left at 2.
- S21 Hey Jandal restored to on. S23U Hey Jandal left on.
- Device locks `/tmp/jandal-s21.lock` and `/tmp/jandal-s23u.lock` released.

## Next steps to close #1980

- Capture a pre-download route on a clean install (AC1), and confirm Zipformer is not downloaded.
- Retry S23U as the wake target after confirming its detector processes audio (AC3).
- Produce a wake with confidence in the 0.5–0.65 band to exercise Whisper verification (AC3).
- Confirm the "dictation" definition (AC4).

Do not merge — wait for review.
