# #1850 on-device evidence (S23U, SM_S918B, serial RFCW11265XH)

Build: debug APK from `feature/mic-ready-preroll-1850` (commit with Actions mic states), `ai.jandal.assistant.debug`, versionName 0.1.0, installed with `adb install -r`, lastUpdateTime verified.
Conditions: media volume 0 during capture (restored to 11), font_scale 1.15 and 2.0 (restored to 1.15). Recognizer route: Zipformer (Sherpa). Only Actions-screen voice capture was exercised. No speech was spoken; no transcripts were captured.

## Screenshots
- `actions-listening-hint-font1.15.png`: Listening, speak now, hint "Speak now: your words are being captured.", pulse ring, mic indicator in status bar.
- `actions-cold-start-font1.15.png`: first tap after cold start, Listening state.
- `actions-idle-font2.0.png`: Actions idle at font 2.0.
- `actions-listening-hint-font2.0.png`: Listening with hint at font 2.0. The hint truncates to "words are being …" (layout limit, not fixed).
- Starting state: NOT captured. The Starting window is about 0.1 s and screencap could not catch it.

## Timings (device clock, logcat `KernelAI` and audio HAL lines)
| Run | Tap (device ms) | requestVoiceCapture / startVoiceCapture | AudioRecord input opened (APM getInputForAttr) | startListening returned |
|---|---|---|---|---|
| Cold start, font 1.15 | 1794702667.681 | .787 (+106 ms) | .895 (+214 ms) | 669.063 (+1382 ms) |
| Warm, font 1.15 | 1794702638.782 | .887 (+105 ms) | .898 (+116 ms) | .935 (+153 ms) |

- Tap-to-Listening: the ListeningStarted cue fires when capture opens, so tap-to-Listening is the AudioRecord-open time: cold 214 ms, warm 116 ms (the tap timestamps come from `adb shell date` before `input tap`, so each has roughly 100 ms of adb overhead).
- Tap-to-recognizer-ready: NOT MEASURED. The app does not log when `ensureRecognizer` completes, so logcat cannot show it. The cold `startListening` gap (1.38 s) is the best available proxy; it is not a recognizer-ready time.

## Not done / owner checks
- Three "speak immediately" Brisbane transcript tries: owner to run by hand; not performed by the agent.
- Stop-during-model-load path: not exercised on device.
- Native (non-Sherpa) route has no pre-roll; not run on device.

## Commands (abridged)
- `adb -s RFCW11265XH shell cmd media_session volume --stream 3 --set 0` then restored to 11.
- `adb -s RFCW11265XH shell settings put system font_scale 2.0` then restored to 1.15.
- `adb -s RFCW11265XH shell am force-stop ai.jandal.assistant.debug` and `am start -W -n ai.jandal.assistant.debug/com.kernel.ai.MainActivity` (cold start).
- `adb -s RFCW11265XH shell input tap` on the Actions voice FAB; `screencap -p`; `logcat -d -v epoch` filtered for `KernelAI` and audio input lines.
