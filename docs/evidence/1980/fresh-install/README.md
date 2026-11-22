# #1980 fresh-install, Hey Jandal and dictation evidence

Verdict: **Part of #1980.** AC3 passes on both phones as wake target. AC4 passes under the coordinator decision that free-form PTT counts as "dictation". AC1 is partial: the pre-download route and the Zipformer download were not captured. The Whisper verifier half of AC3 was never exercised.

## Build under test

- Source: `origin/main` at `e2d9ccc8`. Branch `docs/1980-fresh-install-evidence`.
- Artifact: `app/build/outputs/apk/debug/app-debug.apk`, sha256 `e4e3c7cccaa38ddfe4a44a7ca13bb31a74dba2b3b9675e9de3d0af16aaa8ac5e`.
- Package: `ai.jandal.assistant.debug`.
- Fresh install on both phones: uninstall, reinstall, then setup through onboarding.

## Devices

Two Samsung phones, referred to by role. Serials are not committed (device selectors are kept out of evidence per `docs/testing/acoustic-stimulus-source.md`).

| Role | Device |
|---|---|
| S21 | Samsung SM_G991B |
| S23U | Samsung SM_S918B |

Paired playback: the source phone plays approved fixtures (`natural_wake`, `qwen_command`) through `AcousticStimulusReceiver`. The listener phone captures. Fixture audio is private and not committed. Only fixture IDs and trial IDs are recorded. No transcripts of the cloned voice.

## Acceptance criteria

| AC | S21 | S23U | Result |
|---|---|---|---|
| AC1 fresh install, Auto, online fallback off, asr4all after download | asr4all download complete. First route `Asr4all mode=Command` at 18:59:49 | asr4all download complete. First route `Asr4all mode=Command` at 18:54:11 | **Partial.** Post-download route passes on both. Pre-download route not captured. Zipformer download not observed. |
| AC3 Hey Jandal, `natural_wake` then command routed to asr4all | Target (S23U source): wake 18:57:01.264, route `Asr4all mode=AlertCommand` 18:57:01.337, `get_time` 18:57:10.848, reply played 18:57:16.97. | Target (S21 source at volume 15/15): wake `detected (high confidence=0.894)` 19:28:08.159, route `Asr4all mode=AlertCommand` 19:28:08.212, `get_time` 19:28:19.308, reply started 19:28:20.847. | **Pass on both targets, with a verifier gap.** Both wakes were accepted at high confidence (0.72–0.89), so the Whisper low-band verifier did not run (`verifier=0`, `lowVerifyEntered=false`). |
| AC4 PTT command and dictation-style turn, `route=Asr4all` | Command 18:59:49: `get_time` `direct_reply` at 18:59:57. Free-form turn 19:00:13: route `Asr4all mode=Command`, no intent or tool event logged. | Command 18:54:11: `get_time` `direct_reply` at 18:54:15. Free-form turn 18:54:42: route `Asr4all mode=Command`, no intent or tool event logged. | **Pass** under the coordinator decision. The free-form turn's outcome after routing is not evidenced in these logs. |

## S23U wake diagnosis (19:25–19:28)

The first S23U target runs at source volume 7/15 produced no detection. Read-only checks found no app or setup fault:

- `WakeWordService` running in the foreground (`types=0x80`, microphone), channel `kernel_wake_word`.
- `RECORD_AUDIO` granted. Appops `RECORD_AUDIO=foreground`, `RUN_ANY_IN_BACKGROUND=allow`, `START_FOREGROUND=allow`. Standby bucket 10.
- Hey Jandal enabled (`hey_jandal_enabled` in the wake preferences). Wake models load on every start.
- Whisper tiny.en encoder, decoder and tokens downloaded at 18:46.
- Mic input active: `VOICE_RECOGNITION`, client pid 6752, not silenced.
- Events log: `wake_word` STARTED 18:46:43, STOPPED 19:04:47, STARTED 19:04:50. No errors.

The missing detail was the detector's diagnostics. They are gated on `Log.isLoggable("WakeWordDiag", DEBUG)`. S23U had no `log.tag.WakeWordDiag` property; S21 had one. I set it on S23U to diagnose (19:25:51), restarted the listener by toggling Hey Jandal off and on, and then cleared the property. Log: `logs/s23u-wake-diagnosis.log`.

With diagnostics on, the failing run at volume 7/15 (19:26:28) gave `episodePeakRms=432`, `stage3Evals=66`, `maxConfidence=0.001`. The phrase reached the gate, but the classifier stayed near zero. The same fixture at volume 15/15 gave `episodePeakRms=2354`, `maxConfidence=0.870`, and a detection (19:27:17). The cause is source level. Source-to-listener distance was not changed or measured.

Limits: the earlier S23U runs (19:01, 19:04, 19:07) had no diagnostics, so their level is not measured. The 19:26 run is the only one with counters.

The 19:27:17 detection had no command following it, so the AlertCommand session timed out at about 19:27:27 with an error toast. The toast text was not captured. The source is `Asr4allVoiceInputController` (`No speech heard. Try again.`) by code path [INFERENCE]. The 19:28 retry put the command inside the capture window and completed.

## Interpretation: "dictation"

Main has no dictation mode. `VoiceCaptureMode` is `Command`, `SlotReply` or `AlertCommand`. Per coordinator decision, a free-form PTT turn routed to `Asr4all` counts as the dictation-style turn for AC4.

## Side effect: S21 Hey Jandal was still on

S21's Hey Jandal stayed on during the 19:26–19:28 runs. The S21 listener self-woke on its own speaker at 19:26:20 (confidence 0.721) and again at about 19:28:08, starting its own AlertCommand. S21 then logged a `get_time` item at 7:28 pm. My toggle at 19:26 landed on the Actions screen, not on the switch. This does not affect S23U's pass, since S23U's detection, route and `get_time` are logged on S23U. S21 was used as source in the 19:27–19:28 runs, and those runs carry this side effect. S21's Hey Jandal is on (wake service running, Voice screen showed the switch on at 19:01). The S21 screen went to AOD after `stayon` was restored; it was not woken again.

## Gaps

1. **AC1 pre-download route.** The `Auto STT route` line logs only when a capture starts, and asr4all was ready before any capture. No pre-download route evidence exists on either phone.
2. **AC1 Zipformer.** No Zipformer download appears in the logs on either phone.
3. **AC3 Whisper verification.** No run entered the low-band verifier on either phone (`verifier=0`). The verification half of AC3 is unverified; wakes were accepted at high confidence.
4. **AC4 free-form outcome.** The free-form turns logged a route but no intent or tool event, so the reply is not evidenced.
5. **Invalid self-wake runs.** S21 self-woke at 18:54 and 19:26 (source and listener on the same phone). Recorded as observations only.
6. **Duplicate asr4all queue.** The asr4all M model is queued twice per launch on both phones.
7. **Distance.** Source-to-listener distance was not measured or changed; only source volume was.

## Logs

All under `logs/`:

- `s23u-asr4all-download.log`, `s21-asr4all-download.log`: asr4all M download and engine load.
- `s23u-ptt-turns.log`, `s21-ptt-turns.log`: PTT command and free-form turns. Transcript text removed.
- `s23u-wake-no-detection.log`: S23U wake listener and source replays, first attempt.
- `s23u-wake-diagnosis.log`: service and permission state, events, and diagnostic-gated wake counters, 19:25 onward.

## Screenshots

All under `screenshots/`. Transcript-bearing screens are excluded. `s23u-ptt-command-reply.png` is cropped to the `get_time` chip and reply.

- `s23u-chat-ready.png`, `s21-chat-loading.png`: ready and loading states.
- `s23u-ptt-command-reply.png`: S23U `get_time` chip and reply (crop).
- `s23u-voice-settings-on.png`, `s23u-heyjandal-off-source.png`, `s23u-heyjandal-on-target.png`: S23U Hey Jandal states.
- `s21-voice-settings-on.png`, `s21-heyjandal-off-source.png`, `s21-heyjandal-restored.png`: S21 Hey Jandal states.

## Teardown

- S23U `log.tag.WakeWordDiag` cleared. Confirmed empty.
- S21 `svc power stayon` set to `false`. S23U `stay_on_while_plugged_in` left at 2.
- S21 `STREAM_MUSIC` restored to 2 after playback (checked).
- S21 and S23U Hey Jandal left on.

Do not merge — wait for review.
