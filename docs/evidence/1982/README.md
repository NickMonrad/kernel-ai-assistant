# #1982 on-device evidence: remove Zipformer (PR #2047)

- Build SHA: `a332d331` (PR #2047 head, branch `feat/1982-remove-zipformer-1`)
- APK: `app/build/outputs/apk/debug/app-debug.apk`, sha256 `fc87204774b9490828ce6948dccbb0b26278c1efef573835188acbe654881e9f`, package `ai.jandal.assistant.debug`
- Device: S21 (`R5CR605B71K`) only. S23U and Honor not touched.
- Upgrade baseline APK: `origin/feat/1980-asr4all-auto-default` (`26bfa440`), built from `git archive` into `/tmp`, no repo state change. Room v60 on both, so the DB was not downgraded.

## Results

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | Upgrade: stored `SherpaZipformer` maps to Auto, no crash | PASS | Stored value written to `voice_input_preferences.preferences_pb` with the app stopped (run-as cp of an edited prefs file; stored value was `Asr4all` before). PR build launched with no E/FATAL lines for its pid. Voice settings show "Automatic · Recommended". The raw stored string stays `SherpaZipformer` on disk because the app maps on read and did not rewrite it. |
| 2 | Hey Jandal: Whisper verification, no Zipformer requested | PARTIAL | Hey Jandal enabled (toggle on). Whisper tiny.en files present in `files/models`. Stimulus: `natural_wake` fixture played on the S21 itself through the debug `AcousticStimulusReceiver` (S23U locked). Wake fired at high confidence 0.835 (`WakeWordDetector: detected (high confidence=...)`), so the low-band verifier was not entered (`lowVerifyEntered=false`). **Whisper verification was not exercised.** No Zipformer model request or queue observed in logcat. |
| 3 | Auto routing to asr4all; Settings › Voice has no Zipformer rows | PASS | `Auto STT route=Asr4all` for wake alert-command sessions and for a push-to-talk (mic FAB, Command mode), both with `asr4allReady=true`. Voice settings engine list: Android native, Sherpa SenseVoice, Sherpa Whisper tiny.en, Sherpa Paraformer, asr4all streaming (Downloaded). No Zipformer engine or download row. Caveat: PTT was no-speech only ("No speech heard"), no spoken capture. |
| 4 | No Zipformer files downloaded or queued on a fresh model list | PARTIAL | Model Management list has no Zipformer row. `download_network_prefs`, `model_preferences` and the other datastore files contain no zipformer/sherpa-stt/queue strings. **Not a fresh list:** a fresh list needs app-data clear, which was not done. **Stale files remain** on the device from before this change: `files/models/sherpa-stt-{encoder,decoder,joiner}.int8.onnx` and `sherpa-stt-tokens.txt` (dated 2026-11-16). The PR build does not reference them. They were not deleted. |

## Files

- `s21-logcat-excerpt.txt`: verbatim logcat lines for the checks above, plus the error scan.
- `s21-voice-speech-recognition-auto.png`: Voice settings, Hey Jandal on, Speech recognition = Automatic.
- `s21-voice-engine-list-no-zipformer.png`: Voice settings engine list, no Zipformer row.

No personal data: screenshots are cropped to Settings › Voice. Chat content, transcripts, and the private `natural_wake.wav` are not included. The fixture copy in the app's private `files/acoustic-fixtures/` was removed after the trial.

## Not done / open

- Check 2 needs a trigger that enters the low-band verifier to exercise Whisper verification.
- Check 4 needs a fresh install or data clear to confirm a fresh model list. The stale `sherpa-stt-*` files should be removed from the S21 by the owner if the removal is to be considered clean on this device.

Lock `/tmp/jandal-s21.lock` acquired for this run and released afterwards.
