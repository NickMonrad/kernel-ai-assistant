# #1873 evidence: stop Jandal talking

## Scope (owner decision)

In scope for #1913:
- In-app volume-key stop while Jandal is speaking (MainActivity and the Hey Jandal voice overlay, VoiceCommandActivity).
- Media-controls Stop (MediaSession Stop/Pause and the "Stop" custom action).

Out of scope, moved to #1916: volume-key stop while the screen is locked or the app is in the background. The remote VolumeProvider was removed; it did not receive keys on S23U.

## Implementation

- `core/voice` `SpeechStopCoordinator`: `requestStop()` acts only while speaking (`compareAndSet`), emits `stopRequests` when a screen collects, otherwise calls `VoiceOutputController.stop()` directly.
- `core/voice` `SpeechStopKeyTracker`: consumes a volume-key down only when a stop is accepted; swallows its auto-repeats and key-up. Used from `onKeyDown`/`onKeyUp` (no `dispatchKeyEvent`, so no RestrictedApi lint error).
- `ChatViewModel` and `ActionsViewModel` collect `stopRequests`: chat calls `stopVoicePlayback()` (drops the open speech stream) and re-arms back-and-forth listening; Actions calls `stopAllVoiceOutput()`.
- Engines: Sherpa streaming and Inflect emit `SpeakingStopped` on stop; `trySend` avoids closed-channel throws.
- `SpeechStopSessionManager`: MediaSession opened on `SpeakingStarted`, released on `SpeakingStopped`; callbacks for Stop, Pause and custom action route to `requestStop()`. No app notification, no POST_NOTIFICATIONS dependency, no receiver.

## Verification

Automated (this commit):
- `:core:voice:testDebugUnitTest`: all suites pass (includes `SpeechStopCoordinatorTest` incl. stop-routing and race tests, `SpeechStopKeyTrackerTest`).
- `:feature:chat:testDebugUnitTest`: all suites pass (ChatViewModel and ActionsViewModel constructed with the coordinator).
- `:app:testDebugUnitTest --tests com.kernel.ai.speech.*`: `SpeechStopSessionLifecycleTest`, 4 passing.
- `:app:assembleDebug :app:lintDebug`: passes; no warnings in the touched files.

Device (S23U `RFCW11265XH`, debug build installed, lastUpdateTime 2026-11-15 10:46:59):
- Idle volume keys change STREAM_MUSIC normally (11 → 10), then restored to 11.

Not verified on device in this round:
- Volume-key stop during speech on the new key path. A typed chat prompt via ADB did not produce spoken output, so no speech session opened.
- Media-controls Stop button in the shade and `cmd media_session dispatch stop` while speaking.
- Hey Jandal overlay volume-key stop.
- Earlier evidence (in-app stop ~26 ms, AudioTrack stopped) was on the pre-review design and does not cover the current key path.

Lock screen and background: deferred to #1916.
