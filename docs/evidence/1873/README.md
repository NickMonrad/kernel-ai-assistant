# #1873 device evidence — S23U (RFCW11265XH), debug build

Build: `app-debug.apk` from this branch (`ai.jandal.assistant.debug`), installed with `adb install -r`.
Media (STREAM_MUSIC) volume lowered to 2 for testing; restored to 11 afterward.
Speech engine: Sherpa (Kokoro), text: quick-action card "Read aloud" (#1026 path).

## Verified on device

| Method | Result | Evidence |
|---|---|---|
| Volume-down **in app** while speaking | Speech stopped; key consumed (media volume unchanged at 0) | `SpeechStopDiag key=25 action=0 speaking=true` at 10:18:42.533, then `action=1 speaking=false`; AudioTrack `state:stopped` at 42.559 (~26 ms after key) |
| Volume keys **idle** (not speaking) | Normal media volume change | `volume is 0` → VOL_UP `1` → VOL_DOWN `0` |
| Speaking notification posted | `speech_playback` channel, ONGOING, 1 action, title "Jandal is speaking" | `dumpsys notification` (id 1873, actions=1); shade shows "Press a volume key or tap Stop to interrupt" |

Latency: Sherpa synthesis took ~4 s before SpeakingStarted on these runs; the volume key only stops once SpeakingStarted has fired (see gaps).

## Not verified / failed

- **Volume key via MediaSession remote VolumeProvider** (`JandalSpeech`): session active and PLAYING, but on S23U the volume key still adjusted STREAM_MUSIC (2→1) while speaking when the app was not in front. Samsung routing did not deliver the key to `onAdjustVolume`. Lock-screen / background volume-key stop is therefore **not working on S23U**.
- **Notification Stop button**: Stop is not exposed as a node in the collapsed shade card; not tapped. A `am broadcast` to `SpeechStopReceiver` (`exported=false`) did not stop speech — shell broadcasts cannot reach non-exported receivers, so that test is inconclusive for the receiver. The PendingIntent runs inside the app and was not verified.
- **Lock screen** speech stop: not tested.
- **POST_NOTIFICATIONS** was not granted on the debug build initially; the speaking notification is skipped without it (logged `SpeechStop: POST_NOTIFICATIONS not granted`). Granted temporarily for the notification test, then revoked.
- Chat stop button, barge-in, and the #1026 card speaker were not re-run on device after this change.
- Shorter spoken replies: already covered by #1872/#1895 (`GetWeatherSkill` `spokenSummary`); not re-verified here.
