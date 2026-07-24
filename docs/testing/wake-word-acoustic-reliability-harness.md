# Unattended acoustic wake-word reliability harness

**Issue:** #1403  
**Parent investigation:** #1402  
**Related optimisation work:** #1395, #1398, #1399  
**Status:** Runner, provider-journal integration, deterministic host tests and CI wiring are implemented; the bounded physical source-to-target feasibility session remains outstanding.

## 1. Purpose

This document defines the durable architecture for unattended, repeatable acoustic wake-word reliability testing across the S21 and S23 Ultra.

The harness must exercise the real target-device path:

```text
source speaker
  -> room acoustics
  -> target microphone / AudioRecord
  -> silence gate
  -> ONNX wake pipeline
  -> wake activation
  -> assistant session
  -> acknowledgement cue
  -> STT handoff or timeout
  -> detector re-arm
```

Direct PCM injection is complementary deterministic coverage for #1398/#1399. It is not a substitute for the physical acoustic path required by #1402.

## 2. Approved decisions

1. **Fixture sources:** one fresh private natural recording of only “Hey Jandal” is the primary #1402 oracle. Qwen is the only initial synthetic TTS source. Natural and voice-cloned material stays private and gitignored. Approved non-personal Qwen fixtures may be committed with provenance and hashes.
2. **Source component:** implement a Jandal-specific helper in `app/src/debug`; do not create a separate test APK or generic external player.
3. **Fixture duration:** version 1 accepts individual fixtures up to five seconds. Wake and command files remain separate where practical. A short combined file is smoke-only. Longer files fail explicitly.
4. **Audibility preflight:** use one human-monitored, automation-assisted checkpoint. Begin at a predefined safe volume, allow only bounded operator adjustments, verify delivery and route, then freeze the setup. Wake recognition is evidence, not a prerequisite for approving the source level.
5. **Placement and Bluetooth:** use the normal side-by-side desk placement, with phones not touching and generally orienting the source speaker toward the target microphone. Bluetooth may remain enabled, but no active external Bluetooth audio route may affect source playback, target capture or acknowledgement audio.
6. **Wake and command trials:** wake-only natural audio is the primary matrix. A smaller subset uses a separate Qwen “What time is it?” file.
7. **Command synchronisation:** the durable runner waits for the target’s per-trial STT-ready / `ListeningStarted` event, then applies a small cue-clearance margin before playing the command. Device-specific fixed delays are prototype fallbacks only.
8. **Cue audit:** audit every STT entry point, the central cue mechanism and every harness audio-state mutation. `cue_requested` is not acoustic proof. Final audio attributes, stream policy and effective level require S21/S23U evidence and belong in a separate production remediation issue.
9. **Evidence and dashboard:** integrate with the existing normalised evidence system. Add explicit valid pass, valid failure and invalid-attempt semantics, plus wake-specific dashboard views.
10. **Frozen matrix:** use the valid-trial counts in §15. Invalid attempts stay visible and may be repeated only to complete the required valid count. Never retry a valid failure away.

## 3. Design principles

- One deliberate operator checkpoint, not one interaction per trial.
- S21 is the launch-blocking primary target; S23U is normally the source and a smaller comparison target.
- No threshold, model, provider, silence-gate or production service changes may be made to make the harness pass.
- The target must not be polled or have logs streamed during an idle interval.
- Source playback completion and exact state restoration are mandatory evidence.
- Missing evidence is unknown, not a negative measurement.
- Raw speech, selectors, endpoints and detailed device artifacts stay private.
- Release exclusion is tested, not assumed.
- Wake detection, cue audibility, STT readiness, command recognition and re-arm remain separately classifiable.
- Diagnostic pre-fix runs and post-fix regression gates must be visibly distinct.

## 4. Selected source approach

Use platform `MediaPlayer` in a debug-only Jandal component to replay immutable local PCM WAV fixtures.

### Why selected

- exact waveform repeatability;
- natural and synthetic fixtures use the same path;
- prepared, started, completed and error callbacks;
- no external player or automation dependency;
- app-private file-descriptor access;
- small implementation surface;
- direct volume, route, focus and cleanup ownership;
- strong debug/release separation.

### Rejected as permanent dependencies

- **Live Android TTS:** device-engine variability and weak natural-speech validity. Synthesis-to-file remains supplementary.
- **System or installed media player:** handler, UI, queue, lock-screen, focus and completion behaviour are not owned by the harness.
- **Voice Recorder playback:** suitable for fixture creation only.
- **Termux / Termux:API:** acceptable for a disposable topology proof when already installed, but excessive installation and maintenance burden as a required dependency.
- **VLC or mpv:** full external media applications without a sufficiently narrow per-trial contract.
- **Media3/ExoPlayer:** unnecessary for one short local WAV unless platform playback later proves insufficient.

## 5. Test topology

```text
Host-side Python runner
  |
  +-- ADB -> source device (normally S23U)
  |           - Jandal debug build
  |           - Hey Jandal disabled
  |           - wake service inactive
  |           - debug acoustic source component
  |           - app-private fixtures
  |
  +-- ADB -> target device (normally S21)
              - same reviewed Jandal debug commit
              - Hey Jandal enabled
              - screen locked/off during idle
              - structured debug event journal
              - focused post-trial evidence
```

The runner must support swapping aliases for the S23U comparison matrix.

### Source self-activation prevention

Before every run, verify that the source:

- has Hey Jandal disabled;
- has no active wake service or assistant voice session;
- uses the built-in speaker;
- has no active external Bluetooth audio route;
- is not the same physical device as the target.

Any violation invalidates the run.

## 6. Debug-only source component

### 6.1 Placement

```text
app/src/debug/AndroidManifest.xml
app/src/debug/java/com/kernel/ai/debug/acoustic/AcousticStimulusReceiver.kt
```

Use the existing debug application ID. Do not introduce a standalone APK.

### 6.2 Invocation

The host invokes an explicit debug receiver with:

- `trial_id`: opaque host-generated identifier;
- `fixture_id`: allowlisted ID, never an arbitrary path;
- `volume_index`: bounded against the source media-stream maximum;
- optional bounded player gain, normally `1.0`.

Reject malformed IDs, unknown fixtures, traversal, arbitrary paths, missing/empty/unsupported audio, files over five seconds, unsafe volume values and overlapping playback.

### 6.3 Lifecycle

1. Use `goAsync()` for the bounded short playback.
2. Capture source wall-clock and monotonic timestamps.
3. Resolve an allowlisted app-private fixture.
4. Validate format, duration and SHA-256.
5. Snapshot media volume, route, ringer/DND-relevant state and audio focus context.
6. Apply the approved source volume.
7. Request transient focus with explicit playback attributes.
8. Open the WAV by file descriptor.
9. Prepare and start `MediaPlayer`.
10. Emit structured prepared and started events.
11. On completion, error or timeout, release the player and abandon focus.
12. Restore the exact original audio state.
13. Verify restoration.
14. Write a private machine-readable result and final event.
15. Finish the pending broadcast result.

The host runner invokes the target journal through the debug `ContentProvider` at
`content://com.kernel.ai.debug.target-event-journal`, never through a target
broadcast receiver. It uses `GET_JOURNAL_SEQUENCE` for the idle boundary,
`WAIT_FOR_JOURNAL_EVENT` with a unique request ID for one bounded wait, and
`GET_JOURNAL_SNAPSHOT` for the final exact envelope. Provider cancellation uses
`CANCEL_JOURNAL_WAIT` with the active request ID.

The provider call returns Android's `Bundle[{result_code=..., result_data=...}]`
envelope. The host parses the typed `content call --extra key:{s|l}:value`
bindings, requires the exact journal snapshot envelope, rejects malformed or
overflowed boundaries, and joins or cancels every wait worker before cleanup.

The source result is accepted only when its trial/fixture IDs, hash, timing,
volume, built-in-speaker route, granted focus, completion, cleanup and exact
restoration fields all validate. Private raw result files remain on the source
device and are never copied into public evidence.

Longer fixtures must return an explicit result such as `fixture_duration_not_supported`. Future long-form or multi-round testing should use a more durable debug-only component rather than extending this receiver contract.

A debug-only foreground service is a fallback only if device evidence proves the receiver lifecycle unreliable.

### 6.4 Source evidence

Record:

- trial and fixture IDs;
- fixture SHA-256 and duration;
- request, prepare, start, completion and cleanup monotonic times;
- volume before, requested, applied, maximum and restored;
- output route before and during playback;
- focus result;
- completion, timeout or error category;
- overlap rejection;
- cleanup and exact-restoration status.

## 7. Release-isolation gate

Automated tests must prove:

- the debug manifest contains the explicit source component;
- the release merged manifest contains no receiver, action or helper class;
- release APK and AAB contents contain no helper code or fixtures;
- only the debug package accepts the explicit action;
- the helper accepts only app-private allowlisted fixture IDs;
- private fixtures are never packaged in any variant;
- release behaviour and production wake configuration are unchanged.

Release-isolation failure blocks the source implementation.

## 8. Fixture strategy

### 8.1 Initial set

Primary private fixtures:

1. one fresh natural “Hey Jandal” recording;
2. a digitally attenuated derivative of that recording.

Initial Qwen fixtures:

3. a small set of “Hey Jandal” variants;
4. “What time is it?”;
5. optional short combined wake-plus-command smoke file;
6. silence control.

The primary #1402 pass rate uses the same fresh natural wake fixture throughout. Additional variants diagnose sensitivity and are reported separately.

Do not reuse the user’s wake-model training corpus as the primary reliability oracle. Keep Qwen voice/settings distinct from voice-cloned training material where practical. Defer Kokoro and other synthetic sources unless Qwen presents a concrete limitation or blind spot.

### 8.2 Format

- RIFF/WAVE;
- linear PCM;
- signed 16-bit little-endian;
- mono;
- 48 kHz;
- maximum five seconds;
- no embedded personal metadata;
- documented peak level, initially `-3 dBFS`;
- attenuated derivatives recorded in manifest metadata.

### 8.3 Privacy and provenance

Private layout:

```text
scripts/private-acoustic-fixtures/<fixture-set-id>/
  manifest-private.json
  natural/
  synthetic-private/
```

Private natural or voice-cloned audio, speaker identity, source paths and recorder metadata must remain gitignored.

Approved non-personal Qwen files may be committed only when their manifest records:

- fixture ID;
- type and intended role;
- model/voice/settings provenance;
- duration and sample format;
- SHA-256;
- approval status.

## 9. Audibility preflight and physical setup

Use the operator’s normal adjacent-on-desk setup:

- devices side-by-side but not touching or stacked;
- source speaker generally oriented toward the target microphone;
- no obstructed speaker or microphone;
- quiet enough to hear clipping, muffling or routing mistakes;
- Bluetooth may remain enabled;
- source must use its built-in speaker;
- neither device may have an active external Bluetooth audio route affecting the test;
- placement remains fixed after approval.

Record an approximate gap and placement notes. A mandatory 30 cm distance is not required.

### Preflight

1. Verify source and target identities, builds and roles.
2. Verify source wake disabled and target wake enabled.
3. Verify fixture hashes.
4. Snapshot all relevant source and target audio state.
5. Start from a conservative predefined source media-volume index, provisionally near 60% of maximum.
6. Play the primary wake fixture in a human-monitored checkpoint.
7. Verify source completion, route, exact restoration and target audio/gate evidence.
8. Permit only bounded predefined operator adjustments, for example a lower level or approximately 75%.
9. Record the approved volume index/max, route, placement, fixture hash and operator approval.
10. Freeze the setup for the unattended matrix.

The runner writes a canonical private preflight manifest containing the approved
fixture hashes, source volume/route, placement notes, target boot identity and
cue-policy version. Later diagnostic, feasibility and regression runs must
consume and hash-verify this manifest; they do not recreate approval implicitly.

Preflight schema version 5 also freezes exact source and target environment
snapshots: Android user, microphone/camera privacy state, package UID and UID
state, standby bucket, wake-service state, media volume/range, ringer/DND,
Bluetooth route, target screen/charging state, uptime and boot ID. Missing,
unparseable or changed fields invalidate a later run; they are never replaced
with permissive defaults. Cleanup recaptures the same fields and fails closed on
drift or capture errors.

Do not use open-ended volume search, binary search, repeated maximum-volume ramps or automatic level changes after a failure.

Recognition outcomes during preflight are evidence:

- audio evidence and 3/3 wake: strong control;
- audio evidence and 1/3 or 2/3 wake: valid setup with an immediate reliability concern;
- audio evidence and 0/3 wake: likely product/model/fixture concern, not automatic proof of insufficient volume;
- no target audio/gate evidence: setup, route or source-level concern.

Call this an **audibility preflight**, not laboratory calibration.

## 10. Preserving target idle

For each trial:

1. Validate target state and take a boundary snapshot.
2. Start the idle countdown.
3. Do not poll the target or continuously stream target logs during the idle interval.
4. Trigger wake playback on the source.
5. For wake-only trials, observe the bounded target completion/re-arm window.
6. For command trials, open one bounded event wait only after wake playback.
7. Collect the complete target event snapshot after the trial.

Material interaction, screen-on, reboot, service loss, charging-state violation or missing evidence marks the attempt invalid rather than failed.

The journal snapshot is the exact object envelope
`{lowestSequence, highestSequence, overflowed, events}`. Events use compact
fields `s`, `m`, `w`, `t`, `g`, `i` and `d`; sequence order, event vocabulary,
generation/session correlation and overflow boundaries are validated before
classification. A boundary is invalid when sticky overflow proves that
post-boundary events could have been evicted.

## 11. Target structured event journal

`WakeWordDiag` cumulative summaries are too coarse for individual trials. Add a debug-only bounded event journal using monotonic timestamps and sequence numbers.

Required events or equivalent evidence:

- detector generation started;
- silence gate entered;
- first voiced frame after silence;
- Stage 2 resumed;
- Stage 3 ready after embedding history fill;
- activation candidate and verified activation;
- wake callback;
- voice/assistant session started;
- STT start requested;
- STT ready / `ListeningStarted`;
- cue requested;
- cue playback started/completed/error where available;
- STT speech/partial/final/error without publishing transcript by default;
- command routing result for command trials;
- session completion/cancellation;
- detector re-armed with a new generation;
- service or detector error.

Events must be structured, low-volume, debug-gated, monotonic and free of private transcript, path, account, selector and endpoint data.

### Durable synchronisation

After source wake playback completes, the host opens one bounded wait for `STT_READY` / `ListeningStarted`. Once received, it waits the approved small cue-clearance margin and triggers the separate command fixture.

Filtered `logcat` or bounded polling is allowed only for the feasibility prototype. The permanent machine contract is event-driven, with the journal retained for evidence and late-subscriber recovery.

## 12. Wake-only and command coverage

### Wake-only

The main matrix plays only the fresh natural “Hey Jandal” fixture. A wake-only trial can still validate:

- acoustic/gate transition;
- wake classifier activation;
- callback and session start;
- STT readiness;
- cue request/playback evidence;
- timeout/session end;
- detector re-arm.

### Command subset

Selected trials then play a separate Qwen “What time is it?” fixture after the actual per-trial target readiness event and cue-clearance margin.

Record:

- activation to STT request;
- STT request to ready;
- ready to cue;
- ready to command playback;
- command playback to speech/partial/final transcript;
- command routing result.

A bounded device-specific fixed delay may be used only during the initial feasibility prototype and must be recorded separately for S21 and S23U. It is not the final architecture.

## 13. Start-listening cue audit dependency

The current wake and foreground STT flows may use different stream semantics. The reliability harness must not assume that `cue_requested` proves an audible cue.

Create a separate focused production issue to:

- inventory every STT entry point, including wake, Actions, Chat one-shot, back-and-forth, slot filling, retries, widgets/side-key and alarm/timer stop-command listening;
- verify every cue is tied to actual recogniser readiness;
- audit the playback mechanism, tone/asset, duration, stream or `AudioAttributes`, focus, route, DND/ringer interaction and effective volume;
- audit all harnesses for alarm/media/ring/DND/route mutation;
- require transactional snapshot, exact restoration and verification in `finally`;
- determine one central context-aware cue policy;
- test the final policy on S21 and S23U;
- define which monitored acoustic checks are required for #1402 evidence.

Do not silently raise user volume in production. Do not choose the final stream or level without device evidence.

For version 1 evidence:

- collect app-level cue events, selected policy, route and effective stream/volume on every trial;
- require human acoustic confirmation during audibility preflight and selected S21 post-idle trials;
- add automated source-phone microphone cue detection only if the symptom remains unclassifiable.

## 14. Evidence contract

Integrate with the existing normalised test-evidence pipeline and `test-results` dashboard.

Emit one record per **target** device. Represent the source/stimulus device in run context, not as the primary evidence device.

Suggested suite:

```text
wake_word_acoustic_reliability
```

### 14.1 Run context

Include:

- `run_kind`: `diagnostic_pre_fix` or `regression_post_fix`;
- `gate_mode`: `diagnostic` or `release_gate`;
- matrix ID/version;
- target and source aliases;
- target/source commits and helper version;
- fixture-set ID and hashes;
- audibility-preflight approval;
- placement notes;
- approved source volume index/max and route;
- cue-policy version;
- expected valid-trial counts;
- monitored acoustic-check completion.

### 14.2 Attempt status

Extend the evidence schema and consumers to support:

- `passed`: valid product success;
- `failed`: valid product failure;
- `invalid`: setup, source, environment or evidence failure.

Invalid attempts remain visible with an `invalid_reason`, but are excluded from product reliability pass-rate calculation. They may be repeated only to complete the required valid matrix count.

Suggested summary:

```json
{
  "total_attempts": 22,
  "valid": 20,
  "passed": 18,
  "failed": 2,
  "invalid": 2,
  "pass_rate": 0.9
}
```

### 14.3 Per-attempt case

Each attempted trial records:

- trial ID;
- idle interval;
- `wake_only` or `wake_plus_command`;
- fixture ID/hash;
- source completion and restoration;
- target pre/post state;
- status, failure classification or invalid reason;
- monotonic event offsets and derived latencies;
- audio/gate, activation, callback, STT, cue, command and re-arm evidence;
- sanitised artifact references;
- warnings and operator annotations where required.

Use offsets from the start of the trial rather than assuming host and Android wall clocks are directly comparable.

### 14.4 Completeness gate

A run is not publishable evidence unless:

- every expected matrix position has the required number of valid trials;
- every attempt is passed, failed or explicitly invalid;
- every invalid attempt has a reason;
- every valid failure is classified or explicitly `unclassified`;
- commit, helper version and fixture hashes are present;
- preflight, route and volume evidence are present;
- summaries reconcile with cases;
- cleanup and exact restoration are verified;
- public output passes privacy validation.

The host scheduler assigns every frozen count an independent position ID
`<idle-seconds>:<trial-type>:<ordinal>`. A valid failure completes its position
and remains in the release evidence; only invalid attempts may be retried.
Matrix completeness therefore differs from release-gate success: completeness
requires one valid outcome per required position, while the release gate also
requires every required S21 position to pass, preflight evidence to be present,
and cleanup/restoration verification to succeed.

Diagnostic and bounded smoke modes may complete with valid product failures and
must report those failures without masquerading as a release-gate result. Only
the S21 `regression` mode can return release-gate success, and it additionally
requires verified commit/build provenance, the approved schema-5 preflight,
cue-policy and audibility evidence, exact cleanup, and all required S21 slots
passed. Fixed-delay feasibility output is explicitly non-gating.

## 15. Frozen valid-trial matrix

Counts below are required **valid** trials. Invalid attempts remain in evidence and may be repeated to complete the count.

### S21 primary launch gate

| Idle interval | Wake-only | Wake + command |
|---|---:|---:|
| Recently armed / 10 seconds | 5 | 3 |
| 30 seconds | 5 | 0 |
| 2 minutes | 5 | 3 |
| 15 minutes | 2 | 0 |
| 30 minutes | 2 | 2 |

### S23U comparison

| Idle interval | Wake-only | Wake + command |
|---|---:|---:|
| 2 minutes | 3 | 2 |
| 30 minutes | 2 | 1 |

The S21 matrix is launch blocking. S23U is comparison evidence unless it reveals the same product defect.

## 16. Failure classification

Evaluate validity first.

| Evidence | Classification |
|---|---|
| Source playback, route, hash or restoration failure | invalid: `source_stimulus_failure` |
| Target screen/state/service/reboot/ADB/evidence boundary invalid | invalid: `device_environment_error` |
| Source succeeded but target saw no credible audio/voiced transition | `acoustic_or_gate_miss` |
| Gate opened and inference became ready but no activation | `classifier_model_miss` |
| Activation occurred but callback/session did not start | `activation_handoff_failure` |
| Session started but STT never became ready | `stt_readiness_failure` |
| Cue request/playback policy failed | `cue_audio_failure` |
| Cue evidence healthy but audibility not established | `cue_audibility_unconfirmed` |
| Command audio played but speech/transcript/routing failed | `command_capture_or_routing_failure` |
| Session ended but detector did not re-arm | `service_rearm_failure` |
| Required evidence remains ambiguous | `unclassified` |
| Expected path completed and re-armed | `pass` |

A valid failure is never repeated until it becomes a pass. A rerun is allowed only for a separately recorded invalid attempt.

## 17. Dashboard presentation

Extend the existing dashboard rather than creating a standalone report.

### Run summary

Show:

- Diagnostic or Regression Gate badge;
- target and source device;
- commit and matrix version;
- valid passed/failed and invalid attempts;
- audibility-preflight status;
- monitored cue-audibility status;
- overall classification.

A successful pre-fix reproduction must not look like a failed release gate.

### Matrix view

Group by idle interval and trial type, showing valid pass/required count, failures and invalid attempts.

### Failure view

Show counts for:

- source/setup invalid;
- acoustic/gate miss;
- classifier miss;
- activation/handoff;
- STT readiness;
- cue policy or audibility;
- command recognition/routing;
- re-arm;
- unclassified.

### Timing view

For each device, show median and min/max for:

- activation to STT request;
- STT request to ready;
- ready to cue;
- ready to command playback;
- command playback to speech/transcript.

Small trial counts do not justify p95 claims.

### Trial drill-down

Expose the sanitised event sequence and artifact links for each attempt. Reuse existing JSON, screenshot, video and log artifact-link conventions.

## 18. Post-fix acceptance for #1402

A post-remediation S21 confirmation run requires:

- the entire frozen S21 matrix completed with valid evidence;
- zero valid S21 failures;
- zero unclassified outcomes;
- every command trial recognises and routes “What time is it?”;
- cue behaviour meets the agreed app-level and monitored acoustic evidence standard;
- STT readiness and detector re-arm verified;
- fixed placement, volume and route throughout the matrix;
- one complete independent confirmation run after the fix, not piecemeal retries.

This is targeted regression evidence, not proof of universally perfect wake-word reliability.

## 19. Host runner responsibilities

The runner must:

- map private selectors to public aliases;
- verify device identities, builds and roles;
- verify source wake disabled and target wake enabled;
- verify helper version and fixture hashes;
- transactionally manage diagnostic settings and all mutated audio state;
- run one audibility-preflight checkpoint;
- freeze placement, volume and route;
- preserve target idle;
- schedule source playback;
- perform bounded event-driven command synchronisation;
- classify each valid failure or invalid attempt;
- preserve private raw evidence;
- emit normalised sanitised evidence and Markdown;
- support dry-run, smoke and cancellation;
- never post to GitHub automatically;
- restore exact state after success, failure, cancellation or ADB loss;
- fail evidence publication when cleanup verification fails.

Private layout:

```text
scripts/private-acoustic-runs/<run-id>/
  manifest-private.json
  fixtures-private.json
  trials/<trial-id>/
    source/
    target/
  sanitized/
    evidence-<target>.json
    run-summary.md
```

## 20. Physical feasibility gate

Desk research cannot prove the acoustic topology. Before closing #1403, run one short human-assisted session:

1. use the minimal debug source prototype or another temporary local playback path;
2. use the fresh private natural wake file, its attenuated derivative and the initial Qwen files;
3. use S23U as source and S21 as target in the approved desk placement;
4. complete the audibility preflight;
5. run three recently armed, three 30-second and three 2-minute natural wake trials;
6. run a smaller Qwen wake sample to classify synthetic suitability;
7. run at least one command handoff using a temporary measured per-device delay if structured readiness is not yet available;
8. record source completion, target evidence, readiness delay and setup friction;
9. do not use this small session as the final #1402 regression result.

Feasibility may refine implementation details, but it must not reopen approved decisions without concrete device evidence.

## 21. Implementation slices and dependencies

### Slice A — audit and standardise the start-listening cue

**Title:** Audit and standardise the start-listening cue across all STT entry points

Scope:

- full production cue and STT-entry inventory;
- harness audio-state mutation audit;
- central context-aware cue policy;
- S21/S23U device evidence;
- exact state restoration requirements;
- monitored audibility standard for #1402.

Dependency: starts immediately; its conclusions are required before final #1402 cue classification.

### Slice B — controlled acoustic source

**Title:** Build debug-only controlled acoustic stimulus source for wake-word testing

Scope:

- debug receiver and manifest;
- app-private allowlisted fixtures;
- `MediaPlayer` lifecycle;
- source result and structured events;
- volume, route and focus restoration;
- concurrency, timeout and format validation;
- release-exclusion tests.

Dependency: approved design; physical feasibility can occur during this slice.

### Slice C — target event journal and observability

**Title:** Add structured target event journal for wake, STT, cue, handoff and re-arm

Scope:

- audit existing diagnostics;
- add only missing debug-gated events;
- bounded event-wait interface and journal snapshot;
- parser fixtures and tests;
- no production thresholds, models or hot-loop logging changes.

Dependency: approved design. Can proceed in parallel with Slice B after event vocabulary review.

Implemented Slice C contract details, including the exact snapshot envelope,
bounded concurrent wait/cancellation actions, extras, result codes, validation
errors, and timeout bounds, are maintained in
[`acoustic-stimulus-source.md`](acoustic-stimulus-source.md#target-structured-event-journal).

### Slice D — evidence schema and dashboard

**Title:** Add acoustic wake reliability evidence and dashboard support

Scope:

- normalised `passed` / `failed` / `invalid` attempt semantics;
- wake-specific run and case fields;
- completeness validation;
- summary and metrics updates;
- matrix, timing, failure and drill-down dashboard views;
- backward compatibility for existing evidence.

Dependency: approved evidence contract; coordinate with Slice E runner output.

### Slice E — unattended paired runner

**Title:** Build unattended paired acoustic wake-word reliability runner

Scope:

- role/state validation;
- audibility preflight;
- target-idle preservation;
- source orchestration;
- event-driven command playback;
- frozen matrix scheduling;
- classification, cleanup and evidence generation;
- dry-run, smoke and cancellation tests.

Dependencies: B, C and the evidence contract from D; cue policy integration from A.

### Slice F — execute and resolve #1402

**Title:** Execute the S21 post-idle acoustic wake reliability matrix

Scope:

- pre-fix reproduction and classification;
- focused remediation identified by evidence;
- full clean post-fix S21 gate;
- smaller S23U comparison;
- dashboard evidence publication;
- update and resolve #1402;
- feed relevant findings into #1398/#1399.

Dependencies: A–E complete and reviewed.

### Optional post-launch slice — direct PCM fixtures

Parent under #1398/#1399, not required for the first acoustic harness.

## 22. Consistency review

The approved design has these deliberate separations:

- The source helper delivers audio; it does not schedule the matrix or classify the target.
- The event journal synchronises and explains target behaviour; it does not alter wake logic.
- The cue audit changes production audio policy only when device evidence supports it.
- The evidence/dashboard slice changes reporting semantics; it does not hide invalid attempts or failures.
- The runner never polls during target idle and never retries valid failures away.
- The physical feasibility session validates topology only; the frozen matrix remains the #1402 regression gate.
- Acoustic testing remains distinct from battery measurement and direct PCM injection.

No unresolved architectural decision remains. Physical feasibility can refine bounded implementation details such as the safe starting source-volume index and cue-clearance margin.

## 23. Sources

- [Android MediaPlayer API](https://developer.android.com/reference/android/media/MediaPlayer)
- [Android AudioAttributes API](https://developer.android.com/reference/android/media/AudioAttributes)
- [Android AudioDeviceInfo API](https://developer.android.com/reference/android/media/AudioDeviceInfo)
- [Android TextToSpeech API](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [Android build variants and source sets](https://developer.android.com/build/build-variants)
- [Media3 ExoPlayer overview](https://developer.android.com/media/media3/exoplayer)
- [Termux:API](https://github.com/termux/termux-api)
- [VLC for Android](https://github.com/videolan/vlc-android)
- [mpv-android](https://github.com/mpv-android)

## 24. Start-listening cue policy (PR #1405 / #1416)

### 24.1 Overview

Issue #1405 standardises the start-listening audio cue across wake-word and clock-alert
voice entry points. Only these two paths intentionally play the cue. All other voice
initiation flows obtain readiness through their own UI or signal and do not use this cue.

### 24.2 STT entry-point inventory

| Entry path | Production location | Capture mode | Readiness trigger | Cue policy | Cue context | Playback mechanism | Stream/attributes | Duplicate/missing-cue risk | Test coverage | Device evidence |
| ---------- | ------------------- | ------------ | ----------------- | ---------- | ----------- | ------------------ | ----------------- | --------------------------- | ------------- | --------------- |
| Hey Jandal wake handoff | `WakeWordService.handleDetection()` → `runWakeAttempt()` | `AlertCommand` | `ListeningStarted` | Cue per ready attempt | `WAKE_WORD` | `StartListeningCuePlayer.playCue()` | `STREAM_ALARM` | None — gated by `reachedReadiness` | `WakeWordCueTest` | S21/S23U physical — PASS |
| Automatic wake/STT retry (attempt 2) | `WakeWordService.handleDetection()` → `runWakeCaptureSession()` | `AlertCommand` | `ListeningStarted` | Cue per ready attempt | `WAKE_WORD` | `StartListeningCuePlayer.playCue()` | `STREAM_ALARM` | None — separate journal, new collector | `WakeWordCueTest` | NOT RUN (evidence pending) |
| Alarm/timer voice command | `ClockAlertService.handleVoiceEvent()` | `AlertCommand` | `ListeningStarted` | Cue for owned AlertCommand readiness | `CLOCK_ALERT` | `StartListeningCuePlayer.playCue()` | `STREAM_ALARM` | None — ownership + mode filter | `ClockAlertSessionTest` | S21/S23U physical — PASS |
| Actions command capture | `ActionsViewModel` | `Command` | `ListeningStarted` | FOREGROUND cue per owned readiness | `FOREGROUND` | `StartListeningCuePlayer.playCue()` | `STREAM_MUSIC` | None — ownership + mode filter | Manual | S21/S23U physical — PASS |
| Chat one-shot voice | `ChatViewModel` | `Command` | `ListeningStarted` | FOREGROUND cue per owned readiness | `FOREGROUND` | `StartListeningCuePlayer.playCue()` | `STREAM_MUSIC` | None — ownership + mode filter | `ChatViewModelVoiceTest` | S21/S23U physical — PASS |
| Chat back-and-forth re-listening | `ChatViewModel` | `Command` | `ListeningStarted` | FOREGROUND cue per owned readiness | `FOREGROUND` | `StartListeningCuePlayer.playCue()` | `STREAM_MUSIC` | None — ownership + mode filter | `ChatViewModelVoiceTest` | S23U physical — PASS |
| Chat slot-fill reply | `ChatViewModel` | `Command` | `ListeningStarted` (mic tap) | FOREGROUND cue per owned readiness | `FOREGROUND` | `StartListeningCuePlayer.playCue()` | `STREAM_MUSIC` | None — user re-taps mic | Manual | S21/S23U physical — PASS |
| Widget voice entry | `VoiceCommandActivity` | `Command` | `ListeningStarted` | FOREGROUND cue per owned readiness | `FOREGROUND` | `StartListeningCuePlayer.playCue()` | `STREAM_MUSIC` | None — ownership + mode filter | Manual | S21 physical — PASS |
| Side-key / ASSIST intent | `VoiceCommandActivity` | `Command` | `ListeningStarted` | FOREGROUND cue per owned readiness | `FOREGROUND` | `StartListeningCuePlayer.playCue()` | `STREAM_MUSIC` | None — ownership + mode filter | Manual | Mapped to VoiceCommandActivity — same path as widget |
| Permission-repair restart | ChatViewModel / ActionsViewModel | `Command` | `ListeningStarted` | FOREGROUND cue per re-started readiness | `FOREGROUND` | `StartListeningCuePlayer.playCue()` | `STREAM_MUSIC` | None — fresh session | Manual | S21 physical — PASS |

Every cue-enabled path follows readiness before playback. Three cue contexts exist:
| Context | Examples | Stream |
| ------- | -------- | ------ |
| `FOREGROUND` | Chat, Actions, widget/assistant, Command mode; Actions SlotReply readiness | `STREAM_MUSIC` |

The cue plays exactly once per owned readiness event. Foreign or unowned sessions never trigger a cue.

All three contexts use the same readiness-before-playback ordering. The only difference is the
audio stream and the cue context metadata recorded in the acoustic journal.

### 24.3 Cue policy

```kotlin
internal fun shouldPlayClockAlertListeningCue(
    event: VoiceInputEvent,
    captureSessionId: Long?,
    isVoiceListening: Boolean,
): Boolean =
    isOwnedAlertEvent(event, captureSessionId, isVoiceListening) &&
        event is VoiceInputEvent.ListeningStarted &&
        event.mode == VoiceCaptureMode.AlertCommand
```

Shared between `ClockAlertService.handleVoiceEvent()` and tests.

**Foreground cue rule:**
- **ChatViewModel**: cue plays for `VoiceCaptureMode.Command` only (Chat's capture mode).
- **ActionsViewModel**: cue plays for both `VoiceCaptureMode.Command` and
  `VoiceCaptureMode.SlotReply` (Actions starts SlotReply sessions).
- Cue plays only after `VoiceInputEvent.ListeningStarted` is received.
- Ownership required — ViewModel must have an active capture session.
- Pre-readiness terminal events (`Error`, `ListeningStopped`, `Transcript`) produce no cue.
- Maximum one cue per readiness event.
- A retry produces its own cue on the new attempt's readiness.

**Wake-word cue rule (in `runWakeAttempt`):**
- Cue plays only after `VoiceInputEvent.ListeningStarted` is received.
- A pre-readiness terminal event (`Error`, `ListeningStopped`, `Transcript`) produces no cue.
- Maximum one cue per attempt.
- A retry produces its own cue on the new attempt's readiness.

**Clock-alert cue rule (in `handleVoiceEvent`):**
- Cue plays only for owned `AlertCommand` readiness events.
- Foreign-session events never trigger a cue.
- Transcript, error, and stopped events never trigger a cue.

### 24.4 Android audio stream and attributes

Two audio streams are used depending on the cue context:

| Cue context | Stream | Usage | Content type |
| ----------- | ------ | ----- | ------------ |
| `FOREGROUND` | `STREAM_MUSIC` | `USAGE_MEDIA` | `CONTENT_TYPE_SPEECH` |
| `WAKE_WORD` | `STREAM_ALARM` | `USAGE_ALARM` | `CONTENT_TYPE_SONIFICATION` |
| `CLOCK_ALERT` | `STREAM_ALARM` | `USAGE_ALARM` | `CONTENT_TYPE_SONIFICATION` |

DND behaviour follows Android policy: `STREAM_ALARM` is typically exempt from DND suppression.
`STREAM_MUSIC` is subject to DND and volume settings. The implementation does not bypass,
query, or modify DND or per-stream volume.

Low or zero stream volume may make the cue inaudible. The app never silently raises volume.
Clock-alert cue audibility can be reduced by same-stream alert contention (both use `STREAM_ALARM`).

### 24.5 Readiness-before-cue ordering

Guaranteed by the buffered collector in `runWakeAttempt`:

1. `launch(start = CoroutineStart.UNDISPATCHED)` subscribes to `VoiceInputController.events` before `startListening()`.
2. Events emitted synchronously during `startListening()` are captured by the buffered channel.
3. `awaitEvent` waits for `ListeningStarted` (or a pre-readiness terminal).
4. Only after `ListeningStarted` is received does the cue play.

The clock-alert flow uses the same ordering via `bufferedCaptureSession()`.

### 24.6 Volume non-mutation

### 24.7 Route and volume metadata

Every cue playback event carries common metadata followed by type-specific fields.

**Common fields (both `CUE_PLAYBACK_STARTED` and `CUE_PLAYBACK_ERROR`):**

| Field | Source | Example |
|---|---|---|
| `context` | Cue context constant | `wake_word` / `clock_alert` |
| `policy_version` | `StartListeningCueResult.policyVersion` | `2026-07-cue-v1` |
| `stream` | `selectedStream.toString()` | `4` |
| `current_volume` | `currentVolume.toString()` | `10` |
| `max_volume` | `maxVolume.toString()` | `25` |
| `route` | `routeClassification` | `built_in_speaker` / `bluetooth_a2dp` |

**`CUE_PLAYBACK_ERROR` additionally carries:**

| Field | Source | Example |
|---|---|---|
| `category` | `failureCategory` | `playback_start_failed` |

The `category` field is present only on error events. It does not replace the common fields;
all common fields are still recorded for error events.

Route classification uses a pure function:

```kotlin
internal fun classifyRoute(deviceTypes: Set<Int>): String
```

Tested separately for built-in speaker, Bluetooth (A2DP/HEADSET), wired headset, and mixed routes.

### 24.8 Wake retry semantics

- Maximum two STT attempts per wake-word detection.
- Second attempt occurs only when the first returns `NoTranscript`.
- Each ready attempt plays its own cue.
- `Unavailable` stops immediately (no retry).
- `WakeAttemptCollectionException` containing a flow failure stops the retry loop.

The retry loop is extracted as:

```kotlin
internal suspend fun runWakeCaptureSession(
    runAttempt: suspend (attempt: Int) -> WakeAttemptOutcome,
): WakeSessionCaptureResult
```

### 24.9 Collection-failure classification

| Condition | Category |
|---|---|
| Flow fails before `ListeningStarted` | `startup_collection_failed` |
| Flow fails after `ListeningStarted` | `transcript_collection_failed` |
| STT unavailable | `stt_unavailable` |
| STT produces error event | `stt_recognition_failed` |
| STT stops without transcript | `stt_stopped_without_result` |
| Fatal `Error` (OOM, Linkage, AssertionError) | Propagates — not classified |
| Caller `CancellationException` | Propagates — not classified |

The classification boundary in `runWakeAttempt` catches `Exception` (not `Throwable`),
ensuring fatal JVM errors propagate without conversion to cancellation categories.

### 24.10 Clock-alert ownership semantics

The clock-alert voice session filters events by:

1. **Session ownership** — `event.captureSessionId == captureSessionId` and `isVoiceListening`.
2. **Mode** — Only `VoiceCaptureMode.AlertCommand` triggers the cue.
3. **Event type** — Only `VoiceInputEvent.ListeningStarted` triggers the cue.

```kotlin
internal fun isOwnedAlertEvent(
    event: VoiceInputEvent,
    captureSessionId: Long?,
    isVoiceListening: Boolean,
): Boolean = isVoiceListening && event.captureSessionId != null &&
    event.captureSessionId == captureSessionId
```

Foreign-session events from overlapping voice interactions are silently filtered.

### 24.11 Evidence collection steps

1. Build and install `./gradlew :app:installDebug`.
2. Enable acoustic journal via the debug menu or broadcast:
   ```
   adb shell am broadcast -a com.kernel.ai.debug.action.CLEAR_JOURNAL
   adb shell am broadcast -a com.kernel.ai.debug.action.START_JOURNAL
   ```
3. Trigger the wake word or clock-alert flow.
4. Capture journal:
   ```
   adb shell am broadcast -a com.kernel.ai.debug.action.QUERY_JOURNAL
   ```
5. Capture logcat filtered to the service:
   ```
   adb logcat -s KernelAI WakeWordService ClockAlertService
   ```
6. Record media volume before and after:
   ```
   adb shell settings get system volume_music
   adb shell settings get system volume_alarm
   ```
7. Record DND state and audio route.

### 24.12 Validation matrix (PR #1416 — automated 2026-07-21)

| Device | Scenario | Trigger | Volume/DND/Route | Cue count | Journal ordering | Command result | Evidence path | Result |
|---|---|---|---|---:|---|---|---|---|
| S21 | Unit tests (ownership guard) | Automated | N/A | N/A | N/A | N/A | ClockAlertSessionTest | ✅ PASS |
| S21 | Unit tests (wake cue) | Automated | N/A | N/A | N/A | N/A | WakeWordCueTest | ✅ PASS |
| S21 | App launch + wake service | ADB launch | 9/15 media, DND off, no BT | N/A | N/A | Service OK | logcat | ✅ PASS |
| S23U | App launch + wake service | ADB launch | 6/15 media, DND off, no BT | N/A | N/A | Service OK | logcat | ✅ PASS |
| S21 | ASSIST intent → VoiceCommandActivity | ADB intent | 9/15 media | N/A | N/A | Activity shown | logcat | ✅ PASS |
| S21 | Normal screen-on wake word | ⚠️ Human | 9/15, DND off, built-in | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |
| S21 | Screen-off wake word | ⚠️ Human | 9/15, DND off, built-in | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |
| S21 | Low media volume wake word | ⚠️ Human | TBD | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |
| S21 | DND enabled wake word | ⚠️ Human | TBD | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |
| S21 | Bluetooth route wake word | 🔷 N/A | No BT audio device | — | — | — | — | 🔷 NOT PRESENT |
| S21 | Clock-alert command | ⚠️ Human | TBD | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |
| S21 | Monitored post-idle audibility | ⚠️ Human | TBD | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |
| S23U | Normal screen-on wake word | ⚠️ Human | 6/15, DND off, built-in | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |
| S23U | Screen-off wake word | ⚠️ Human | 6/15, DND off, built-in | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |
| S23U | Low media volume wake word | ⚠️ Human | TBD | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |
| S23U | DND enabled wake word | ⚠️ Human | TBD | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |
| S23U | Bluetooth route wake word | 🔷 N/A | No BT audio device | — | — | — | — | 🔷 NOT PRESENT |
| S23U | Clock-alert command | ⚠️ Human | TBD | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |
| S23U | Monitored post-idle audibility | ⚠️ Human | TBD | TBD | TBD | TBD | TBD | ⚠️ NOT RUN |

**Legend:** ✅ PASS (verified) | ❌ FAIL | ⚠️ NEEDS HUMAN | 🔷 NOT PRESENT | TBD = to be determined during human-led validation

### 24.13 Stale-startup ownership guard (PR #1416 remediation)

`startVoiceControl()` creates an attempt-local `WakeSessionJournal` before STT startup.
The `Unavailable` and exception branches perform an ownership check before mutating
shared service state. The same check is now applied to the successful-start branch:

```kotlin
internal fun ownsCurrentVoiceJournal(
    currentJournal: WakeSessionJournal?,
    attemptJournal: WakeSessionJournal,
): Boolean = currentJournal === attemptJournal
```

Used in `startVoiceControl()` at the `CaptureStartResult.Started` branch — if the
current service journal no longer matches the attempt's journal (because a
replacement session started while the first attempt was waiting for STT), the
stale result is silently discarded without mutating `captureSessionId`, processing
buffered events, or recording evidence against the replacement journal.

Tested in `ClockAlertSessionTest.stale Started result does not affect replacement journal`,
which exercises the production `ownsCurrentVoiceJournal()` function with stale and
current journals, and verifies the replacement journal remains uncorrupted.

### 24.14 Monitored audibility standard

The start-listening cue policy defines four distinct levels of evidence, each
requiring different validation methods:

| Level | Evidence | Source | Validation method |
|---|---|---|---|
| 1 — Requested | `CUE_REQUESTED` recorded in journal | App before calling `StartListeningCuePlayer.playCue()` | Unit test / journal inspection |
| 2 — Started | `CUE_PLAYBACK_STARTED` recorded in journal | App after `MediaPlayer.start()` returns | Unit test / journal inspection |
| 3 — Route/volume metadata | `route`, `current_volume`, `max_volume`, `stream` in journal | `StartListeningCueResult` from cue player | Unit test / journal inspection |
| 4 — Acoustic audibility | Human observer confirms audible | Physical device test | Monitored trial with human |

**A test cannot claim acoustic success from levels 1-3 alone.** `CUE_PLAYBACK_STARTED`
at zero volume, on a muted stream, or on a non-rendering Bluetooth route is not
acoustic proof and must not be reported as audible.

**Minimum evidence fields for physical trials:**

- `trial_id` (unique per trial)
- `device_id`
- `device_screen_state` (on / off)
- `media_volume_before` / `alarm_volume_before`
- `dnd_state`
- `audio_route` (built_in_speaker / bluetooth_a2dp / wired_headset / unknown)
- `cue_requested` (true/false + timestamp)
- `cue_playback_started` (true/false + timestamp)
- `route` / `current_volume` / `max_volume` from journal
- `human_observed_audible` (yes / no)
- `transcript_captured` (yes / no)
- `duplicate_cue` (yes / no, with description)
- `terminal_session_event` (type + category)
- `restoration_verified` (yes / no)

**Invalidation rules:**

A trial MUST be marked INVALID — RESTORATION FAILED if:
- Any mutated device state (volume, DND, ringer, BT, permissions) is not restored to its
  exact baseline value.
- The baseline verification step after restoration reveals a mismatch.
- Equipment calibration or source placement was disturbed and not re-verified.

