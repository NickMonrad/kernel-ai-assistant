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
route, focus, volume, completion/error, timeout, overlap and cleanup fields. It
never contains a host path, device selector or audio bytes.

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
Every completion, preparation error, player error, timeout or partial failure
releases the player, closes the descriptor, abandons focus, restores the exact
original media volume and verifies that restoration. A restoration or cleanup
failure makes the result invalid. Concurrent requests are rejected without
mutating audio state.
