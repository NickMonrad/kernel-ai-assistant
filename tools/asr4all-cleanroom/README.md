# asr4all host harness (#2004)

Runs the Kotlin asr4all engine (`core/voice/.../asr4all/`) on a desktop JVM against FUTO's real
ExecuTorch exports, and writes the partial-text sequence and final text for every clip × size ×
tier as JSON lines. A separate verifier compares these rows with reference outputs; nothing here
reads or produces reference outputs.

The engine runs unmodified. Only the runtime differs from the app: `et_bridge.py` loads the
`.pte` with the official `executorch` Python package and executes the methods the engine asks
for (a black-box loader with no asr4all logic), through `PythonBridgeProgram` in the
`core:voice` unit-test source set.

## Setup

```bash
uv venv -p 3.12 .venv
uv pip install -p .venv/bin/python "executorch==1.5.1" numpy
uv pip install -p .venv/bin/python "torch==2.14.*" --index-url https://download.pytorch.org/whl/cpu
```

Download each size's files by exact path at the pinned revision (see
`docs/evidence/2004/CLEANROOM.md`), keeping the repository layout:

```
<model dir>/config.json
<model dir>/release_geom.json
<model dir>/preprocessor_config.json
<model dir>/executorch/xnnpack_int8/metadata.json
<model dir>/executorch/xnnpack_int8/asr_encoder.pte
```

## Run

Write a config (relative paths resolve against the config file's directory):

```json
{
  "python": "/path/to/.venv/bin/python",
  "bridge": "/path/to/repo/tools/asr4all-cleanroom/et_bridge.py",
  "models": {"S": "models/asr4all-s", "M": "models/asr4all-m", "L": "models/asr4all-l"},
  "sizes": ["S", "M", "L"],
  "tiers": ["all"],
  "chunk_ms": 100,
  "repeat": 2,
  "clips": [{"id": "1272-128104-0000", "wav": "clips/1272-128104-0000.wav"}],
  "output": "out/engine.jsonl"
}
```

Clips must be 16-bit mono little-endian PCM WAV at 16 kHz. Then:

```bash
ANDROID_HOME=$HOME/Android/Sdk ASR4ALL_HARNESS_CONFIG=/path/to/config.json \
  ./gradlew :core:voice:testDebugUnitTest --tests '*Asr4AllHostHarnessTest*'
```

Without `ASR4ALL_HARNESS_CONFIG` the harness test is skipped, so CI never needs models.

## Output

One JSON object per line:

| field | meaning |
|---|---|
| `clip`, `wav_sha256` | clip id and the SHA-256 of the WAV file as read |
| `size`, `tier` | `S`/`M`/`L` and the export tier (`stream_c16r4`, …) |
| `chunk_samples` | samples per `acceptPcm16` call |
| `partials` | the engine's partial text each time it changed, in order |
| `partial_sample_offsets` | samples fed when each partial appeared |
| `final` | the text returned by `finish()` |
| `repeats_identical` | how many runs produced identical partials and final (the harness fails otherwise) |

The final text does not depend on `chunk_ms`; partials are recomputed after each encoder window
(every `commit` frames of 40 ms), so their timing does.
