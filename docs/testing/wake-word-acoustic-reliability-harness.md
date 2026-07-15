# Unattended acoustic wake-word reliability harness

**Issue:** #1403  
**Parent investigation:** #1402  
**Related optimisation work:** #1395, #1398, #1399  
**Status:** Proposed design; physical source-to-target feasibility check still required before implementation slices are finalised.

## 1. Decision summary

Use a **host-controlled Android source device** to play repeatable acoustic fixtures into the real microphone of a separate target device.

The selected permanent source mechanism is a small component compiled only into the Jandal **debug** build:

- source location: `app/src/debug/`;
- invocation: explicit ADB broadcast to a debug-only receiver;
- playback: platform `MediaPlayer` reading an app-private WAV through a file descriptor;
- evidence: machine-readable source result plus a narrow `AcousticStimulus` debug log;
- volume: record, set to a calibrated media-stream index, and restore exactly;
- fixtures: private natural-voice WAVs are primary; TTS-generated WAVs are supplementary;
- release isolation: no source component, manifest entry, resource, action or fixture may appear in release APK/AAB output.

Do **not** rely on a system media player, Voice Recorder playback, VLC, mpv, Termux, Media3/ExoPlayer, or live TTS as the permanent runner dependency.

The source helper only solves stimulus delivery. A later host-side runner must still schedule trials, preserve target idle, collect target evidence after each trial, classify failures, sanitise reports and restore state.

## 2. Why this is needed

The remaining #1402 concern appears only after an idle period. Requiring an operator to return every 15–30 minutes to speak a wake phrase is slow, inconsistent and unsuitable for repeated launch validation.

The harness must reduce operator effort to one setup and calibration checkpoint while preserving the real product path on the target:

```text
speaker on source Android device
  -> room acoustics
  -> target microphone / AudioRecord
  -> RMS silence gate
  -> ONNX mel, embedding and classifier stages
  -> wake activation callback
  -> assistant session / acknowledgement cue
  -> STT handoff or timeout
  -> detector re-arm
```

Direct PCM fixture injection remains valuable for deterministic model and silence-transition testing, but it bypasses the microphone, audio routing, service and cue/handoff path. It is complementary rather than a replacement for this acoustic harness.

## 3. Constraints and design principles

1. **One operator checkpoint, not one per trial.**
2. **S21 is the primary target.** The S23 Ultra is the normal source and a smaller comparison target.
3. **No production behaviour changes.** Do not alter thresholds, models, provider policy, silence gating, cue behaviour or service lifecycle to make the harness work.
4. **Target idle must remain credible.** Do not poll or stream logcat from the target during the configured idle interval.
5. **Every source playback must be proven.** A missing target activation is uninterpretable when the source event is unknown.
6. **Missing target evidence is inconclusive, not zero.**
7. **Raw voice and device evidence are private.** Public output is sanitised and alias-based.
8. **Release exclusion is a build gate, not an assumption.**
9. **Use the smallest sufficient playback implementation.** One local WAV does not require a streaming framework or full media application.
10. **Separate detector reliability from command timing.** Wake-only trials are the primary recall oracle; wake-plus-command trials are a smaller handoff suite.

## 4. Research findings and option decision

### 4.1 Platform `MediaPlayer` with a fixed WAV — selected

Android `MediaPlayer` accepts local input through a file descriptor and exposes completion and error listeners. That is sufficient for short, local PCM WAV playback without adding a media framework.

References:

- [Android `MediaPlayer`](https://developer.android.com/reference/android/media/MediaPlayer)
- [`setOnCompletionListener`](https://developer.android.com/reference/android/media/MediaPlayer#setOnCompletionListener(android.media.MediaPlayer.OnCompletionListener))

**Strengths**

- exact waveform repeatability;
- explicit prepared, started, completed and failed states;
- no third-party runtime dependency;
- supports app-private files through a file descriptor;
- small implementation surface;
- easy to isolate to `src/debug`;
- supports natural and pre-generated TTS WAVs through the same path.

**Risks and mitigations**

- global media volume affects acoustic level: capture and restore the exact stream index;
- source process could be killed: use a short explicit broadcast with `goAsync()` and bounded playback; validate on both Samsung devices;
- exported test entry point: compile only in debug, require an explicit component, accept allowlisted fixture IDs rather than arbitrary paths, and prove absence from release output;
- OEM audio differences: calibration and source metadata are part of every run.

### 4.2 Native Android TTS — supplementary only

Android TTS can report utterance progress and can synthesize speech to a file. The repository already uses `TextToSpeech` with progress callbacks in `AndroidTextToSpeechController`, proving device support for the API.

References:

- [Android `TextToSpeech`](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [`synthesizeToFile`](https://developer.android.com/reference/android/speech/tts/TextToSpeech#synthesizeToFile(java.lang.CharSequence,android.os.Bundle,java.io.File,java.lang.String))
- [`setOnUtteranceProgressListener`](https://developer.android.com/reference/android/speech/tts/TextToSpeech#setOnUtteranceProgressListener(android.speech.tts.UtteranceProgressListener))

**Decision**

- Do not synthesize live during each reliability trial.
- Optionally synthesize once to a WAV, record engine package, voice, locale, rate, pitch and SHA-256, then replay that immutable file through the selected player.
- Treat TTS as supplementary coverage or orchestration validation.
- A TTS miss is not evidence that natural user speech is broken.

The helper must not reuse or change production assistant-output behaviour merely to generate a test stimulus.

### 4.3 System media player / generic `ACTION_VIEW` — rejected

A generic media intent delegates to whichever application handles the MIME type. The harness would not reliably own:

- selected handler;
- playback start and completion;
- queue state;
- previous media state;
- lock-screen or UI behaviour;
- audio focus;
- app-update stability;
- cleanup after cancellation.

This may be acceptable for a one-off manual sanity check, but not as an evidentiary unattended runner.

### 4.4 Voice Recorder — fixture creation only

Samsung Voice Recorder or another recorder may be used to capture the original private phrase. It is not selected for automated playback because it is UI-driven and provides no stable host completion contract.

Raw recorder output must be converted and validated on the host before it becomes a fixture.

### 4.5 Termux and Termux:API — useful disposable proof, rejected permanently

Termux:API exposes media playback and TTS commands. `termux-media-player` can play one local file and report playback information; `termux-tts-speak` supports engine, locale, pitch, rate and stream selection.

References:

- [Termux:API repository](https://github.com/termux/termux-api)
- [`termux-media-player`](https://github.com/termux/termux-api-package/blob/master/scripts/termux-media-player.in)
- [`termux-tts-speak`](https://github.com/termux/termux-api-package/blob/master/scripts/termux-tts-speak.in)

**Why it is not the permanent dependency**

- requires Termux plus its API add-on from compatible signing sources;
- introduces installation, permission and package-maintenance steps unrelated to Jandal;
- adds a large external environment for a tiny playback requirement;
- Termux documents Android process-lifetime caveats and is seeking Android application maintainers;
- trial IDs, exact restoration and result schema would still require custom wrapping.

Termux remains an optional no-code topology check when already installed. The project should not require the operator to install it solely for this harness.

### 4.6 VLC, mpv and other open-source player applications — rejected

VLC and mpv-android are capable players, but they are full applications intended for broad media use. Invoking an external app does not automatically provide a stable per-trial completion and cleanup contract. VLC also brings a substantially larger native media stack; mpv-android explicitly states that the application is not an importable Android library.

References:

- [VLC for Android](https://github.com/videolan/vlc-android)
- [mpv-android](https://github.com/mpv-android/mpv-android)

They provide no advantage over platform playback for a short local WAV.

### 4.7 Media3 / ExoPlayer — rejected for the initial implementation

Media3 adds streaming protocols, renderers, buffering, playlists and a broader player abstraction. Those are useful for a media application but are unnecessary for one local short WAV.

Reference: [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer)

Reconsider only when the platform implementation demonstrates a concrete unsupported requirement.

## 5. Decision matrix

| Criterion | Live native TTS | System / installed player | Termux / open-source app | Debug platform player |
|---|---|---|---|---|
| Exact repeatability | Low | Medium with fixed WAV | High with fixed WAV | **High** |
| Natural-speech validity | Low–medium | High with natural WAV | High with natural WAV | **High with natural WAV** |
| Host automation | Requires new entry point | Low / app-specific | Medium–high | **High** |
| Start/completion evidence | Good API callbacks | Usually weak | Medium | **Strong callbacks + result file** |
| Volume restoration | Custom work | Weak | Wrapper required | **Owned and testable** |
| External dependency | Platform TTS engine | Handler app | Multiple external apps | **None** |
| Private app-file support | Custom work | Poor | Termux-private or shared file | **App-private file descriptor** |
| Release-build isolation | Possible | Not applicable | External | **Debug source-set gate** |
| Setup burden | Medium | Low initially, high to stabilise | High when not installed | **Low after helper install** |
| Long-term maintenance | Medium | Low confidence | Medium–low | **High** |
| Suitable primary release oracle | No | No | Possible but operationally weak | **Yes, with natural WAV calibration** |

## 6. Selected test topology

```text
Host-side Python runner
  |
  +-- ADB -> source device (normally S23U)
  |           - Jandal debug build installed
  |           - Listen for Hey Jandal disabled
  |           - debug acoustic source receiver
  |           - private app-local fixtures
  |
  +-- ADB -> target device (normally S21)
              - current Jandal debug build
              - Listen for Hey Jandal enabled
              - WakeWordDiag plus focused trace events
              - locked and screen off during idle
```

The topology must support swapping aliases so that S21 becomes source and S23U becomes target for comparison.

### Source self-activation prevention

Before a run, the host must verify on the source:

- **Listen for Hey Jandal** is disabled;
- the wake-word service is inactive;
- no assistant voice session is active;
- media output route is the built-in speaker unless the run explicitly records another route.

A source device that can respond to its own stimulus invalidates the run.

## 7. Debug-only acoustic source design

### 7.1 Build placement

Use Android's build-type source sets:

```text
app/src/debug/AndroidManifest.xml
app/src/debug/java/com/kernel/ai/debug/acoustic/AcousticStimulusReceiver.kt
```

Android Gradle merges `src/debug` only into debug variants. The main app already has a distinct `com.kernel.ai.debug` application ID.

Reference: [Configure Android build variants and source sets](https://developer.android.com/build/build-variants)

### 7.2 Invocation contract

Invoke an explicit receiver component from ADB. The exact action is internal to the harness, for example:

```text
com.kernel.ai.debug.action.PLAY_ACOUSTIC_STIMULUS
```

Required extras:

- `trial_id` — host-generated opaque identifier;
- `fixture_id` — allowlisted filename stem, never an arbitrary path;
- `volume_index` — validated against the source stream maximum;
- optional `player_gain` — bounded `0.0..1.0`, normally `1.0`.

The receiver must reject:

- missing or malformed trial IDs;
- traversal characters or arbitrary paths;
- unknown fixture IDs;
- concurrent playback;
- files that are absent, empty, too long or not in the accepted WAV format;
- unsafe volume values.

### 7.3 Playback lifecycle

1. Use `goAsync()` to hold the explicit broadcast only for the bounded short playback.
2. Record source wall-clock and monotonic timestamps.
3. Resolve an app-private fixture under a fixed directory.
4. Validate fixture metadata and SHA-256 against the private fixture manifest.
5. Capture current media-stream volume and route.
6. Apply the calibrated media-stream volume.
7. Request transient audio focus using media/speech attributes.
8. Open the fixture through a file descriptor.
9. Prepare and start `MediaPlayer`.
10. Emit structured `prepared` and `started` events.
11. On completion or error, release the player, abandon focus and restore the exact original volume.
12. Verify restoration.
13. Write a machine-readable private result and emit one final structured event.
14. Complete the pending broadcast result.

Playback must have a hard timeout shorter than the broadcast execution allowance. The initial fixture limit should be five seconds. Wake-plus-command should therefore use separate short files or a deliberately bounded combined file.

If Samsung firmware proves the asynchronous receiver lifecycle unreliable, the implementation issue may substitute a debug-only foreground service while preserving the same host contract and result schema. That is a fallback, not the initial design.

### 7.4 Source evidence

Use a dedicated tag such as `AcousticStimulus`, enabled only in debug builds. Do not use broad `KernelAI` debug logging.

Record:

- trial ID;
- fixture ID and SHA-256;
- request, prepare, start, completion and cleanup monotonic times;
- volume before, requested, applied and restored;
- output route summary;
- completion status or error category;
- player timeout;
- overlapping-request rejection;
- cleanup/restoration success.

The same fields must be available in a private JSON result so the runner does not depend solely on logcat text parsing.

## 8. Stimulus strategy

### 8.1 Primary fixtures: natural voice WAV

Use a small private fixture set rather than one recording:

1. normal natural **“Hey Jandal”**;
2. faster onset;
3. lower-amplitude derivative of the normal fixture;
4. optional second natural take;
5. negative speech / near phrase;
6. silence-only control.

The first #1402 matrix should use the normal natural fixture as its primary oracle. Additional fixtures help explain a failure but must not be mixed together when calculating a pass rate.

### 8.2 Canonical format

Recommended canonical fixture format:

- RIFF/WAVE;
- linear PCM;
- signed 16-bit little-endian;
- mono;
- 48 kHz;
- no embedded personal metadata;
- maximum duration five seconds;
- peak-normalised to a documented level, initially `-3 dBFS`;
- attenuated variants derived digitally and recorded in manifest metadata.

The target still receives the fixture acoustically and performs its normal 16 kHz capture path.

### 8.3 Wake-only versus command fixtures

The primary reliability matrix uses **wake-only** fixtures. This avoids misclassifying a fixed command-delay failure as a wake-word miss.

A smaller end-to-end suite may use:

- a combined fixture containing wake phrase, calibrated silence and command; or
- separate wake and command fixtures with a fixed calibrated delay.

Version 1 must not poll the target during the idle interval or require reactive command playback. Any combined fixture must record its exact gap and must not be used as the sole detector-recall oracle.

### 8.4 TTS fixtures

When TTS is evaluated:

- synthesize once to file;
- record engine package/version where available;
- record voice name, locale, pitch and rate;
- hash the output;
- replay through the same `MediaPlayer` path;
- label results as synthetic;
- do not treat synthetic failure as a product regression without natural-voice corroboration.

### 8.5 Fixture privacy

Private structure:

```text
scripts/private-acoustic-fixtures/<fixture-set-id>/
  manifest-private.json
  natural/
  synthetic/
```

This directory must be gitignored. Do not commit raw human speech, recorder metadata, source paths or speaker identity.

Commit-safe fixture metadata may include:

- opaque fixture ID;
- type (`natural`, `tts`, `negative`, `silence`);
- duration;
- sample format;
- SHA-256;
- digital attenuation;
- approval status.

## 9. Physical setup and calibration

Document and photograph locally, but do not publish room or identifying images.

Recommended initial setup:

- phones stationary on the same non-vibrating surface;
- source speaker aimed toward target microphone;
- approximately 30 cm separation, measured and recorded;
- no case or accessory obstructing source speaker or target microphone;
- source output route set to built-in speaker;
- no Bluetooth audio route;
- quiet room for the primary matrix;
- target locked and screen off;
- wireless ADB established before the idle interval.

### Calibration procedure

1. Validate source and target identities with public aliases only.
2. Verify source wake service inactive and target wake service active.
3. Verify the fixture SHA-256 on host and source.
4. Start at a conservative source media volume.
5. Play the normal wake fixture three times with the target recently armed.
6. Require three valid source completions and three target wake activations.
7. When calibration fails, adjust physical placement first, then volume.
8. Record the selected source volume index, maximum index, route and placement.
9. Lock the target and begin the unattended matrix.

Calibration trials do not count toward the post-idle result.

The selected volume should be the lowest stable level that gives 3/3 recently-armed activations in that setup, not automatically maximum volume.

## 10. Preserving target idle

The host must not continuously stream target logcat or poll target state during a configured idle wait.

For each trial:

1. Validate target state before the idle countdown.
2. Take a target diagnostic-counter snapshot.
3. Start the idle timer.
4. Do not issue target ADB commands during the idle interval.
5. Trigger playback only on the source device.
6. Wait a fixed post-stimulus observation window.
7. Query target state and retrieve focused logs/counter deltas after that window.

This preserves the source-to-target acoustic event as the first deliberate activity at the end of the idle interval.

The reliability runner is not a battery benchmark, but it should still record material target interaction, screen-on, charging, service loss, reboot and ADB outage so invalid trials are not treated as recall failures.

## 11. Target observability requirements

`WakeWordDiag` cumulative summaries are valuable for run-level cadence but are too coarse for a single acoustic attempt. A separate implementation slice should audit existing logs and add only missing debug-gated trace events.

Required target events or equivalent evidence:

- detector generation/start identifier;
- silence gate entered;
- first voiced frame after gated silence;
- Stage 2 resumed;
- Stage 3 became ready after embedding-ring fill;
- high-confidence or verified activation;
- wake callback invoked;
- assistant/voice session started;
- acknowledgement cue requested;
- cue playback started/completed/error, where the playback layer exposes it;
- STT capture started;
- STT result/timeout/error category without publishing private transcript content by default;
- session completed/cancelled;
- detector re-armed with a new generation identifier;
- service loss or detector error.

These events must be:

- debug-gated;
- low-volume;
- structured for reliable parsing;
- free of transcript, account, path, selector and endpoint data;
- based on monotonic time as well as ordinary log timestamp where practical.

The host cannot place a trial ID into acoustic audio. Correlation therefore relies on one stimulus at a time, an isolated observation window, source monotonic timing and target clock-offset measurement.

## 12. Trial model

### 12.1 Timing classes

**Silence-transition tests** exercise the RMS gate and inference readiness:

- 10 seconds;
- 30 seconds;
- 2 minutes.

**Extended-idle tests** exercise service survival, Doze, audio routing and re-arm:

- 15 minutes;
- 30 minutes;
- optional longer unattended interval only when needed.

Do not require a 30-minute delay to debug every gate transition.

### 12.2 Initial matrix

| Target | Idle period | Trials | Primary purpose |
|---|---:|---:|---|
| S21 | 10 s | 5 | rapid repeated gate transitions |
| S21 | 30 s | 5 | sustained silence transition |
| S21 | 2 min | 5 | longer quiet / re-arm |
| S21 | 15 min | 2 | Android idle and service reliability |
| S21 | 30 min | 2 | reproduce reported post-idle condition |
| S23U | 2 min | 3 | device comparison |
| S23U | 30 min | 2 | extended-idle comparison |

Run the smallest failing subset during harness development. The complete matrix is an execution issue after the source and diagnostics are reviewed.

### 12.3 Trial JSON

Suggested sanitised schema:

```json
{
  "schema_version": 1,
  "run_id": "opaque-run-id",
  "trial_id": "opaque-trial-id",
  "source_alias": "s23u",
  "target_alias": "s21",
  "fixture": {
    "fixture_id": "natural-normal-01",
    "kind": "natural_wav",
    "sha256": "...",
    "duration_ms": 1210
  },
  "idle_seconds": 1800,
  "source": {
    "status": "completed",
    "requested_host_time": "...",
    "started_elapsed_ms": 0,
    "completed_elapsed_ms": 0,
    "volume_before": 0,
    "volume_applied": 0,
    "volume_max": 0,
    "volume_restored": true,
    "route": "built_in_speaker"
  },
  "target": {
    "pre_service_state": "active",
    "post_service_state": "active",
    "screen_remained_off": true,
    "reboot_detected": false,
    "events": [],
    "diagnostic_delta": {}
  },
  "classification": "pass",
  "validity": "valid",
  "warnings": []
}
```

Raw records may include extra detail, but public records must use aliases and sanitised values.

## 13. Classification rules

Evaluate validity before product classification.

| Evidence | Classification |
|---|---|
| Source did not start, complete or restore state | `source_stimulus_failure` |
| Target service stopped, rebooted, screen interaction occurred or evidence window is untrustworthy | `invalid_or_inconclusive` |
| Source succeeded but target saw no voiced transition or usable audio-frame evidence | `acoustic_or_gate_miss` |
| Gate opened and Stage 2/3 became ready but classifier did not activate | `classifier_model_miss` |
| Activation occurred but wake callback or assistant session did not start | `activation_handoff_failure` |
| Session started but cue request/playback failed | `cue_audio_failure` |
| Cue/session succeeded but STT did not start or complete in an end-to-end trial | `stt_capture_failure` |
| Session ended but detector did not re-arm | `service_rearm_failure` |
| Required evidence is absent or ambiguous | `inconclusive` |
| Expected wake path completed and detector re-armed | `pass` |

A successful source event plus no target activation is not automatically a model miss. Gate transition and classifier-readiness evidence are required to separate acoustic/gating loss from classifier loss.

Actual cue audibility is harder than cue-request evidence. Version 1 records target cue events, route and stream state. When the original inaudible-cue symptom remains after that evidence is healthy, create a focused observer-audio capture slice rather than adding source microphone recording to the first player implementation.

## 14. Host runner responsibilities

A later Python runner should follow existing repository patterns in `scripts/` and the privacy/abort principles used by the battery harness.

It must:

- map private ADB selectors to `s21` and `s23u` aliases;
- verify source and target build identities;
- verify source wake disabled and target wake enabled;
- verify helper version and fixture hashes;
- save and restore diagnostic-tag values transactionally;
- run one operator setup/calibration checkpoint;
- preserve target idle without polling;
- schedule source playback;
- capture source result and target post-window evidence;
- abort or mark individual trials invalid on state failures;
- preserve private raw evidence;
- emit sanitised JSON and Markdown;
- provide smoke and dry-run modes;
- never post GitHub output automatically;
- restore source volume and target diagnostic properties even after cancellation.

Suggested private layout:

```text
scripts/private-acoustic-runs/<run-id>/
  manifest-private.json
  fixtures-private.json
  trials/<trial-id>/
    source/
    target/
  sanitized/run-summary.json
  sanitized/run-summary.md
```

## 15. Failure handling and cleanup

### Abort the run or trial when

- a device identity is wrong or ambiguous;
- source and target resolve to the same device;
- source wake listening is active;
- target wake listening or service is inactive at required boundaries;
- source playback or volume restoration fails;
- target reboot or app crash occurs;
- target screen turns on or material interaction occurs during a strict idle trial;
- source or target ADB is unavailable at an evidence boundary;
- fixture hash differs;
- output sanitisation fails;
- diagnostic cleanup fails.

### Cleanup order

1. stop any active source player;
2. restore exact source media volume;
3. abandon source audio focus;
4. restore target and source diagnostic properties;
5. preserve already collected private evidence without broad re-querying;
6. write a sanitised aborted report;
7. exit nonzero.

No cleanup path may uninstall Jandal, clear app data, delete model downloads or alter wake thresholds.

## 16. Release isolation gate

The debug source mechanism is accepted only when automated checks prove:

- the debug manifest contains the explicit receiver;
- the release merged manifest does not contain the receiver, action or class;
- release APK/AAB contents do not contain the debug class, resources or private fixtures;
- `com.kernel.ai` release cannot receive the test action;
- `com.kernel.ai.debug` is the only Jandal package that can expose it;
- the helper accepts only app-private allowlisted fixture IDs;
- no raw fixture is packaged in any variant.

The application already uses a separate debug application ID and Android source sets provide a build-type-specific manifest and code location. The implementation PR must add an explicit release-exclusion regression test or package audit.

## 17. Relationship to silence replay optimisation

### #1398

The acoustic harness validates the real-world requirements of the planned PCM pre-roll design:

- the beginning of the phrase is not lost after silence;
- no transition frames are dropped;
- activation remains reliable after extended silence;
- latency and re-arm remain acceptable.

The harness does not implement PCM replay and must not close #1398.

When #1402 produces a reproducible silence-transition/readiness defect, the relevant portion of #1398 may become launch-critical or a narrower fix may be created.

### #1399

The same fixture and runner structure can validate RMS threshold, hangover, hysteresis and noise-floor candidates. Gate tuning must use deterministic fixtures and compare recall/false-positive behaviour, not battery alone.

### Direct PCM injection

A future test seam should separate audio-frame acquisition from wake inference and feed privacy-safe PCM fixtures directly. That enables fast, deterministic model comparisons and ring/replay tests. It remains a separate optional child under #1398/#1399 because the current #1402 path must still exercise real microphone, routing and service behaviour.

## 18. Physical feasibility gate still required

Desk research can select the architecture, but it cannot prove that an S23U speaker at a practical calibrated level reliably activates the S21 microphone and current model.

Before closing #1403, run a short operator-assisted validation:

1. install a minimal debug source prototype or use an already available local playback mechanism;
2. use one private normal natural WAV and one TTS-generated WAV;
3. position S23U as source and S21 as target;
4. run three recently-armed, three 30-second and three 2-minute natural trials;
5. run the same reduced matrix with TTS only to classify its suitability;
6. record source completion, target activation and setup friction;
7. do not claim the final #1402 reliability result from this small matrix.

This is one short setup session. It does not require the operator to wait beside the phones for the future extended-idle matrix.

## 19. Proposed implementation slices

Create these only after this design and physical feasibility result are reviewed.

### Slice A — controlled acoustic source

**Proposed title:** Build debug-only controlled acoustic stimulus source for wake-word testing

Scope:

- debug receiver and manifest;
- app-private fixture loading and validation;
- `MediaPlayer` lifecycle;
- source result JSON and structured events;
- volume/focus restoration;
- concurrency and timeout handling;
- release-exclusion tests;
- source-side unit/instrumented tests.

Estimated size: M.

### Slice B — target attempt observability

**Proposed title:** Add structured debug events for post-silence wake, cue, handoff and re-arm

Scope:

- audit existing evidence first;
- add only missing low-volume events;
- preserve production hot-loop behaviour;
- sanitised parser fixtures and tests;
- no threshold, provider or model changes.

Estimated size: M.

### Slice C — unattended paired runner

**Proposed title:** Build unattended paired acoustic wake-word reliability runner

Scope:

- host scheduling and state validation;
- calibration workflow;
- target-idle preservation;
- trial correlation and classification;
- private/sanitised reports;
- dry-run, smoke and cancellation behaviour;
- no automatic GitHub publication.

Estimated size: L.

### Slice D — #1402 execution

**Proposed title:** Execute unattended S21 post-idle wake reliability matrix

Scope:

- approved S21 matrix;
- smaller S23U comparison;
- classification of every failure;
- determine whether #1398, cue/handoff remediation or no launch fix is required;
- update and resolve #1402.

Estimated size: S–M after the harness exists.

### Optional post-launch slice — direct PCM fixtures

**Proposed title:** Add injectable PCM fixture source for wake-word silence and replay regression tests

Parent: #1398/#1399, not required for the first acoustic harness.

## 20. Review decisions required

Before implementation, review and confirm:

1. `app/src/debug` helper versus a separate test APK. This design recommends `src/debug` for lowest setup burden and strongest direct release exclusion.
2. natural voice fixtures remain private and are not committed;
3. the initial primary oracle is wake-only natural WAV;
4. TTS is supplementary and synthesized once to file;
5. actual cue audibility capture is deferred unless target cue/route evidence cannot classify the symptom;
6. physical feasibility passes with practical source volume and placement;
7. the child-issue boundaries remain appropriate after the prototype.

## 21. Sources

- [Android MediaPlayer API](https://developer.android.com/reference/android/media/MediaPlayer)
- [Android TextToSpeech API](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [Android build variants and source sets](https://developer.android.com/build/build-variants)
- [Media3 ExoPlayer overview](https://developer.android.com/media/media3/exoplayer)
- [Termux:API](https://github.com/termux/termux-api)
- [Termux API client scripts](https://github.com/termux/termux-api-package)
- [VLC for Android](https://github.com/videolan/vlc-android)
- [mpv-android](https://github.com/mpv-android/mpv-android)
