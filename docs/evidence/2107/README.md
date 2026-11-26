# PR #2107 device evidence: slot follow-up mic (#2082) and chat one-shot reopen (#2083)

**Risk tier: High** (voice pipeline: TTS playback stop, slot-reply mic open, watchdog). Policy requires S23U device evidence for High risk. **This run is S21 only.** S23U evidence is not collected here; see Gaps.

Build: `fix/2082-2083-slot-followup-mic` at `b92cadfd` (debug build, `ai.jandal.assistant.debug`, lastUpdateTime 2026-11-26 07:44:26), installed with `adb -s R5CR605B71K install -r`.

## Method and deviations

- Device: S21 `R5CR605B71K` (Exynos), no PIN. Lock `/tmp/jandal-s21.lock` taken by mkdir after waiting ~16 min for #2110's hold; released after the run.
- Trigger: the debug harness quick-action path (`quick_action_input="set a timer"`, `quick_action_is_voice=true`, harness token via `run-as`; see `docs/adb-testing.md` §8.1), not "Hey Jandal" audio. Reason: a wake clip could not be delivered to the S21 mic. Playing `wake_pause_timer_loud.wav` on the S23U via the media player did not start playback (`am start` VIEW opened a chooser, then a permission prompt from another app; I backed out and did not grant anything). The approved acoustic receiver needs owner-approved fixtures, which I did not install. The S21 wake detector ran during the attempt (maxConfidence about 0.001, not triggered).
- Command: "set a timer" with no duration, which asks the slot question "How long would you like the timer for?". No timer was created; no answer was spoken, so no timer started.
- Log: `s21-run1-slot-followup-logcat.txt`, KernelAI and audio route lines from the 07:47:06 launch through 07:55.
- Volumes: S21 untouched (media 2, ringer 2, stay-awake 2 before and after). S23U media 4 to 10 for the wake attempt, restored to 4; ringer 0 unchanged; stay-awake value 2 unchanged. Harness token file removed from the S21 app dir.

## Results (timeline from the log)

| Event | Time (AEST) | Delta |
|---|---|---|
| launch, NeedsSlot, slot prompt set | 07:47:06.58 to 06.90 | |
| prompt speech started (38 chars) | 07:47:08.33 | 1.7 s after launch |
| playback stopped (`voiceOutputSpeakingStopped`) | 07:47:10.65 | 2.3 s speech |
| `startVoiceCapture mode=SlotReply` | 07:47:11.00 | **0.35 s after playback stop** |
| `startListening` started | 07:47:11.44 | |
| silent capture: `retry 1/2` | 07:47:21.64 | 10.2 s no-speech timeout |
| re-ask 1 (66 chars) started | 07:47:23.43 | |
| playback stopped | 07:47:27.42 | |
| mic open | 07:47:27.77 | **0.35 s** |
| `retry 2/2` | 07:47:38.28 | 10.2 s |
| re-ask 2 started | 07:47:40.22 | |
| playback stopped | 07:47:44.27 | |
| mic open | 07:47:44.62 | **0.35 s** |
| `no retry (handedOff=false), waiting for manual input`; `slotReplyVoiceRetryExhausted` | 07:47:55.15 | 10.2 s, then stops |

- **Why the follow-up mic opened:** the playback-stopped event (`voiceOutputSpeakingStopped`) in all three cycles. Neither the 10 s start watchdog nor the 15 s playback-safety watchdog logged anything ("never started", "stalled" absent).
- **Delay, end of question to mic open:** about 0.35 s. The owner's earlier ~9 s is not reproduced on this build. The 10.2 s gaps are the silent-capture timeout after the mic opened, not the delay before it. Caveat: `voiceOutputSpeakingStopped` is the proxy for the end of the spoken question.
- **Silent reply cap:** initial prompt, 2 re-asks, then stop. Matches the intended cap (max 3 captures). Wake listening resumed at 07:47:55.

## Gaps (not verified in this run)

- **#2083 chat one-shot reopen: not verified on device.** The one-shot starts from a voice transcript in chat, and this run had no audio path to the S21 mic. Unit tests only: `./gradlew :feature:chat:testDebugUnitTest --tests '*ChatViewModelVoiceTest*' --tests '*ActionsViewModelVoiceTest*'` at `b92cadfd`: ChatViewModelVoiceTest 91 tests and ActionsViewModelVoiceTest 176 tests, 0 failures, 0 errors (run 2026-11-26 07:49 AEST).
- **"Hey Jandal" audio trigger: not exercised** (see Method).
- **S23U primary evidence: missing.** Policy requires it for High risk. Needs a follow-up S23U run (or an owner decision that S21 suffices).

## Runs 9 to 13 (2026-11-26, head `eed81f02`): audio path blocked, no matrix results

Attempted the two-turn reminder flow ("set a reminder" → "put the bins out" → "at five pm") and the timer diagnostic ("set a timer" → "ten seconds") with acoustic clips played over the air. No run produced a transcript or a `mode=SlotReply` open, so no mic-open delay was measured and no reminder or timer was created.

| Run | Player → listener | Result |
|---|---|---|
| 9, 10 | S23U → S21 | Wake clip detected on the S21. Command clip never transcribed. Voice session closed after its 10 s window. |
| 11 | S23U → S21 (proven timer command) | Same as 9, 10. The command also fails with a known-good clip, so the reminder clip is not the cause. |
| 12 | S21 → S23U (S21 Jandal stopped) | S23U did not detect the wake clip. No KernelAI lines in the window. |
| 13 | S21 → S23U, S21 media 15 | S23U still did not detect the wake clip. |

- **Cause found for runs 9 to 11:** the S23U's own Jandal `WakeWordService` (foreground, Hey Jandal enabled) detects the clip it plays, starts its own voice session, and takes audio focus. VLC pauses (`AUDIOFOCUS_LOSS_TRANSIENT`), so the command clip is lost. The player must not run the wake service.
- **Not yet explained:** the S23U does not detect wake clips played by the S21 at media volume 10 or 15, although the S21 VLC started playback (18:09:42, 18:12:18). Speaker placement or mic sensitivity are candidates; not checked.
- **Volumes:** S21 media 2 before and after; S23U media 4 before and after. The S21 Jandal process was stopped for run 12 and not relaunched.
- **Privacy:** no audio, transcripts, names, or screenshots committed. Logs are kept in `/tmp` only.
