# #1993 device check: S23U (RFCW11265XH), Cori TTS, `mahi` -> `ma-hee`

Status: INCONCLUSIVE. The S23U renders ran, but the debug path does not exercise the lexicon or the app's speech settings, and the durations do not match the host references. Nothing here proves the spoken text is `ma-hee`.

## What ran (2026-11-22, AEST)

- Keyguard: `isKeyguardShowing=false` at start. `stay_on_while_plugged_in` set to 2 and left at 2.
- Lock: `/tmp/jandal-s23u.lock` taken, released after the run.
- Branch: `fix/1993-cori-mahi` merged with `origin/main` (PR #2032 merged; main is Room v60).
- Build: `./gradlew :app:assembleDebug` succeeded. Install: `adb install -r` returned `Success`.
- DB: `databases/kernel_db` user_version 59 before the install, 60 after (forward migration; no data cleared).
- Render: `SampleClipReceiver` broadcast with `--es text` (Cori voice, `files/sample_corihigh.wav`, 22050 Hz mono 16-bit).

## Durations (captured clips)

| Input text | Samples | Duration |
|---|---|---|
| `Ka pai tō mahi.` | 13568 | 0.615 s |
| `Ka pai tō ma-hee.` | 10752 | 0.488 s |

Host references (sherpa 1.13.0, speed 0.85, gain 1.5): `Ka pai tō mahi.` ~1.35 s; `Ka pai tō ma-hee.` ~1.40 s.

## Why this does not verify the fix

- `SampleClipReceiver` calls `generate(text, 0, 1.0f)` directly. Speed is 1.0, not the app's 0.85, and it skips `SherpaSpeechText.prepare`, so the lexicon mapping is not applied to the input. The respelled render just says the text it was given.
- The durations are about half the host references, and the respelled clip is shorter than the raw one. The expected direction was the reverse.
- The clips were not listened to, and the receiver logs only sample counts.
- The live speech path does not log the prepared text; only the character count appears under verbose logging.

The lexicon mapping itself is covered by `PronunciationLexiconTest` on the host (`SherpaSpeechText.prepare("Ka pai tō mahi.") == "Ka pai tō ma-hee."`).

## Device state

- Debug app data: not cleared. Onboarding was skipped during the run. Skipping started a background model download (about 3.7 GB, Wi-Fi only, labelled "Getting Jandal ready") that was not requested for this check. It may still be running.
- A notification-permission prompt was answered "Not now".
- A One UI 9.0 "Install now" update prompt appeared once; I backed out with Back and did not install it.
- Cori voice pack was already downloaded and selected on the device.
- Screen was not turned off. No PIN entered, no power key pressed.

## Next step needed

A correct on-device check needs the live speech path (speech with the lexicon applied at speed 0.85), or a debug receiver that calls `SherpaSpeechText.prepare` first, plus listening to the clips or measuring against those references.
