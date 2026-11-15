# #1873 evidence: stop Jandal talking

## Scope (owner decision)

In scope for #1913:
- In-app volume-key stop while Jandal is speaking (MainActivity and the Hey Jandal voice overlay, VoiceCommandActivity).
- Media-controls Stop: MediaSession Stop/Pause and the "Stop" custom action, plus a MediaStyle card bound to that session.

Out of scope, moved to #1916: volume-key stop while the screen is locked or the app is in the background. The remote VolumeProvider was removed; it did not receive keys on S23U.

## Implementation

- `core/voice` `SpeechStopCoordinator`: `requestStop()` acts only while speaking (`compareAndSet`), emits `stopRequests` when a screen collects, otherwise calls `VoiceOutputController.stop()` directly.
- `core/voice` `SpeechStopKeyTracker`: consumes a volume-key down only when a stop is accepted; swallows its auto-repeats and key-up. Used from `onKeyDown`/`onKeyUp` (no `dispatchKeyEvent`, so no RestrictedApi lint error).
- `ChatViewModel` and `ActionsViewModel` collect `stopRequests`: chat calls `stopVoicePlayback()` and re-arms back-and-forth listening; Actions calls `stopAllVoiceOutput()`.
- Engines: Sherpa streaming and Inflect emit `SpeakingStopped` on stop; `trySend` avoids closed-channel throws.
- `SpeechStopSessionManager`: on `SpeakingStarted` opens a MediaSession (`JandalSpeech`, PlaybackState PLAYING with ACTION_STOP|ACTION_PAUSE and a "Stop" custom action, metadata title "Jandal is speaking", artist "Jandal AI") and posts a MediaStyle notification bound to the session token (channel `speech_playback`, IMPORTANCE_LOW, id 1873, public visibility, ongoing). On `SpeakingStopped` it releases the session and cancels the notification. Not gated on POST_NOTIFICATIONS. No receiver.
- Sherpa post-stop write guard (`SherpaOnnxVoiceOutputController`): the synthesis producer drops a chunk it finishes after the playback token is no longer active, and the AudioTrack `shouldContinue` also requires the streaming token to be active. Previously it checked only the global `stopped` flag, which a later `speak()` can reset.
- Foreground service: not added. The media card renders from the session plus notification without one (check 4).

## Verification

Automated (final build, includes the notification and Sherpa changes):
- `:core:voice:testDebugUnitTest`: all suites pass.
- `:app:testDebugUnitTest`: pass (includes `SpeechStopSessionLifecycleTest`).
- `:app:assembleDebug :app:lintDebug`: exit 0; 25 pre-existing warnings, none in touched files.
- Earlier (a10f74dc): `:feature:chat:testDebugUnitTest` all suites pass (ChatViewModel 47 voice tests, ActionsViewModel 149, Init 31).

## Device results

Two phones were used. They run different speech engines, so the table names the phone for each check.

| # | Check | Phone / engine | Result | Evidence |
|---|---|---|---|---|
| 1 | In-app volume-down stops speech; media volume unchanged | S23U, Sherpa | PASS | Build a10f74dc. `SpeakingStarted engine=sherpa chars=77`; `AudioTrack: stop(3412): called with 16384 frames delivered` at 10:51:06.124; `abandonAudioFocus` 10:51:06.067; media volume stayed 1. Listening resumption NOT proven (no recognizer-start log). |
| 2 | `cmd media_session dispatch stop` stops speech | S23U, Sherpa | PASS | Build a10f74dc. `AudioTrack: stop(3413): called with 20480 frames delivered` at 10:51:27.535 after `SpeakingStarted` at 10:51:26.678. |
| 3 | Session shows PLAYING with Stop action | S23U, Sherpa | PASS | `state=PLAYING(3) ... custom actions=[Action:mName='Stop'] ... metadata description=Jandal is speaking`. |
| 4 | Stop control on the media card (quick-settings shade) during speech | S23U, Sherpa | PASS | Build 93f16ccc-era (notification build). Card with Stop and Pause shown. `input tap` on Stop at 11:01:03.35 → `SpeakingStarted` 11:01:00.306 (104 chars); `AudioTrack: stop(3430): called with 69632 frames delivered` at 11:01:03.402 (about 3.2 s into a ~7 s reply). Screenshot: `media-card-shade.png`. |
| 4b | Card without POST_NOTIFICATIONS | S23U, Sherpa | PASS (card shown) | Permission denied (default). Card still shown with Stop and Pause. Screenshot: `media-card-shade-permission-denied.png`. |
| 5 | Stop on the lock-screen media card | S21 `R5CR605B71K`, Android TTS | FAIL: no card; speech stopped on screen-off | See below. |

Check 5 detail (S21, build 93f16ccc-era, keyguard enabled for the test and restored to disabled afterwards):
- Speech started 11:08:45.257 (`engine=android_tts chars=83`). `input keyevent KEYCODE_SLEEP` at 11:08:45.715. `AudioTrack: stop(57): called with 17056 frames delivered` at 11:08:45.900 (about 0.2 s after the screen went off).
- Control run (no sleep): `SpeakingStarted` 11:09:11.429; `AudioTrack: stop(58): called with 197237 frames delivered` at 11:09:19.751 (about 8.3 s, full reply). So the stop comes from the screen-off, not from the reply ending.
- After wake without dismissing the keyguard, the lock screen showed with no Jandal card. Its screenshot shows personal photos, so it is not committed. No card existed, so no Stop tap was performed.
- Cause not located. Candidates: the screen-off/background lifecycle stop in `ActionsViewModel`/`ChatViewModel` voice output, or the Android TTS controller. This is lock/background behaviour, which is the #1916 scope.
- The S21 has no Sherpa voice pack downloaded, so it used `android_tts`. Check 5 on the Sherpa engine is not covered.

Post-stop audio check (S23U, check 4 run): after `AudioTrack: stop(3430)` at 11:01:03.402 there is no further AudioTrack start, write or state event for the app's process in logcat. This is log evidence, not a sample-level proof. The code change gates writes on the playback token, so a chunk synthesised after stop is dropped before it reaches AudioTrack.

## Open

- Check 5 (lock-screen card and Stop tap) cannot pass while speech stops on screen-off. The fix belongs with lock/background behaviour (#1916), not this PR.
- Check 1 listening resumption is unproven (no recognizer-start log).
- Check 5 is not covered on the Sherpa engine.
