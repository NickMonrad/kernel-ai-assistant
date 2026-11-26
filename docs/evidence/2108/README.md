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

- **Samsung Voice Recorder (S21):** covered only with the notification Pause action; see "Pause action run" below.
- **Wake phrase after re-arm:** one synthetic clip was detected after Resume (section 10 of `s21-pause-resume-run.txt`). No human speaker, distance or room test.
- **Phone call:** not tested.

## Other contender found

On the S21 at 20:59:33 `com.samsung.android.bixby.wakeup` (uid 1000) captured MIC for about 10 s. Its start silenced Jandal's capture, and Jandal paused until Bixby stopped (release 20:59:33.189, re-arm 20:59:43.868). If a system listener like this one stays open continuously, Jandal stays paused. Long-lived system captures need a policy decision before this ships.

## Known flap

Camera run on this build (section 7 of `s21-pause-resume-run.txt`) shows one release, not a flap. The 10 s silence hold removes the flap for a silence-caused release; the re-measured camera-start timing on the S21 is not separately recorded.

## Update: source filter and Samsung Voice Recorder (S21)

### Always-on source filter (owner rule, #2109)
Other apps' clients on `VOICE_RECOGNITION` do not pause Jandal by their presence (`WakeMicContentionGate.ALWAYS_ON_SOURCES`). If the audio server silences Jandal's capture while such a client is open, Jandal releases and probes again after a hold: 10 s, doubling to a 120 s cap while the silence repeats. Recorders on MIC/CAMCORDER and calls on VOICE_COMMUNICATION still pause it.

Limit: `AudioRecordingConfiguration` exposes the client's source, session id and silenced flag publicly. It does not expose the client uid or package (no public getter in the API 35 reference). The system listener `com.samsung.android.bixby.wakeup` (uid 1000) captured on **MIC**, so the source filter does not exclude it. Excluding it needs the uid, which needs a hidden API or a different signal. This is not built.
The filter also ignores the hidden HOTWORD source (1999), which is not a public constant.

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
| Recorder stop | The recorder logs `Enable System Sound` and `isSaveEnable`, but its MIC session stays open after stop (riid 91946991, uid 10279, still active 40 s later; no `rec stop`). Jandal stays paused. Notification: user-pause text ("Paused. Tap Resume to listen for Hey Jandal.") until Resume. |
| Resume tapped | Jandal re-syncs and stays paused, because the recorder still held the mic. This is correct behaviour for that state. |
| Recorder released (`am force-stop`) | Jandal re-arms: VOICE_RECOGNITION session for uid 10649 at 07:39:19; notification "Listening for wake word…". |

Camcorder and voice session on this build (`s21-pause-resume-run.txt` sections 7–8): camcorder start releases the mic once (no thrash) and Jandal re-arms after the camcorder stops (07:41:53). Voice session: yield 07:42:17.165, re-arm 07:42:22.165, the same second as Back. The 5 s is the session length, not a re-arm delay.

Recorder normal flow, no force-stop (`s21-pause-resume-run.txt` section 9): Stop, Home, Back and a Recents swipe do not close the recorder's MIC session. Windows tested: 118 s+ (Home), 97 s+ (Back), 75 s+ after the swipe (about 3.5 min after Stop in total). Only `am force-stop` closed it. Jandal stays paused until the mic is free, then re-arms on Resume (07:58:47, immediate). Documented as expected: Jandal waits for the mic.

Wake phrase after re-arm (section 10): one espeak-ng "Hey Jandal" clip played on the S23U (VLC, media volume 10, restored to 4). S21 logged `WakeWordDetector: detected (high confidence=0.792)` at 07:59:24.112 and yielded to the voice session. Synthetic voice, one playback, distance not measured.

Not verified in this run:
- Human speaker and distance or room tests; the wake rate.
- Phone call. Camcorder and voice session were run earlier (sections 7–8).
- Stream volumes 1, 2 and 5 changed by the recorder's start (`Disable System Sound`); pre-run values not recorded, so restoration is not verified. Current: stream 1 = 0, stream 2 = 11, stream 3 = 2, stream 4 = 11, stream 5 = 11. `mode_ringer` = 2 and `zen_mode` = 0 (unchanged). `stay_on_while_plugged_in` = 2 (unchanged).

## Run 2026-11-26: both phones, build 984707d0

Full filtered log lines and timestamps: `run-2026-11-26-both-phones.txt`. Pause and Resume were sent through the notification's own action buttons. No screenshots were taken or committed.

| Check | S21 (R5CR605B71K) | S23U (RFCW11265XH) |
|---|---|---|
| Pause from the notification releases the mic | yes, 18:19:26.385 | yes, 18:23:01.584 |
| Camcorder while paused: Jandal stays off | yes, 18:19:52.428 contention release; `not re-arming` 18:20:23.633 | yes, 18:23:21.399; `not re-arming` 18:23:33.496 |
| Resume from the notification re-arms | yes, 18:20:49.200 (detector 18:20:49.262) | yes, 18:23:55.706 (detector 18:23:55.722) |
| Camcorder while listening: silence-caused release and re-arm | yes: release 18:22:06.841 with the new message `audio server silenced wake capture — releasing wake mic`; re-arm 18:22:16.891 (10.05 s) | yes: release 18:24:12.698; re-arm 18:24:22.725 (10.03 s) |
| Spoken wake after Resume, clip from the other phone at media stream 15 | no. S23U clip, two playbacks: maxConfidence 0.0999 and 0.3033, threshold 0.65 | yes. S21 clip: detected 18:25:54.228 (high confidence 0.7936), yielded 18:25:54.336, re-armed 18:26:04.487 after Back |

Not verified:

- Human speaker and distance or room tests. One clip per direction, so no wake rate.
- Phone call.
- Samsung Voice Recorder on this build. Its results are from the earlier build (sections above).
- The S21 missed the clip played from the S23U in both attempts. The cause is not established (speaker placement, volume, or the clip). The result is asymmetric, so treat it as a real gap.

Incident: for one playback attempt I granted VLC storage access on the S21. VLC then scanned shared storage (about 280 "cannot open DVD" log lines). No file in that scan was opened by this run. The permission was revoked and VLC force-stopped. VLC's media library may hold entries from that scan.

Stream volumes, ringer, zen mode and stay-on were restored and checked against the values recorded before the run on both phones.

### Owner decisions (not decided in this run)

- Samsung Voice Recorder holds the mic after Stop until its process dies. Accept, or change?
- The user pause is memory-only. It survives screen-off, not reboot or a service restart. Accept?
- Bixby (`com.samsung.android.bixby.wakeup`) and other system MIC listeners have no uid in the public API, so the source filter cannot exclude them. Jandal pauses while they are open. Accept?
- Silence-caused release probes again after a 10 s hold, doubling to a 120 s cap while the silence repeats. Accept, or change the cap?
- Both phones now have evidence, but the S21-hears-S23U wake miss is open. Accept this PR with that gap, or block on it?
- Clear VLC's media-library data on the S21 after the scan incident?
