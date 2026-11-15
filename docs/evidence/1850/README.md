# #1850 on-device evidence

## S21 voice overlay (VoiceCommandActivity), SM-G991B-class device, serial R5CR605B71K
Build: debug APK at `9b6e2d92` (`feature/mic-ready-preroll-1850`), `ai.jandal.assistant.debug`, installed with `adb install -r`. No clear or uninstall; downloaded models untouched.
Conditions: media volume 0 during test (restored to 8). Recognizer route: Android built-in (Zipformer not installed on S21; Sherpa not exercised).
- `s21-1850-overlay-listening.png` (local only, `/tmp`): overlay at Listening with ring and hint "Your words are being captured."
- Mic permission granted "While using the app" for the debug build; session cancelled after capture.
- NOT run on S21: hint on 6th use; cold-load stop path (needs Sherpa); Starting frame; chat Listening at font 1.15/2.0.

## S23U Actions voice (SM_S918B, serial RFCW11265XH)
Build: the S23U Actions screenshots and timings below were captured on the debug build installed at 13:59 (screenshot status-bar times 14:02/14:06 match that install). Its exact SHA was not recorded; attribute to that install, not to later heads. Package `ai.jandal.assistant.debug`, versionName 0.1.0, `adb install -r`, lastUpdateTime verified at install time.
Conditions: media volume 0 during capture (restored to 11), font_scale 1.15 and 2.0 (restored to 1.15). Recognizer route: Zipformer (Sherpa). Only Actions-screen voice capture was exercised. No speech was spoken; no transcripts were captured.

### Screenshots
- `actions-listening-hint-font1.15.png`: Listening, speak now, hint "Speak now: your words are being captured.", pulse ring, mic indicator in status bar.
- `actions-cold-start-font1.15.png`: first tap after cold start, Listening state.
- `actions-idle-font2.0.png`: Actions idle at font 2.0.
- `actions-listening-hint-font2.0.png`: Listening with hint at font 2.0. The hint truncates to "words are being …" (layout limit, not fixed).
- Starting state: NOT captured. The Starting window is about 0.1 s and screencap could not catch it.

### Timings (device clock, logcat `KernelAI` and audio HAL lines)
| Run | Tap (device ms) | requestVoiceCapture / startVoiceCapture | AudioRecord input opened (APM getInputForAttr) | startListening returned |
|---|---|---|---|---|
| Cold start, font 1.15 | 1794702667.681 | .787 (+106 ms) | .895 (+214 ms) | 669.063 (+1382 ms) |
| Warm, font 1.15 | 1794702638.782 | .887 (+105 ms) | .898 (+116 ms) | .935 (+153 ms) |

- Tap-to-Listening: the ListeningStarted cue fires when capture opens, so tap-to-Listening is the AudioRecord-open time: cold 214 ms, warm 116 ms (tap timestamps come from `adb shell date` before `input tap`, so each has roughly 100 ms of adb overhead).
- Tap-to-recognizer-ready: NOT MEASURED. The app does not log when `ensureRecognizer` completes. The cold `startListening` gap (1.38 s) is a proxy only.

## Not done / owner checks
- Three "speak immediately" Brisbane transcript tries: owner to run by hand; not performed by the agent.
- Stop-during-model-load: PASS on S23U at code head `4ea9a473` (installed 16:22:33). Logs in `s23u-r4/logcat-stop-during-load.txt` (`start abandoned: stopped while the recognizer was loading`) and `s23u-r4/logcat-stop-during-load-audio-tags.txt` (requestAudioFocus 16:23:48.761, abandonAudioFocus 16:23:49.763). Indicator cleared by 4 s (`s23u-r4/stop-during-load-after-stop-4s.png`). Unit tests for the same path at `4ea9a473`. Details: `s23u-r4/README.md`.
- Native (non-Sherpa) route has no pre-roll; not run on device.
- S21 items above.
