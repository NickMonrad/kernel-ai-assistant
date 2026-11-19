# #1978 asr4all streaming STT: evidence

Scope: criteria 1–3 (Kotlin port of FUTO's pinned HF asr4all ONNX driver, engine parity, app wiring). Criteria 4–5 (owner audio accuracy and tier decision) remain pending #1984. For criterion 2, owner approved a reproducible synthetic WAV recipe plus expected parity output in place of committed audio; no audio is committed or packaged.

## Pinned upstream
- S repo: `futo-org/asr4all-s` at `87a6b5d093723fe37e9f69ad26307a3b95a31af0`, path `onnx/`.
- M repo: `futo-org/asr4all-m` at `7d6e9145109a5bece4f05831d046e93d27e38a99`, path `onnx/`.
- Both parity directories use all 13 model files in the official `onnx/` trees, including complete tier metadata. S upstream metadata Git blob is `18f9a4d3396ad8104a7c79e9fb46a1690eca9ba5`; M is `0135c69a48b0373d8b00bc13a36b06270b731703`. Every model file is SHA-256 pinned in `core/voice/src/test/resources/asr4all/parity-expected.tsv`.
- The reference helper `onnx/asr4all_onnx.py` is the same 26,116-byte blob in both revisions (Git blob `14788e17d64cbc0a06e6cb5d08a81bc6d3199ed3`, SHA-256 `f173c6d61a004d8596d6f9b3623716167176639440920a92917f902d5566bba4`).

## Parity reference (pinned Python driver, same ONNX files)

Fixture: `clip.wav`, 16 kHz mono signed 16-bit PCM, 9.24525 s, SHA-256 `5b8e2fd35bf3d63db1e106eea1f2d860210572850c4d147f499506e761cf21f2`. Run `scripts/generate_asr4all_parity_fixture.sh /var/tmp/asr4all-1987-parity/generated-a` from the repository root; the WAV stays outside Git. The script refuses a destination inside the repository and validates the digest. Exact inert spoken text: `A small green notebook is beside the open window. This is a synthetic speech fixture for offline recognition parity, not a real recording.` The duration exceeds the seven 8,000-sample chunks needed for the chunk-index-6 partial assertion.

Generator pins eSpeak NG 1.52.0 (`-v en-us -s 150 -p 50 -a 100`) and FFmpeg n9.0.2 (`-ar 16000 -ac 1 -c:a pcm_s16le -map_metadata -1 -fflags +bitexact -flags:a +bitexact`). Two independent generations produced identical bytes and the recorded digest. This is synthetic speech parity, not an accuracy measurement.

Reference environment: Python 3.12.13 via `uv` 0.11.26 in a private venv, not a global install. Reproduce with `uv venv --python 3.12.13 /var/tmp/asr4all-1987-parity/.venv` then `uv pip install --python /var/tmp/asr4all-1987-parity/.venv/bin/python numpy==2.2.6 onnxruntime==1.22.0 coloredlogs==15.0.1 flatbuffers==25.12.19 humanfriendly==10.0 mpmath==1.3.0 packaging==26.3 protobuf==7.36.2 sympy==1.14.0`. The upstream helper itself is SHA-256 pinned above.

The external pinned Python driver produced the reference rows from the exact full official S and M ONNX bundles. Download each bundle's `onnx/` files from its pinned Hugging Face `/resolve/<revision>/onnx/<file>` URL and verify against the per-file digests in `parity-expected.tsv`; place the complete byte-verified sets into `source-s/` and `m/`. Copy the generated file with `cp /var/tmp/asr4all-1987-parity/generated-a/clip.wav /var/tmp/asr4all-1987-parity/clip.wav`. The reference invocations were:
```
for model in source-s m; do
  for tier in stream_c16r4 stream_c32r8; do
    /var/tmp/asr4all-1987-parity/.venv/bin/python /var/tmp/asr4all-1987-parity/asr4all_onnx.py /var/tmp/asr4all-1987-parity/$model /var/tmp/asr4all-1987-parity/clip.wav --tier $tier
    /var/tmp/asr4all-1987-parity/.venv/bin/python /var/tmp/asr4all-1987-parity/asr4all_onnx.py /var/tmp/asr4all-1987-parity/$model /var/tmp/asr4all-1987-parity/clip.wav --tier $tier --no-pcec
    /var/tmp/asr4all-1987-parity/.venv/bin/python /var/tmp/asr4all-1987-parity/asr4all_onnx.py /var/tmp/asr4all-1987-parity/$model /var/tmp/asr4all-1987-parity/clip.wav --tier $tier --stream
  done
done
```

Partial oracle command (run in the isolated venv from the scratch directory; `asr4all_onnx.py` is the pinned upstream file):
```
PYTHONPATH=/var/tmp/asr4all-1987-parity /var/tmp/asr4all-1987-parity/.venv/bin/python - <<'PY'
from asr4all_onnx import load_engine, load_wav
root = "/var/tmp/asr4all-1987-parity"
clip = load_wav(f"{root}/clip.wav")
for model, directory in (("S", f"{root}/source-s"), ("M", f"{root}/m")):
    engine = load_engine(directory)
    for tier in ("stream_c16r4", "stream_c32r8"):
        engine.set_tier(tier)
        for chunk in range(7):
            start = chunk * 8000
            engine.decode_stream(clip[start:min(start + 8000, len(clip))])
        print(model, tier, "partial_pcec", engine.decode_pcec(decode_tail=True))
PY
```
The output is read before `finish()` and exercises unflushed PCEC state. The expected output rows and all 13 model-file SHA-256 values for each tier are committed as text in `core/voice/src/test/resources/asr4all/parity-expected.tsv`. Model identity is checked before Kotlin decoding; an adjacent driver file is allowed only at its separately pinned SHA-256.

Reference outputs (official pinned driver; 12 final rows and four intermediate partial rows):

| Model | Tier | Mode | Driver output |
|---|---|---|---|
| S | c16r4 | offline PCEC | `Small green notebook is besides an open window. This is an speech fixture for offline recognition par. Not a real recording.` |
| S | c16r4 | offline plain | `small green notebook is besides it open window this is ynfabic sach fixture f offline recognition paroy not a real recording` |
| S | c16r4 | stream PCEC | `Small green notebook is besides an open window. This is an speech fixture for offline recognition par. Not a real recording.` |
| S | c32r8 | offline PCEC | `Small green notebook is besides an open window. This is a synthatic speech fixture for offline recognition par. Not a real record.` |
| S | c32r8 | offline plain | `small green notebook is besides it open window this is a synthatic sach fixture f offline recognition paroy not a real recardding` |
| S | c32r8 | stream PCEC | `Small green notebook is besides an open window. This is a synthatic speech fixture for offline recognition par. Not a real record.` |
| M | c16r4 | offline PCEC | `Small Green Notebook is besided the open window. This is a synthetic speech fixture far offline recognition parody. Not a real recording.` |
| M | c16r4 | offline plain | `mall green notebook is besided the open window this is a synthetic speech fixture far offline recognition parody not a real recording` |
| M | c16r4 | stream PCEC | `Small Green Notebook is besided the open window. This is a synthetic speech fixture far offline recognition parody. Not a real recording.` |
| M | c32r8 | offline PCEC | `Small Renebook is besided the open window. This is a synthetic speech fixture far offline recognition parody. Not a real recording.` |
| M | c32r8 | offline plain | `small renebook is besided the open window this is a synthetic speech fixture far offline recognition parody not a real recording` |
| M | c32r8 | stream PCEC | `Small Renebook is besided the open window. This is a synthetic speech fixture far offline recognition parody. Not a real recording.` |
| S | c16r4 | partial PCEC, chunk 6 | `Small green notebook is besides it open.` |
| S | c32r8 | partial PCEC, chunk 6 | `Small green noteb.` |
| M | c16r4 | partial PCEC, chunk 6 | `Small Green Notebook is besided the open w.` |
| M | c32r8 | partial PCEC, chunk 6 | `Small reb` |

Kotlin `Asr4allEngineParityTest` uses `ASR4ALL_PARITY=required`; missing or mismatched input is a failure, not a skip. Invocation (rerun fresh, real JVM ONNX Runtime; XML: `core/voice/build/test-results/testDebugUnitTest/TEST-com.kernel.ai.core.voice.asr4all.Asr4allEngineParityTest.xml`):
```
ANDROID_HOME=/home/lokhor/Android/Sdk ASR4ALL_PARITY=required ASR4ALL_PARITY_MODEL=S ASR4ALL_MODEL_DIR=/var/tmp/asr4all-1987-parity/source-s ASR4ALL_FIXTURE_WAV=/var/tmp/asr4all-1987-parity/generated-a/clip.wav ./gradlew --no-daemon :core:voice:testDebugUnitTest --rerun --tests 'com.kernel.ai.core.voice.asr4all.Asr4allEngineParityTest'
```
M uses `ASR4ALL_PARITY_MODEL=M` and `ASR4ALL_MODEL_DIR=/var/tmp/asr4all-1987-parity/m`; all other environment and Gradle arguments stay the same.

Each required-mode run covers 11 tests: eight parity rows (six final modes and two chunk-index-6 partials), two reset-after-acoustic/PCEC-carry tier tests against fresh streams, and one closed-engine rejection/reopen test. S: 11 passed, 0 skipped, 0 failures. M: 11 passed, 0 skipped, 0 failures. Both runs used real JVM ONNX Runtime; the reset fixture produced committed PCEC text before reset. The strict 13-file-per-model-directory SHA-256 checks and helper SHA-256 check passed before decoding. The test was not weakened.

Earlier repo-fixture run: a different parity run reported 6/7 failures. Its input was different or unverified (fixture hash not recorded), so it is not a known failure on this pinned WAV and is not reproduced by these results.

Device harness: `Asr4allTierMeasurementDeviceTest` (`app/src/androidTest`, opt-in via `measure_enabled=true`) is documented in `docs/testing/asr4all-device-measurement.md`. Device measurements: not executed; no phone run is recorded in this evidence. The former host-JVM `Asr4allTierMeasurementTest` was removed. Host metrics unit tests verify the metric arithmetic only and are not decision evidence.

Routing tests: `SelectableVoiceInputControllerTest` (32) and `SelectableVoiceInputHandoffTest` (15) pass, 0 failures (XML 03:57:17 and 03:57:18).

## S23U smoke (RFCW11265XH), debug build
- Installed `app-debug.apk` built from the opening head (the PR head before remediation; exact build SHA not recorded in this README) via `adb install -r`; `lastUpdateTime` 2026-11-18 10:49:12. Remediated head (`47be1ed4` and later) has not been installed or run on device.
- Six model files pushed to the app's models folder under prefixed names (not committed).
- Settings → Voice: asr4all row shows **Downloaded**; selecting it shows **Ready**; no `AndroidRuntime` errors in logcat.
- Engine selection was restored to Automatic after the check. Hey Jandal toggle was not touched.

## Device trials, criterion 1 (acoustic stimulus, S21 source → S23U target)
- Stimulus: 48 kHz mono s16 synthetic phrase, 3.29 s, generated outside Git. Manifest `fixture_id=natural_wake`, sha256 prefix `52c1e43f…e576` (the manifest value is truncated; the full digest was not recorded in this README). Installed in S21 app-private `files/acoustic-fixtures/`.
- Source S21 (`R5CR605B71K`): `AcousticStimulusReceiver` events `prepared` → `started` → `completed` → `cleanup_completed`, `exact_restoration_verified=true`, for trials smoke-1978-001/002/003.
- **Trial 001: invalid.** No S23U capture was active, so it measured nothing.
- **Trial 002, volume index 7 (lower-volume trial, not a proven safe level): timeout.** The S23U capture ran and ended with `No speech heard` after the 10 s no-speech timeout.
- **Trial 003, volume 15: captured.** Chat push-to-talk on S23U (accessibility bounds `[305,1876][380,1928]`): AudioRecord opened (VOICE_RECOGNITION, 16 kHz). Trace `ADB_INTENT_TRACE submitMode=Voice` at 11:01:22.338 with the final `The quick brown fox stump over the lazy dog.` (cased and punctuated).
- Coupling: the captured final is observed for trial 003 only.
- **Not verified:** streaming partials (`PartialTranscript` was not captured; no asr4all journal instrumentation), voice-mode entry point not separately exercised, and no on-device evaluation beyond this one phrase.
- Device state: S23U mic grant for `ai.jandal.assistant.debug` was given through the in-app dialog, not ADB; still `granted=true`, left unchanged pending Nick. S21 temp WAV `/sdcard/Download/asr4all-stim.wav` removed.
- Criteria 4–5 stay pending #1984.
