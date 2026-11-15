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
- Sherpa post-stop write guard (`SherpaOnnxVoiceOutputController`): the synthesis producer drops a chunk it finishes after the playback token is no longer active, and the AudioTrack `shouldContinue` now also requires the streaming token to be active. Previously it checked only the global `stopped` flag, which a later `speak()` can reset.
- Foreground service: not added. The media card renders from the session plus notification without one (see check 4).

## Verification

Automated (after the notification and Sherpa changes):
- `:core:voice:testDebugUnitTest`: all suites pass.
- `:app:testDebugUnitTest`: pass (includes `SpeechStopSessionLifecycleTest`).
- `:app:assembleDebug :app:lintDebug`: exit 0; 25 pre-existing warnings, none in touched files.
- Earlier run (a10f74dc): `:feature:chat:testDebugUnitTest` all suites pass (ChatViewModel 47 voice tests, ActionsViewModel 149, Init 31).

Device (S23U `RFCW11265XH`; Sherpa voice `en_GB-cori-high`; speech via harness token + `quick_action_is_voice=true`; media volume 1 during runs, restored to 11 after):

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | In-app volume-down stops speech; media volume unchanged | PASS (audio stopped, volume stayed 1) | a10f74dc: `SpeakingStarted engine=sherpa chars=77` 10:51:03.5 (approx.); `AudioTrack: stop(3412): called with 16384 frames delivered` at 10:51:06.124; `abandonAudioFocus` 10:51:06.067. Listening resumption NOT proven: no recognizer-start log in the window. |
| 2 | `cmd media_session dispatch stop` stops speech | PASS | a10f74dc: `AudioTrack: stop(3413): called with 20480 frames delivered` at 10:51:27.535 after `SpeakingStarted` at 10:51:26.678. |
| 3 | `dumpsys media_session` shows JandalSpeech PLAYING with Stop action | PASS | `state=PLAYING(3) ... custom actions=[Action:mName='Stop'] ... metadata description=Jandal is speaking`. |
| 4 | Stop control on the media card (shade) during speech | PASS (card and Stop tap) | Build 11:00:xx (`pm grant` POST_NOTIFICATIONS): Quick Settings panel shows the "Jandal is speaking" card with Stop and Pause; `input tap` on Stop at 11:01:03.35 → `SpeakingStarted` 11:01:00.306 (104 chars), `AudioTrack: stop(3430): called with 69632 frames delivered` at 11:01:03.402 (about 3.2 s into a ~7 s reply). Screenshot: `media-card-shade.png`. |
| 4b | Card without POST_NOTIFICATIONS | PASS (card shown) | Permission revoked (default denied state, `granted=false`); the QS media card still shows with Stop and Pause. Screenshot: `media-card-shade-permission-denied.png`. The notification list dump omitted the card while denied; System UI hides denied apps' notifications there but the media card renders anyway. |
| 5 | Stop on the lock-screen media card | BLOCKED | Device locked (keyguard showing, `deviceLocked=1`, no trust agent) before the run; the quick action needs an unlocked device. Not unlocked: needs owner PIN. Lock-screen volume-key stop remains #1916. |

Post-stop audio check (check 4 run): after `AudioTrack: stop(3430)` at 11:01:03.402 there is no further AudioTrack start, write or state event for the app's process in logcat. This is log evidence, not a sample-level proof. The code change gates writes on the playback token, so a chunk synthesised after stop is dropped before it reaches AudioTrack.

Open:
- Check 5 needs the owner to unlock the S23U (or run it on an unlocked device) and repeat: start a speech, sleep the screen, wake to the keyguard, confirm the card, tap Stop.
- Check 1 listening resumption is unproven (no recognizer-start log).
