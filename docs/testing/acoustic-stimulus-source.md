# Debug acoustic stimulus source

The source endpoint exists only in the debug application (`com.kernel.ai.debug`). It
plays an approved app-private WAV fixture through the source device's built-in
speaker. It does not schedule trials or change wake-word behaviour.

## Invocation

Use an ADB alias, not a serial number or other private selector, and target the
receiver explicitly:

```sh
adb -s "$JANDAL_S23U_SOURCE" shell am broadcast \
  -n com.kernel.ai.debug/com.kernel.ai.debug.acoustic.AcousticStimulusReceiver \
  -a com.kernel.ai.debug.action.PLAY_ACOUSTIC_STIMULUS \
  --es trial_id smoke-001 \
  --es fixture_id natural_wake \
  --ei volume_index 7 \
  --ef player_gain 1.0
```

The receiver accepts only `trial_id`, `fixture_id`, `volume_index` and optional
`player_gain`. Trial IDs are opaque bounded identifiers. Fixture IDs are
allowlisted manifest IDs; paths and path-like IDs are rejected. Volume is an
integer from 1 through the source media-stream maximum. Player gain is bounded to
`0.1..1.0`, with `1.0` as the normal value. A request must use the debug package
and receiver component explicitly.

Each request writes a private JSON result below the app's private
`files/acoustic-stimulus-results/` directory and emits only structured events
under the `AcousticStimulus` log tag. The result contains fixture hash and timing,
route, focus, volume, completion/error, timeout, overlap and cleanup fields,
including a final `cleanup_completed` event with restoration status. If result
persistence fails, the externally returned outcome is invalid with
`result_write_failed`; playback/cleanup status remains visible in the returned
evidence even though the evidence file was not persisted.
It never contains a host path, device selector or audio bytes.

## Approved fixture contract

The fixture directory is app-private:

```text
files/acoustic-fixtures/
  manifest.json
  <allowlisted-file>.wav
```

`manifest.json` has this shape (the hash and duration must describe the exact
file installed):

```json
{
  "schema_version": 1,
  "fixtures": [
    {
      "fixture_id": "natural_wake",
      "file_name": "natural_wake.wav",
      "sha256": "<64 lowercase hex characters>",
      "duration_ms": 1250
    }
  ]
}
```

WAV files must be RIFF/WAVE, signed linear PCM, 16-bit little-endian, mono,
48 kHz, non-empty and no longer than five seconds. The helper validates the WAV
header, duration, manifest hash and manifest duration before opening a file
descriptor. It rejects malformed/unsupported WAV, missing or unknown fixtures,
empty files, over-duration files and hash/metadata mismatches. Private natural
and voice-cloned recordings remain outside Git and are never placed in an APK or
AAB.

## Safe local installation

Install an approved fixture and its manifest only after reviewing their private
provenance and hash. The temporary files are made readable solely so `run-as`
can copy them into the app UID's private directory; remove them after copying.

```sh
export SOURCE_ALIAS="$JANDAL_S23U_SOURCE"
export FIXTURE_FILE="$HOME/private-acoustic-fixtures/natural_wake.wav"
export MANIFEST_FILE="$HOME/private-acoustic-fixtures/manifest.json"

adb -s "$SOURCE_ALIAS" shell run-as com.kernel.ai.debug mkdir -p files/acoustic-fixtures
adb -s "$SOURCE_ALIAS" push "$FIXTURE_FILE" /data/local/tmp/acoustic-natural_wake.wav
adb -s "$SOURCE_ALIAS" push "$MANIFEST_FILE" /data/local/tmp/acoustic-manifest.json
adb -s "$SOURCE_ALIAS" shell chmod 644 /data/local/tmp/acoustic-natural_wake.wav /data/local/tmp/acoustic-manifest.json
adb -s "$SOURCE_ALIAS" shell run-as com.kernel.ai.debug cp /data/local/tmp/acoustic-natural_wake.wav files/acoustic-fixtures/natural_wake.wav
adb -s "$SOURCE_ALIAS" shell run-as com.kernel.ai.debug cp /data/local/tmp/acoustic-manifest.json files/acoustic-fixtures/manifest.json
adb -s "$SOURCE_ALIAS" shell rm /data/local/tmp/acoustic-natural_wake.wav /data/local/tmp/acoustic-manifest.json
```

Do not use `adb push` directly into the application data directory, add fixture
files to a source/resource directory, or reuse wake-model training audio.

## Lifecycle and safety
Playback uses `MediaPlayer` and `goAsync()` with a seven-second hard timeout.
The helper snapshots only media volume and the current output route, verifies a
built-in speaker route, requests transient media focus, opens the validated WAV
through a file descriptor, and emits prepared/started/completed/error events.
After player release, descriptor close, focus abandonment, volume restoration and
restoration verification it emits the final `cleanup_completed` event. Every
completion, preparation error, player error, timeout or partial failure releases
resources and restores the exact original media volume. A restoration or cleanup
failure makes the result invalid while preserving the original playback error.
Concurrent requests are rejected without mutating audio state.

## Target structured event journal

The debug application (`com.kernel.ai.debug`) includes an additional receiver for
structured diagnostics from the target device. It provides a bounded in-memory event
journal, an event-driven bounded wait and a snapshot-since mechanism. It does not
alter wake thresholds, models, provider selection, silence-gate semantics or
production service behaviour.

### Receiver actions

```sh
# Get the current highest sequence number (0 = journal empty)
adb shell am broadcast \
  -n com.kernel.ai.debug/com.kernel.ai.debug.journal.TargetEventJournalReceiver \
  -a com.kernel.ai.debug.action.GET_JOURNAL_SEQUENCE

# Wait up to 10 seconds for an STT_READY event after sequence 5
adb shell am broadcast \
  -n com.kernel.ai.debug/com.kernel.ai.debug.journal.TargetEventJournalReceiver \
  -a com.kernel.ai.debug.action.WAIT_FOR_JOURNAL_EVENT \
  --el since_sequence 5 \
  --es event_type STT_READY \
  --el timeout_ms 10000

# Retrieve the complete snapshot since sequence 5
adb shell am broadcast \
  -n com.kernel.ai.debug/com.kernel.ai.debug.journal.TargetEventJournalReceiver \
  -a com.kernel.ai.debug.action.GET_JOURNAL_SNAPSHOT \
  --el since_sequence 5
```

### Journal contract

- Default capacity: **256 events** — covers the expected 20–30 events per trial
  across a full idle-interval matrix plus diagnostic overhead.
- Bounded ring buffer; oldest events are evicted when capacity is exceeded.
- `overflowed` flag is set when eviction occurs.
- Events carry a monotonically increasing **sequence number**, a **monotonic
  timestamp** (elapsed-time since boot), an optional wall-clock timestamp for
  operator diagnostics, a **generation ID** (detector (re-)start) and a **session
  ID** (voice session), and a small structured metadata map.
- No transcripts, audio samples, private file paths, device selectors, account
  data, service endpoints, model paths or exception dumps are recorded.

### Event vocabulary

| Type | Description | Metadata |
|---|---|---|
| `DETECTOR_GENERATION_STARTED` | ONNX pipeline loaded and detector began | — |
| `SILENCE_GATE_ENTERED` | Silence gate became active (Stage 2/3 suppressed) | — |
| `VOICED_FRAME_AFTER_SILENCE` | First voiced frame detected after silence gating | — |
| `STAGE3_READY` | Embedding ring filled; classifier active | — |
| `ACTIVATION_CANDIDATE` | Confidence at or above low threshold | `confidence`, `mode` (high/low) |
| `VERIFIED_ACTIVATION` | Activation confirmed by STT or high-confidence path | `mode` (high/low) |
| `WAKE_CALLBACK_INVOKED` | WakeWordService callback entered | — |
| `VOICE_SESSION_STARTED` | Assistant/voice session beginning | — |
| `STT_START_REQUESTED` | Speech recogniser start called | `attempt` |
| `STT_READY` | Recogniser reported readiness / ListeningStarted | — |
| `CUE_REQUESTED` | Start-listening cue playback requested | `force_audible` |
| `STT_PARTIAL` | Partial STT result (no transcript text) | `length` (chars) |
| `STT_ERROR` | STT error | `error` |
| `SESSION_COMPLETED` | Voice session ended normally | — |
| `SESSION_CANCELLED` | Voice session cancelled | `reason` |
| `DETECTOR_REARMED` | Detector re-armed after session end | — |
| `DETECTOR_ERROR` | Fatal detector error (ONNX/AudioRecord) | `error` |

### Snapshot-since contract

`GET_JOURNAL_SNAPSHOT` with `since_sequence=N` returns all events whose
sequence number is strictly greater than N, in chronological order. An empty
array is returned when N equals or exceeds the current highest sequence.

### Bounded wait contract

`WAIT_FOR_JOURNAL_EVENT` provides event-driven synchronisation for the future
paired runner. After source playback the runner opens one bounded wait for
`STT_READY` / `ListeningStarted`. The receiver checks once synchronously, then
enters a `wait`/`notify` loop on the journal's intrinsic lock.

Timeouts:
- Default: 15 seconds
- Minimum: 500 ms

Result codes: `0` = event found; `1` = timeout; `2` = error.

### Response format

Events are serialised as compact JSON:

```json
{"s":1,"m":123456789,"w":1705300000000,"t":"STT_READY","g":1,"i":1,"d":{}}
```

Fields: `s` sequence; `m` monotonic Ms; `w` wall-clock Ms; `t` type; `g`
generation ID; `i` session ID; `d` metadata dict.

### Target-idle invariant

The journal interface preserves the target-idle invariant required by the design:
no target polling, log streaming or state queries occur during the configured idle
interval. One bounded wait opens only after source wake playback completes. Full
evidence is retrieved after the trial via `GET_JOURNAL_SNAPSHOT`.

### Release isolation

The receiver, journal implementation and all debug actions are present only in
debug builds. The production-side `AcousticJournalBridge` and
`AcousticEventRecorder` no-op interface remain in the release APK/AAB as required
for the production-code hooks.

Verify with:

```sh
./gradlew verifyTargetEventJournalReleaseIsolation
```

## Production-source integration points

### `OnnxWakeWordDetector` (`core/voice/src/main/`)

Calls `AcousticJournalBridge.record(...)` at these transition points:
- After models load: `DETECTOR_GENERATION_STARTED`
- Silence gate activation: `SILENCE_GATE_ENTERED`
- Voice onset after gating: `VOICED_FRAME_AFTER_SILENCE`
- First classifier-ready frame: `STAGE3_READY`
- At each confidence threshold crossing: `ACTIVATION_CANDIDATE`
- After STT verification or high-confidence: `VERIFIED_ACTIVATION`
- On fatal detector error: `DETECTOR_ERROR`

### `WakeWordService` (`app/src/main/`)

Calls `AcousticJournalBridge.record(...)` at these points:
- `handleDetection()` start: `VOICE_SESSION_STARTED`, allocates session ID
- Before `startListening()`: `STT_START_REQUESTED`
- On `ListeningStarted` event: `STT_READY`
- Before `cuePlayer.playCue()`: `CUE_REQUESTED`
- On error/break paths: `SESSION_CANCELLED` with reason
- After session end: `SESSION_COMPLETED`
- In `rearmDetector()`: allocates generation ID, records `WAKE_CALLBACK_INVOKED`
  (in the `onDetected` lambda) and `DETECTOR_REARMED`
- On `VoiceInputEvent.PartialTranscript`: `STT_PARTIAL` (length only, no text)
- On `VoiceInputEvent.Error`: `STT_ERROR`

### `AcousticJournalBridge` (`core/voice/src/main/`)

Thread-safe singleton bridge that connects production hooks to the debug-only
journal. Defaults to `NoOp`. The debug `TargetEventJournalReceiver` installs the
real journal on first invocation via `AcousticJournalBridge.install(...)`.
