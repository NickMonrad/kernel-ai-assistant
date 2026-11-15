# #1850 S23U stop-during-load evidence (code head 4ea9a473)

Device: `RFCW11265XH`, package `ai.jandal.assistant.debug`, installed with `adb install -r` from the `4ea9a473` APK. `dumpsys package` read-back: `lastUpdateTime=2026-11-15 16:22:33`, versionName 0.1.0.

## Stop during cold model load: PASS
Sequence: `am force-stop`, `logcat -c`, `am start com.kernel.ai.MainActivity`, mic FAB tap on the chat list, Stop tap about 1 s later.

Logs (`logcat-stop-during-load.txt`, filtered to the app pid 7182):
- `SherpaSTT: Sherpa STT start abandoned: stopped while the recognizer was loading (sessionId=1)` at 16:23:49.983.

Logs (`logcat-stop-during-load-audio-tags.txt`, tags MediaFocusControl, AudioRecord, AudioFlinger, AudioPolicyManager, SherpaSTT, KernelAI):
- `MediaFocusControl: requestAudioFocus() ... callingPack=ai.jandal.assistant.debug` at 16:23:48.761 (mic open).
- `MediaFocusControl: abandonAudioFocus() ... callingPack=ai.jandal.assistant.debug` at 16:23:49.763 (after Stop; focus released).
- Android logs no AudioRecord release line; the mic release is shown by the focus abandon, the cleared indicator below, and the `stopped while loading` branch that closes the capture.
Screens:
- `stop-during-load-listening.png`: capture started (1 s after the tap).
- `stop-during-load-after-stop.png`: 2 s after Stop, a small green mic dot still visible at the top-right edge.
- `stop-during-load-after-stop-4s.png`: 4 s after Stop, the dot has cleared (no mic indicator).

## Setup changes on the owner device (disclosed)
- RECORD_AUDIO for `ai.jandal.assistant.debug` was `granted=false` at the start of this run. I granted it via the system prompt "While using the app" to run the mic path. It remains granted; revoke if the owner wants it off.
- The first mic tap showed the in-app "Allow voice input?" dialog (not a start); this is why an earlier attempt captured no focus lines.
- Restored: font_scale 1.15, media volume 11 (both unchanged in this run). Lock `/tmp/jandal-s23u.lock` released by its owner (omp-1850).

## Not in this run
- Hey Jandal pre-roll handoff timing: moved to the pre-release QA pass on the S21.
- Chat Listening font 1.15/2.0 and hint-on-6th-use: captured earlier on the 13:59 build (see `../README.md`).
