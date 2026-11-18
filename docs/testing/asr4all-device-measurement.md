# ASR4ALL device measurement harness

The opt-in `Asr4allTierMeasurementDeviceTest` compares streaming S/M asr4all at `c16r4` and `c32r8` with the shipping Zipformer recognizer on the same staged 16 kHz mono PCM16 WAVs. Run the owner decision set on both agreed phones; synthetic breadth is optional and is regression/latency-only. The test never invokes production voice commands, timers, or alarms.

## Private inputs

Keep host fixtures and reports in operator-private directories. Do not put audio, manifests, phrase files, or reports in Git, APK/AAB assets, terminal transcripts, issue/PR comments, or shared artifacts. The report contains owner reference text and raw decoder transcripts.

Stage only the approved fixture trees and separate phrase-list files. Each set directory contains `manifest.json` and its manifest-named nested WAVs; the set root may contain nested fixture paths. Phrase files use the agreed `schema_version: 1`, `phrases: [{"id":"…","text":"…"}]` envelope and remain separate from manifests. The owner manifest and phrase file must identify exactly 20 unique matching IDs. Only the manifest's `formats["16k"]` WAV is measured. The test validates app-private containment, PCM alignment/metadata, duration, hashes, set role, and phrase/fixture IDs before inference.

Owner phrase SHA-256 is a required explicit instrumentation argument obtained from the coordinator's trusted immutable phrase-list source; never calculate trust from the staged copy itself. The owner phrase-list source/digest is not available in this worktree. For breadth, the harness pins raw phrase bytes to reviewed commit `437de7ae8c2bcb60eb4d4fd77f7bc9af3caa63be`, SHA-256 `72790822ff96cc1c4e0de6d4169ac7e2afda11deb11ee06d8716ecd44fd01271`; the supplied manifest and trusted phrases must cover exactly `CL-001` through `CL-100`. Omit all three breadth arguments to skip it. If breadth inputs are supplied but incomplete, the report marks them not-run and performs no breadth inference.

Optional owner speech-end metadata is a private sidecar, not part of the recording/fixture producer contract. Require the sidecar bytes and trusted SHA-256 independently from the coordinator. Its exact envelope is `{"schema_version":1,"set":"owner-decision","method":"independent-human-annotation","speech_ends":[{"fixture_id":"…","fixture_sha256":"…","speech_end_sample":123}]}`. It must contain exactly 20 unique owner fixture IDs, each fixture SHA-256 must match that fixture's validated 16 kHz WAV, and each `speech_end_sample` is the exclusive end-of-audible-speech boundary on that final PCM timeline (`1..sampleCount` inclusive), independently judged from candidate output. Omit both sidecar arguments to run paired WER without speech-end latency; never infer offsets from WAV duration or recognizer output.

Example private host input tree:

```text
$PRIVATE_INPUT_ROOT/
  owner/
    manifest.json
    16k/<manifest-named WAVs>
  owner-phrases.json
  # Optional independent-human speech-end annotations:
  owner-speech-ends.json
  # Optional, as one paired breadth set:
  breadth/
    manifest.json
    16k/<manifest-named WAVs>
  breadth-phrases.json
```

## App-private staging and run

Use the same verified private input tree and owner phrase digest on each phone. `$SERIAL`, `$OWNER_PHRASES_SHA256`, and all host paths below are operator-supplied; no measurement has been run by this harness work. The tar stream writes directly into the target app's private `filesDir` without creating a device-side shared-storage copy. For breadth, include `breadth` and `breadth-phrases.json` in the tar source list too. If the independently supplied sidecar is used, stage `owner-speech-ends.json` the same way and provide its coordinator-trusted digest.

```sh
APP_ID=ai.jandal.assistant.debug
adb -s "$SERIAL" shell run-as "$APP_ID" mkdir -p files/asr4all-measure-input
# Owner-only staging:
tar -C "$PRIVATE_INPUT_ROOT" -cf - owner owner-phrases.json |
  adb -s "$SERIAL" shell run-as "$APP_ID" tar -xf - -C files/asr4all-measure-input
# Optional sidecar staging:
tar -C "$PRIVATE_INPUT_ROOT" -cf - owner-speech-ends.json |
  adb -s "$SERIAL" shell run-as "$APP_ID" tar -xf - -C files/asr4all-measure-input
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

If the independently supplied sidecar is staged, add both arguments to the same invocation:

```text
-e owner_speech_ends asr4all-measure-input/owner-speech-ends.json
-e owner_speech_ends_sha256 "$OWNER_SPEECH_ENDS_SHA256"
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

The report records device/model identities, manifest/phrase/sidecar digests, per-lane outcomes and coverage, shared ORT/Sherpa runtime initialization, comparable recognizer-open time, pre-load/post-load/post-warmup absolute PSS and deltas, shared-runtime residuals and sampling-noise ranges, compute-only RTFx, and paced delivery metrics. Runtime initialization and app startup are outside candidate model-open timings; the exact debug measurement test class runs with application `onCreate` suppressed. Model bundle file hashes are calculated after all trials; preflight uses only file existence/size/mtime. Compare all recorded identities across both phones before combining results.

Every primary lane, including Zipformer, consumes the same full paired PCM; paired WER and compute RTFx use that full signal. Zipformer's early-endpoint behavior is a separate `endpoint_trials` section and is excluded from WER and primary RTFx. First-partial offsets refer to each scheduled delivered chunk end. `forced_clip_end_latency_ms` is relative to the scheduled end of the full injected WAV and is emitted only for a nonblank final; it is not a speech-end measurement. With the optional sidecar, `speech_end_latency_ms_from_human_annotation` is paced injected-input completion relative to the independently human-annotated speech-end sample—not acoustic microphone/service latency. Without offsets, status is `not_decision_grade_missing_offsets`; paired WER still runs. Blank finals count as WER deletions but are excluded from final-latency coverage. Compute-only RTFx excludes pacing waits; `rtfx_paced` includes scheduled full-clip delivery and final result work.

WER is raw-decoder WER with one NFC/case/punctuation tokenizer that preserves Māori macrons and removes apostrophes; no production transcript correction is applied. The report exposes lane WER only if every expected owner fixture/repetition is scored in every lane and all lanes carry the same validated PCM hash per key; an error, missing/extra/duplicate key, or paired-input mismatch invalidates WER across the decision set. WER sums edits and reference words per lane, not per-clip ratios. Synthetic rows have no WER and never enter owner selection. A device comparison/recommendation requires complete owner coverage and matching input/model/phrase identities on both phones. No device measurements have been run by this harness work.
