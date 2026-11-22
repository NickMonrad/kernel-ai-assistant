# #1993 device check: S23U (RFCW11265XH), Cori TTS, `mahi` -> `ma-hee`

Status: LEXICON VERIFIED ON DEVICE. The prepared text shows `ma-hee` for the `mahi` input. Durations are close to the host renders. Listening was not done, so the audio quality of `ma-hee` is not verified.

## Path exercised (2026-11-22, AEST)

The debug `SampleClipReceiver` now calls the same steps as `SherpaOnnxVoiceOutputController.speak`:
`PronunciationLexicon.ensureLoaded`, `SherpaSpeechText.prepare(text)`, `generate(prepared, sid=0, speed)` with the stored speed (0.85), then the stored gain (1.5) applied as in playback (`multiply, clamp to [-1, 1]`). Speed, gain and voice are read from `VoiceOutputPreferences`. The lexicon object and `SherpaSpeechText` are `internal` to `:core:voice`, so the receiver calls them reflectively. No production code changed.

- Keyguard `isKeyguardShowing=false`. `stay_on_while_plugged_in` = 2, left at 2. Lock `/tmp/jandal-s23u.lock` taken and released.
- Branch merged with `origin/main` (Room v60). App DB user_version 59 -> 60 by forward migration on install. No data cleared.
- Build `:app:assembleDebug` and `:app:testDebugUnitTest` (`SampleWavTest`, 2 tests, pass).

## Why the earlier clips were short

The receiver had received only `input="Ka"`. The host's `adb shell am broadcast ... --es text "Ka pai tō mahi."` was split on spaces by the device shell, so only the first word arrived. The receiver was not truncating. The logged `input=` field, now added, shows the full text.

## Results

| Input (`--es text`) | Prepared text (logcat) | Samples | Duration |
|---|---|---|---|
| `Ka pai tō mahi.` | `Ka pai tō ma-hee.` | 35285 | 1.600 s |
| `Ka pai tō ma-hee.` | `Ka pai tō ma-hee.` | 34560 | 1.567 s |
| `mahi.` | `ma-hee.` | 14632 | 0.664 s |

Host references (sherpa 1.13.0, speed 0.85, gain 1.5): `Ka pai tō mahi.` ~1.35 s; `Ka pai tō ma-hee.` ~1.40 s. The device sentence renders are about 15% longer than the host.

No long pause at the hyphen: the longest internal quiet run (10 ms RMS windows, edges excluded) is 0.08 s for `mahi` and 0.09 s for `ma-hee`. The WAV files and sample counts match the logged durations. Peaks: 11508 (mahi), 13943 (ma-hee).

WAVs are kept on the host at `/tmp/1993-device/` (not in git): `raw_mahi.wav`, `raw_mahee.wav`, `word_mahi.wav`.

## Device state

- Debug app data: not cleared. Onboarding was skipped in an earlier run. That started a background ~3.7 GB Wi-Fi model download ("Getting Jandal ready"), not requested for this check. It may still be running.
- Cori voice pack already downloaded and selected.
- Screen was not turned off. No PIN entered, no power key pressed.

## Not verified

- Listening to `ma-hee`: the clips were not played back, so audio quality is not confirmed.
- The live speech path (`speak`, AudioTrack playback) was not run. The receiver mirrors its steps; it does not play audio.
- Host-vs-device duration gap (~15%) is not explained yet.
