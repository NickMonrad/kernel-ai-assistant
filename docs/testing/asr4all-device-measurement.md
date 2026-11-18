# ASR4ALL device measurement harness

The opt-in `Asr4allTierMeasurementDeviceTest` compares streaming S/M asr4all at `c16r4` and `c32r8` with the shipping Zipformer recognizer on the same staged 16 kHz mono PCM16 WAVs. Run the owner decision set on both agreed phones; synthetic breadth is optional and is regression/latency-only. The test never invokes production voice commands, timers, or alarms.

## Private inputs

Keep host fixtures and reports in operator-private directories. Do not put audio, manifests, phrase files, or reports in Git, APK/AAB assets, terminal transcripts, issue/PR comments, or shared artifacts. The report contains owner reference text and raw decoder transcripts.

Stage only the approved fixture trees and separate phrase-list files. Each set directory contains `manifest.json` and its manifest-named nested WAVs; the set root may contain nested fixture paths. Phrase files use the agreed `schema_version: 1`, `phrases: [{"id":"…","text":"…"}]` envelope and remain separate from manifests. The owner manifest and phrase file must identify exactly 20 unique matching IDs. Only the manifest's `formats["16k"]` WAV is measured. The test validates app-private containment, PCM alignment/metadata, duration, hashes, set role, and phrase/fixture IDs before inference.

Owner phrase SHA-256 is a required explicit instrumentation argument obtained from the coordinator's trusted immutable phrase-list source; never calculate trust from the staged copy itself. The owner phrase-list source/digest is not available in this worktree. For breadth, the harness pins raw phrase bytes to reviewed commit `437de7ae8c2bcb60eb4d4fd77f7bc9af3caa63be`, SHA-256 `72790822ff96cc1c4e0de6d4169ac7e2afda11deb11ee06d8716ecd44fd01271`; the supplied manifest and trusted phrases must cover exactly `CL-001` through `CL-100`. Omit all three breadth arguments to skip it. If breadth inputs are supplied but incomplete, the report marks them not-run and performs no breadth inference.

Example private host input tree:

```text
$PRIVATE_INPUT_ROOT/
  owner/
    manifest.json
    16k/<manifest-named WAVs>
  owner-phrases.json
  # Optional, as one paired breadth set:
  breadth/
    manifest.json
    16k/<manifest-named WAVs>
  breadth-phrases.json
```

## App-private staging and run

Use the same verified private input tree and owner phrase digest on each phone. `$SERIAL`, `$OWNER_PHRASES_SHA256`, and all host paths below are operator-supplied; no measurement has been run by this harness work. The tar stream writes directly into the target app's private `filesDir` without creating a device-side shared-storage copy. For breadth, include `breadth` and `breadth-phrases.json` in the tar source list too.

```sh
APP_ID=ai.jandal.assistant.debug
adb -s "$SERIAL" shell run-as "$APP_ID" mkdir -p files/asr4all-measure-input
# Owner-only staging:
tar -C "$PRIVATE_INPUT_ROOT" -cf - owner owner-phrases.json |
  adb -s "$SERIAL" shell run-as "$APP_ID" tar -xf - -C files/asr4all-measure-input
# Build and install both the target and instrumentation APKs:
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Run owner-only once per phone, supplying the trusted digest independently:

```sh
adb -s "$SERIAL" shell am instrument -w \
  -e measure_enabled true \
  -e class com.kernel.ai.Asr4allTierMeasurementDeviceTest \
  -e owner_dir asr4all-measure-input/owner \
  -e owner_phrases asr4all-measure-input/owner-phrases.json \
  -e owner_phrases_sha256 "$OWNER_PHRASES_SHA256" \
  ai.jandal.assistant.debug.test/com.kernel.ai.debug.inflect.InflectSpikeAndroidJUnitRunner
```

For breadth, stage both optional files and add all three arguments to the same invocation:

```text
-e synthetic_dir asr4all-measure-input/breadth
-e synthetic_phrases asr4all-measure-input/breadth-phrases.json
-e synthetic_phrases_sha256 72790822ff96cc1c4e0de6d4169ac7e2afda11deb11ee06d8716ecd44fd01271
```

The test is skipped in normal connected-test runs unless `measure_enabled=true`; when enabled, missing or invalid required owner inputs fail before inference. Each run atomically creates a unique JSON file under `files/asr4all-measurement-results/<run-id>/measurement.json`. Keep run IDs and all report contents private. Retrieve directly to a private host destination; never print the JSON to a terminal:

```sh
adb -s "$SERIAL" exec-out run-as ai.jandal.assistant.debug cat \
  "files/asr4all-measurement-results/$RUN_ID/measurement.json" \
  > "$PRIVATE_RESULTS/$DEVICE-$RUN_ID.json"
```

## Interpretation

The report records the app/device identity, model-bundle file hashes, manifest/phrase digests, set role, per-lane outcomes and coverage, load time, pre-load/post-load/post-warmup absolute PSS and deltas, PSS sampling noise range, compute-only RTFx, and paced-stream delivery metrics. Run the same harness build on both phones and compare the recorded identities before combining results.

`first_partial_latency_ms_from_scheduled_clip_start` and its audio offset use injected clip time. `forced_clip_flush_latency_ms` is relative to the scheduled end of the complete injected WAV and is emitted only for a nonblank forced-flush final. `observed_decoder_endpoint_to_final_ms` is separately measured from Zipformer's actual decoder endpoint. Lane summaries keep these latency origins and their means separate. No acoustic speech-end offset is available, so neither the injected clip end nor the decoder endpoint is asserted to be an independently validated acoustic speech end. Blank finals do not count toward valid-final latency. `rtfx_compute_only` excludes pacing waits; `rtfx_paced` includes scheduled delivery and final result work. They are distinct metrics.

WER is raw-decoder WER with one NFC/case/punctuation tokenizer that preserves Māori macrons and removes apostrophes; no production transcript correction is applied. Aggregation sums edits and reference words separately per owner model+tier. Synthetic rows have no WER and never enter owner selection. A device comparison or recommendation requires complete owner coverage on both phones; this harness work includes no device measurements.
