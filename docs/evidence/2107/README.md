# PR #2107 device evidence: slot follow-up mic (#2082) and chat one-shot reopen (#2083)

**Risk tier: High** (voice pipeline: TTS playback stop, slot-reply mic open, watchdog). Policy normally requires S23U device evidence for High risk. **Owner waiver (27 Nov 2026): S23U voice evidence is waived, recorded on PR #2107. S21 device runs plus unit tests are the evidence.** This run is S21 only.

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

## Final matrix (2026-11-26, head `eed81f02`): partial, not a pass

| Run | Player → listener | Result |
|---|---|---|
| reminder 1 | S23U → S21 | **Failed.** Wake detected on S21 (18:46:49). "Set a reminder" routed FallThrough → chat; the chat LLM dispatched `add_reminder` with no params. No `mode=SlotReply` mic opened; no reminder created. |
| timer 2 | S23U → S21 | **Passed on `45bc7387`, not on this head.** Actions path, not the wake route (see the 27 Nov c section). NeedsSlot → slot sheet; `SlotReply` mic opened 0.35 s after playback stopped (18:50:12.01). "Ten seconds" normalised to 10 seconds; `set_timer` completed at 18:50:16. 10 s timer; expired, nothing to delete. |
| S23U evidence | S21 → S23U, S21 media 15 | **Failed.** Hey Jandal enabled (pref `hey_jandal_enabled` = true). S23U WakeWordService started its detector only after an explicit app relaunch and backgrounding; it did not detect the S21 wake clip. Voice session launched via `VoiceCommandActivity`; command played inside its capture window (18:55:50), S23U reported "No speech heard" at 18:55:59. Acoustic path S21 → S23U not working at this placement and volume. |
| #2083 chat check | not run | Depends on an audio path; not attempted. |

- **Reminder runs 2 and 3: not run.** Run 1 shows a deterministic FallThrough, so a repeat would repeat it.
- **Root cause of reminder failure:** a bare "set a reminder" matches no `add_reminder` pattern in `QuickIntentRouter`. The `add_reminder` contract requires `day` and `time` in addition to `item`, so the owner's flow ("at five pm" alone) cannot complete. Blocked on a product decision: bare reminder should ask for `item`; `day` required, or default to today when only a time is given.
- **eed81f02 coverage:** the in-place fix covers only `RegexMatch`/`ClassifierMatch`. The device path is FallThrough, so the fix does not reach it.
- **Volumes and state:** S21 media 2 and S23U media 4 restored; stay-awake 2 on both. S21 Jandal relaunched; S21 staged clips removed. S23U Jandal left running, not in a call.
- **Privacy:** no audio, transcripts, or screenshots committed. Logs in `/tmp` only.

## Run 2026-11-27 (fix head `73104f9eff`, S23U → S21): reminder routing and creation pass; timer and S23U own-speaker fail; #2083 inconclusive

Build: branch head with the bare-reminder fix (`a4c5642c`), installed on both phones with `adb install -r`. S21 `lastUpdateTime` 04:01:16 and S23U 03:53:15. **Earlier S21 runs in this session (reminder 1 and 1b) are invalid:** another agent's build replaced the S21 app at 03:42, before this run took the S21 lock. Those runs tested the wrong code; their FallThrough result does not apply to this branch.

Driver: the private driver counted `mode=SlotReply` lines, and each capture logs that string twice. The second reply clip therefore played during the first capture. Runs 2 onward used a fixed copy that counts `startVoiceCapture mode=SlotReply` (private, not committed). Run 2 is invalid for that reason.

### Reminder flow (S23U → S21, owner clips, 3 valid runs)

| Run | Result |
|---|---|
| 2 (04:01) | Bare "Set a reminder" → `NeedsSlot` (item). Prompt played; reply heard as "Depends there. At 5 PM" (overlapping clips, invalid). No reminder created. |
| 3 (04:02) | Command heard as "See a reminder" (classifier `add_reminder`, in-place slot sheet). Item heard as "There". Time "And 5 PM". `complete` with `paramKeys=[item,time]`. One reminder created, `dueAt` 17:00 today (default day). Deleted. |
| 4 (04:05) | Item "Put the bens out"; time "And 5 PM"; `complete`. Reminder created, `dueAt` 17:00 today. Deleted. |
| 5 (04:08) | Item "What the ben out"; time "And 5 PM"; `complete`. Reminder created, `dueAt` 17:00 today. Deleted. |

- Routing: all three bare commands reached the item slot sheet (`NeedsSlot`), not chat fallthrough. This is the owner fix.
- Day default: all created reminders resolved to 17:00 on the test day, the next occurrence of the time.
- Confirmation text: the spoken confirmation was not logged. Only `SpeakingStarted chars=50` was logged, so the "today" wording is not verified on device.
- STT quality: item replies were misheard in 4 of 4 attempts ("There", "Put the bens out", "What the ben out"). Item text is therefore not verified against the spoken words.
- Cleanup: all three reminders deleted through the to-do list UI. Database check: `list_items` with `dueAt` has no `ACTIVE` rows.

### Timer (10 s, S23U → S21): failed twice

- Runs `timer-2` (04:10) and `timer-3` (04:11): wake detected, VoiceCommandActivity opened, command clip played. No ASR result, no `ActionsViewModel` or timer lines. Activity closed after about 10 s. No timer created, so nothing to cancel.
- The earlier pass (head `45bc7387`, prior run) does not cover this build. Cause not diagnosed.

### #2083 chat one-shot (S21 chat PTT, one turn): inconclusive

- Single PTT turn with the reminder command. STT "A reminder" went to a confirmation fast path, then the LLM fallthrough answer. TTS ended at 04:15:03.
- No follow-up mic opened after TTS ended. This matches the fix, but the slot follow-up path in chat was not exercised. One sample only, so this is not a pass.

### S23U own speaker: failed

- S23U played the clips and was also the listener. Wake overlay route `none`; no wake detection on the S23U. No command captured.

## Run 2026-11-27b (head `0e9c8a49`, reinstalled on both phones 04:47; S23U → S21)

Builds: `app-debug.apk` from `0e9c8a49`, installed with `adb install -r` on S21 (`lastUpdateTime` 04:47:42) and S23U (04:47:52). The earlier installs were replaced by another agent's builds (S21 04:31, S23U 04:44). The APK was built after the `origin/main` merge.

### Reminder: pass (3 valid runs, both follow-up mic opens)

| Run | Item reply heard | Time reply heard | Result |
|---|---|---|---|
| reminder-6 | "Put the b there" | "And 5 PM" | `complete`, `paramKeys=[item,time]`, reminder at 17:00 today |
| reminder-7 | "What the pen there" | "And 5 PM" | `complete`, `paramKeys=[item,time]`, reminder at 17:00 today |
| reminder-8 | "What the benz" | "In 5 PM" | `complete`, `paramKeys=[item,time]`, reminder at 17:00 today |

- Each run: `NeedsSlot for "Set a reminder"` → item sheet → `startVoiceCapture mode=SlotReply` → item reply → time mic opened → time reply → `NativeIntentHandler.handle: intent=add_reminder paramKeys=[item,time]`.
- Item text is still misheard by STT in every run. The confirmation wording was not logged, so the "today" text is unverified.
- All three test reminders deleted via the to-do list; DB check shows 0 ACTIVE rows with `dueAt`.

### Timer: failed (4 runs, not diagnosed)

- timer-4 (`cmd_set_timer.wav`, peak 7744) and timer-5 (`cmd_set_timer_loud.wav`, same clip scaled ×2.88 to peak 22272, matching the reminder command level) both: wake detected (`WakeWordDetector: detected`), `wake overlay route=DIRECT_ACTIVITY`, VoiceCommandActivity opened, Asr4all requested audio focus, then released after ~10 s. No STT line, no `ADB_SLOT_STATE`, no timer.
- Reminder runs use the same wake and VoiceCommandActivity path and do transcribe. So the clip level is not the sole cause. Remaining candidates: command timing relative to ASR start (timer commands landed 2.0–2.1 s after wake; reminder commands 4.5 s after wake), or the timer command failing in VoiceCommandActivity. Not confirmed.
- The earlier passing timer (`45bc7387`, Actions path, not wake) is a different path and is not evidence for this one.

### Run 2026-11-27c (head `8809fff1`, code same as `0e9c8a49`; S21 reinstalled 05:11)

**Timer retest (S23U → S21): fail, 3 more runs.** Driver `run_flow_c.sh` (private copy with `CMD_DELAY`) placed the command at different offsets after the detected wake route:
- timer-6: command 4.1 s after detection (`CMD_DELAY=3.0`). ASR focus 33.3–43.5 s, no transcript.
- timer-7: command 6.5 s after wake start (`CMD_DELAY=4.0`). ASR focus 12.5–22.7 s, command at 17.1 s inside the window, no transcript.
- timer-8: command 3.1 s after detection (`CMD_DELAY=2.6`). No slot question, driver timed out waiting for SlotReply.
- No `NeedsSlot`, no `SlotReply`, no timer created in any run. Timer cause still not diagnosed; timing is not the cause (fails at 3–6.5 s). The owner's 4.5 s target was not hit exactly (offset was measured from detection, not from clip start, in two runs).

**#2083 chat check (S21 chat screen, PTT tapped at (332,1955), S23U plays clips): partly run.**
- "Set a reminder" from the S23U: transcribed, spoken slot question (43 chars) at 10.4 s, PTT mic reopened at 13.1 s.
- Silence from the S23U: `silent retry 1/2` at 23.4 s, re-ask (71 chars) at 24.8 s, `silent retry 2/2` at 39.4 s, re-ask at 40.7 s, one more capture 44.9–55.2 s with no speech, then the flow stopped. Two re-asks and then stop, as specified.
- LLM question check: **not verified.** A typed "help my plans" was taken as the pending reminder item (the chat slot was still open), so it tested the slot path, not an LLM question. A second attempt to open a new conversation landed on the Tools screen. No pass is claimed for "LLM question does not reopen the mic".
- The reminder was not created (no time was given). 0 active reminders in the DB.

**S23U own speaker, voice session without wake: fail.**
- Session started by `am start -n ai.jandal.assistant.debug/com.kernel.ai.feature.widget.VoiceCommandActivity` on the S23U (not by the default assistant; the default assistant was not changed, it is Google's `GsaVoiceInteractionService`).
- ASR (route `Asr4all`, mode `AlertCommand`) started at 29.4 s, but VLC playing `cmd_set_reminder.wav` at media 12 took audio focus at 33.2 s (`onAudioFocusChange(-1)`), which stopped the recognizer. No transcript, no `ADB_SLOT_STATE`. The reply clip at 58 s had no listener.
- S21-speaker fallback not tried: the S23U would have to act as a wake-free listener, which earlier runs showed does not work.

### State and volumes

- S21 media 2, S23U media 4 restored. `stay_on_while_plugged_in` = 2 on S23U.
- Staged `silence_2107.wav` removed from the S23U. No active reminders (DB: 0). No timers created.
- Privacy: logs in `/tmp` only; no audio, transcripts, names committed. The one S21 screenshot (chat screen) was kept in `/tmp`, not committed.

## Run 2026-11-27d (head `704980b2` merged with `origin/main` `9e398a9f`; S21 only)

Build: `./gradlew :app:assembleDebug` on the merged head, `app-debug.apk` installed on S21 `R5CR605B71K` with `adb install -r` (`lastUpdateTime` 06:32:36). Locks `/tmp/jandal-s21.lock` and `/tmp/jandal-s23u.lock` taken by mkdir at 06:30:04 (neither was held). Both released at the end of the run.

### Blocker: S23U disconnected

- The S23U (`RFCW11265XH`, usb:1-5) dropped off adb at about 06:33. It did not reattach within 60 s, and `adb kill-server` / `start-server` did not bring it back. The owner must replug it before any S23U run.
- Not run: the S23U speaker step (playing a "ten seconds" reply from the S23U to the S21 mic), and the LLM non-reopen check by voice. The S23U was never used for playback in this run, so its volume and stay-on settings were not changed.
- A "ten seconds" clip exists outside the repo at `~/private-acoustic-fixtures/device-2107/reply_ten_seconds.wav`. It was not played, because the S23U was disconnected, and it was not in `/tmp/jandal-clips`.

### Timer routing: typed "set a timer" on S21 (pass)

Route: `am start` with `adb_harness_token`, `quick_action_input_encoded=set%20a%20timer`, `quick_action_is_voice=true` (`docs/adb-testing.md` §8.1). `external_prompt_trust callerUid=-1 trust=DIRECT`.

| Event | Time (AEST) |
|---|---|
| `NeedsSlot for "set a timer"` → slot sheet, `ADB_SLOT_STATE missingSlot=duration_seconds` | 06:32:47.74 |
| Slot question spoken, 38 chars: "How long would you like the timer for?" | 06:32:49.20 |
| Playback stopped (`voiceOutputSpeakingStopped`) | 06:32:51.48 |
| `startVoiceCapture mode=SlotReply`, `startListening started mode=SlotReply` | 06:32:51.83 / 06:32:52.15 |
| No speech: `slot-reply voice error — retry 2/2`; re-asks with `SlotReply` mic at 06:33:08 and 06:33:24 | 06:33:18 / 06:33:34 |
| `no retry (handedOff=false), waiting for manual input`; `slotReplyVoiceRetryExhausted` | 06:33:34.70 |

The mic opened, so the route is not broken. The "ten seconds" answer was not heard because nothing was played (S23U disconnected).

### Timer creation: typed slot reply on S21 (pass for the slot-to-timer step, typed not voice)

- Typed "ten seconds" into the slot sheet's answer field and tapped Submit (the field needed a keyboard-shift fix: first attempt dropped the final "s"). Log: `ADB_SLOT_STATE action=reply slot=duration_seconds reply="ten seconds"`, `action=complete intent=set_timer finalSlots=[duration_seconds]`, `set_timer params={duration_seconds=10}` at 06:35:35.
- UI text on the Actions result: "Timer set for 10 seconds." The same text says exact-alarm access is off, so the timer is inexact and may ring later, and it may not persist across restarts.
- Cancel: harness `cancel my timer` routed to `cancel_timer` at 06:35:47 and the UI reply was "Dismissed the active timer alert." The 10 s timer was already past its expiry (about 12 s after creation), so the cancel dismissed its alert. Nothing was left active (inferred from the reply text; no timer list was read).

### #2083 LLM non-reopen on S21: not verified

- New conversation: the `New conversation` content-desc node (912-984, 1704-1776 on the Chats screen) opened a fresh chat. Model load took about 30 s.
- Typed "Help my plans" (typed, not voice). First token 58.7 s after `generate_called`. The model emitted a malformed `run_intent` call with `intent_name=plan` and garbage parameters (`JSONException`, fail-closed). The reply was "Invalid parameters: expected a JSON object", not a clarifying question.
- No mic activity was logged. A typed turn cannot reopen the mic, so this run proves nothing about the reopen rule. Verification of the rule rests on the unit test below.
- Separate observation, out of scope: the S21 model emits invalid tool-call JSON for a vague request. Fail-closed handling is correct. Not fixed here.

### Unit tests (pass)

- New: `ActionsViewModelVoiceTest` "voice bare set a timer routes to duration slot and arms slot reply mic". Uses the real `QuickIntentRouter`. It asserts `duration_seconds` pending, the spoken question, and `startListening(SlotReply)` only after the 350 ms settle.
- Existing and extended: `ChatViewModelVoiceTest` "one-shot llm clarifying question does not reopen the mic". A FallThrough LLM reply that is a question ("Which room do you mean?") is spoken; the chat stays Idle; no `Command` or `SlotReply` capture; one `Dictation` capture only.
- `./gradlew :core:skills:testDebugUnitTest --tests '*QuickIntentRouterTest*' :feature:chat:testDebugUnitTest --tests '*ActionsViewModelVoiceTest*' --tests '*ChatViewModelVoiceTest*'`: BUILD SUCCESSFUL. ActionsViewModelVoiceTest 178 tests, ChatViewModelVoiceTest 92 tests, QuickIntentRouterTest suites 0 failures or errors.

### State

- S21 media volume 2 (unchanged), stay-awake 2. Harness token removed from the S21 app dir; token file in `/tmp` only.
- S23U: not reached; volumes and stay-awake unchanged by this run. Its PIN state and sleep state are unknown after the disconnect.
- Privacy: no audio, transcripts, or screenshots committed. UI dumps kept in `/tmp`.

### Still open

- S23U speaker run (voice answer → timer, and voice LLM non-reopen) when the S23U is reconnected. Owner waived S23U voice evidence on 27 Nov; this run is still not voice-end-to-end on the S21 alone.
