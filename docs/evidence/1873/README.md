# #1873 evidence: stop Jandal talking

## Scope (owner decision)

In scope for #1913:
- In-app volume-key stop while Jandal is speaking (MainActivity and the Hey Jandal voice overlay, VoiceCommandActivity).
- Media-controls Stop (MediaSession Stop/Pause and the "Stop" custom action).

Out of scope, moved to #1916: volume-key stop while the screen is locked or the app is in the background. The remote VolumeProvider was removed; it did not receive keys on S23U.

## Implementation

- `core/voice` `SpeechStopCoordinator`: `requestStop()` acts only while speaking (`compareAndSet`), emits `stopRequests` when a screen collects, otherwise calls `VoiceOutputController.stop()` directly.
- `core/voice` `SpeechStopKeyTracker`: consumes a volume-key down only when a stop is accepted; swallows its auto-repeats and key-up. Used from `onKeyDown`/`onKeyUp` (no `dispatchKeyEvent`, so no RestrictedApi lint error).
- `ChatViewModel` and `ActionsViewModel` collect `stopRequests`: chat calls `stopVoicePlayback()` and re-arms back-and-forth listening; Actions calls `stopAllVoiceOutput()`.
- Engines: Sherpa streaming and Inflect emit `SpeakingStopped` on stop; `trySend` avoids closed-channel throws.
- `SpeechStopSessionManager`: MediaSession opened on `SpeakingStarted`, released on `SpeakingStopped`; callbacks for Stop, Pause and custom action route to `requestStop()`. No app notification, no POST_NOTIFICATIONS dependency, no receiver.

## Verification

Automated (commit a10f74dc):
- `:core:voice:testDebugUnitTest`: all suites pass (includes `SpeechStopCoordinatorTest`, `SpeechStopKeyTrackerTest`).
- `:feature:chat:testDebugUnitTest`: all suites pass (ChatViewModel 47 voice tests, ActionsViewModel 149, Init 31).
- `:app:testDebugUnitTest --tests com.kernel.ai.speech.*`: `SpeechStopSessionLifecycleTest` 4/4.
- `:app:assembleDebug :app:lintDebug`: passes; no warnings in touched files.

Device (S23U `RFCW11265XH`, debug build a10f74dc installed; Sherpa voice `en_GB-cori-high`; speech via harness token + `quick_action_is_voice=true`, query "weather in Brisbane"; media volume 1 during run, restored to 11 after):

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | In-app volume-down stops speech; media volume unchanged | PASS (audio stopped, volume stayed 1) | `SpeakingStarted engine=sherpa chars=77 epochMs=1794703865551`; `AudioTrack: stop(3412): called with 16384 frames delivered` at 10:51:06.124 (0.6 s after start, a 77-char reply takes ~4 s); `abandonAudioFocus` 10:51:06.067. Listening resumption NOT proven: no recognizer-start log in the window; quick-action voice may not be back-and-forth mode. |
| 2 | `cmd media_session dispatch stop` stops speech | PASS | `AudioTrack: stop(3413): called with 20480 frames delivered` at 10:51:27.535 after `SpeakingStarted` at 10:51:26.678; `abandonAudioFocus` 10:51:27.474. |
| 3 | `dumpsys media_session` shows JandalSpeech PLAYING with Stop action | PASS | `Sessions Stack: JandalSpeech ... state=PLAYING(3) ... custom actions=[Action:mName='Stop'] ... metadata description=Jandal is speaking`. |
| 4 | Stop in the expanded shade | FAIL: no media card | Two shade dumps during speech (`uiautomator`): notification list only; no Jandal card, no Stop node, although the session was PLAYING. |
| 5 | Stop on the lock screen | FAIL: no media card | Keyguard dump during speech (after KEYCODE_SLEEP): no media controls; session still PLAYING. Lock-screen volume-key stop remains #1916. |

Notes:
- Synthesis chunk at 10:51:09 (58 chars) was logged after the in-app stop; whether audio was written after the stop was not checked. Follow-up: confirm no `AudioTrack` write after stop.
- No screenshots were captured; the shade and keyguard dumps show no media card, so there was nothing to screenshot beyond what the XML records.

Gap to resolve (decision needed): Android's shade and lock-screen media controls are built from a MediaStyle notification bound to the session. Removing the app notification (reviewer item "Drop POST_NOTIFICATIONS") left checks 4 and 5 without a visible control. Options: (a) re-add a MediaStyle notification with the session token and Stop action (reintroduces a notification; POST_NOTIFICATIONS needed on Android 13+), or (b) keep the MediaSession-only design and remove the shade and lock-screen claims from #1913.
