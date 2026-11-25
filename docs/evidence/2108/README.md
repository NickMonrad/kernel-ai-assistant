# #2108 evidence: Hey Jandal yields the mic to other recorders

Build: debug APK from `fix/2108-wake-yield-mic` (WakeMicContentionGate + AudioRecordingCallback), installed with `adb -s <serial> install -r`.
No personal data: test recordings were deleted from the phones after their audio tracks were checked. Logs are filtered to KernelAI/WakeRearm/AudioService lines.

## Verified on device

| Check | S21 (R5CR605B71K) | S23U (RFCW11265XH) |
|---|---|---|
| Wake service foreground, VOICE_RECOGNITION capture open before test | yes | yes |
| Camera video (camcorder intent) starts while Jandal listens | yes | yes |
| Jandal releases the mic when the camcorder starts | yes: silenced 20:59:54.495, release 54.499/54.523 | yes: silenced 21:02:30.090, release 30.105/30.161 |
| Camcorder records with a sound track | yes: AAC 48 kHz stereo, RMS about -26.7 dBFS | yes: AAC 48 kHz stereo, RMS about -30.7 dBFS |
| Jandal re-arms after the camcorder stops | yes: camcorder stop 21:00:45.695, Jandal capture 45.862 (167 ms) | yes: camcorder stop 35.348, Jandal capture 35.400 (52 ms) |
| Detector generation starts after re-arm | yes (`recording started` 21:00:45.859) | yes (`recording started` 35.399) |

Timeline lines: `s21-audio-recording-events.txt`, `s23u-audio-recording-events.txt`; service lines: `*-logcat-filtered.txt`.

## Not verified

- **Samsung Voice Recorder (S21): tested with the notification Pause action.** See "Pause action run" below.
- **Wake phrase after re-arm:** the re-arm is shown by detector start and `recording started` logs only. No spoken "Hey Jandal" was run, so wake detection after re-arm is unverified.
- **Phone call:** not tested.

## Other contender found

On the S21 at 20:59:33 `com.samsung.android.bixby.wakeup` (uid 1000) captured MIC for about 10 s. Its start silenced Jandal's capture, and Jandal paused until Bixby stopped (release 20:59:33.189, re-arm 20:59:43.868). If a system listener like this one stays open continuously, Jandal stays paused. Long-lived system captures need a policy decision before this ships.

## Known flap

At camera start, both phones show one short release/re-arm/release (S21 54.499 → 54.553 → 54.560; S23U 30.105 → 30.199 → 30.200). The re-arm starts the detector, which holds the capture open about 12–18 ms before the release (review measurement). The detector's model load is about 40 ms per start. Nothing is recorded from it. The silence hold (`WakeMicContentionGate.SILENCE_BACKOFF_MS`, 10 s) removes the flap for a silence-caused release; this build has not been re-measured on the camera path.

## Update: source filter and Samsung Voice Recorder (S21)

### Always-on source filter (owner rule, #2109)
Other apps' clients on `VOICE_RECOGNITION` never pause Jandal (`WakeMicContentionGate.ALWAYS_ON_SOURCES`). Recorders on MIC/CAMCORDER and calls on VOICE_COMMUNICATION still pause it. Tested in `WakeMicContentionGateTest` (16 tests), with `WakeUserPauseTest` (3) and `WakeMicYieldGateTest` (10) still passing.

Limit: `AudioRecordingConfiguration` exposes the client's source, session id and silenced flag publicly. It does not expose the client uid or package (no public getter in the API 35 reference). The system listener `com.samsung.android.bixby.wakeup` (uid 1000) captured on **MIC**, so the source filter does not exclude it. Excluding it needs the uid, which needs a hidden API or a different signal. This is not built.
HOTWORD is a hidden constant, so the filter lists only the public VOICE_RECOGNITION.

### Samsung Voice Recorder on S21 with Hey Jandal on (`s21-recorder-*`)
- Granted RECORD_AUDIO and READ_MEDIA_AUDIO to `com.sec.android.app.voicenote` (owner-approved test-phone grant). Without READ_MEDIA_AUDIO it showed "allow permission in Settings".
- Attempts with Jandal listening (VOICE_RECOGNITION, uid 10649, not silenced):
  - 22:40:19 and 22:40:35: recorder reaches `prepareRecord`, then `AnotherRecordActive: true` at 21.216 and 36.468. It shows `ANOTHER_RECORDER_ALREADY_RUNNING` and cancels. No recorder capture is created in the audio server.
- Control with Jandal force-stopped (22:41:00): `AnotherRecordActive: false`, `RECORD_START` at 22:41:01, audio server shows MIC session for `com.sec.android.app.voicenote`.
- **Result: the recorder refuses before it opens a capture. Jandal never sees a recorder client, so it cannot yield in time.** The gate's callback path is not enough for this recorder.
- Built (#2109): a Pause action on the Hey Jandal notification releases the mic until the user taps Resume. The pause is in memory: it survives screen-off, not reboot or service restart. See "Pause action run".
- No recording file was saved (the control and attempts were force-stopped before save). Test data is not on the phone from this run.

Files: `s21-recorder-app-filtered.txt` (recorder's own log), `s21-recorder-kernelai-filtered.txt`, `s21-recorder-audio-events.txt`.

## Pause action run (S21, #2109)

Build: this branch, installed on the S21 (`s21-pause-resume-run.txt` has the log lines).

| Step | Result |
|---|---|
| Pause tapped | Jandal releases the mic (`paused by user — releasing wake mic`, 07:36:31.785). Notification: "Paused. Tap Resume to listen for Hey Jandal." |
| Samsung Voice Recorder records while paused | Yes. `AnotherRecordActive: false`, `setRecorderState - state : 2`. A 57.96 s clip saved (not kept on the device). Waveform shown in the recorder screen. |
| Recorder stop | The recorder logs `Enable System Sound` and `isSaveEnable`, but its MIC session stays open after stop (riid 91946991, uid 10279, still active 40 s later; no `rec stop`). Jandal stays paused. Notification: "Paused while another app records". |
| Resume tapped | Jandal re-syncs and stays paused, because the recorder still held the mic. This is correct behaviour for that state. |
| Recorder released (`am force-stop`) | Jandal re-arms: VOICE_RECOGNITION session for uid 10649 at 07:39:19; notification "Listening for wake word…". |

Camcorder and voice session on this build (`s21-pause-resume-run.txt` sections 7–8): camcorder start releases the mic once (no thrash) and Jandal re-arms after the camcorder stops (07:41:53). A voice session yields the mic and Jandal re-arms 5 s after it closes (07:42:22).

Not verified in this run:
- The recorder releases its MIC on Stop without force-stop. It did not. The re-arm after Resume is shown only after force-stop. `Closes #2108` therefore depends on a user-visible result we did not reach; the PR uses `Refs #2108`.
- Stream volumes 1, 2 and 5 were changed by the recorder's start (`Disable System Sound`); pre-run values were not recorded, so restoration is not verified. Current: stream 1 = 0, stream 2 = 11, stream 3 = 2, stream 4 = 11, stream 5 = 11. `mode_ringer` = 2 and `zen_mode` = 0 (unchanged). `stay_on_while_plugged_in` = 2 (unchanged).
