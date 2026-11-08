# Feature-pipeline parity artifacts — #1432

`WakeWordFeaturePipelineParityTest` proves the Android production wake feature
pipeline (`OnnxWakeWordDetector` Stage 1/2/3) produces the same tensors as the
Python/openWakeWord reference pipeline that generated the committed fixture
streams and trained the committed classifier.

## Provenance

- **Reference implementation:** openwakeword 0.4.0 `AudioFeatures` streaming
  (`_streaming_features` / `_streaming_melspectrogram`), installed at
  `~/.venvs/acoustic-runner/lib/python3.14/site-packages/openwakeword` (pip
  dist-info `openwakeword-0.4.0`), onnxruntime 1.24.3, numpy 2.5.1, CPU
  execution.
- **Version stability:** the training requirements pin `openwakeword>=0.6.0`
  from source, but the streaming feature logic is identical in 0.4.0 and
  0.6.0 for exact 1280-sample chunks (verified by source diff of
  `_streaming_features`, `_streaming_melspectrogram`, `_get_melspectrogram`:
  the mel model is always fed the last `n + 480` buffered samples and one
  embedding is computed per chunk from the last 76 mel rows).  The committed
  fixture streams were generated with 0.4.0 (the installed venv version whose
  `AudioFeatures` kwarg names match `generate_wake_embeddings.py`).
- **Models:** the exact committed app assets
  `app/src/main/assets/models/wakeword/` (hashes below), executed on CPU by
  both runtimes.
- **PCM framing:** the canonical 16 kHz mono int16 stream is chunked at 1280
  samples; the reference feeds the mel model the current chunk plus the
  previous chunk's 480-sample tail (1760 samples → 8 mel rows), except the
  very first chunk (1280 samples → 5 rows).  Initial state: empty raw-sample
  buffer, 76-row ones mel pre-fill, cleared feature buffer (the same initial
  state as `generate_wake_embeddings.py`).

## What was fixed

The production detector previously fed the mel model exactly 1280 samples per
chunk and appended only the 5 output rows, so the 3 boundary rows per chunk
(over the previous chunk's tail) were never computed.  The 76-row mel ring
advanced 5 rows per chunk instead of the reference 8, so every Stage-2 input
patch, every embedding and every classifier window was structurally different
from the training distribution (missing 37.5% of the 160-hop mel grid).
`OnnxWakeWordDetector` now feeds the 1760-sample streaming window, appends all
8 rows (5 for the first chunk) and slides the ring by the exact overflow, so
the ring is a capacity-76 sliding window over the same mel-row stream the
reference computes.

## Artifacts

| File | Contents |
|---|---|
| `parity_pcm.bin` | deterministic 4 s int16 mono 16 kHz PCM (64000 samples): silence, seeded xorshift32 noise, fixed 440/880/1320 Hz tones, a 2 kHz chirp burst, a loud noise transient, trailing silence. Privacy-safe; no speech content; does not approximate any private recording. SHA-256 `75e0163e01d997a6046c042de1322231d3531060b4d307bb4a6a83a89c5ee5c8` |
| `parity_reference.json` | per-chunk reference checkpoints: mel input length, mel rows appended (5 then 8), the mel rows, the complete 76×32 Stage-2 window, the 96-dim embedding, and the classifier confidence over the last-16-embedding window |
| `generate_parity_reference.py` | the generator (synthesizes the PCM, runs the reference, verifies model hashes) |

## Model hashes (SHA-256, verified by the test and the generator)

| Model | SHA-256 |
|---|---|
| melspectrogram.onnx | `ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f` |
| embedding_model.onnx | `70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f` |
| hey_jandal.onnx | `11bcdb0d800b3a93449197122bd9fb484c4b8db887364c629f6c975e3e38c206` |

## Measured parity

Post-fix, the JVM production pipeline (the exact `OnnxWakeWordDetector`
functions) reproduces the Python reference **bit-exactly** on the committed
stream: max abs diff 0.0 for mel rows, ring windows, embeddings and classifier
confidence across all 50 chunks.  The committed test tolerances (1e-4 / 1e-4 /
1e-5) are headroom only.

Pre-fix, the first divergence is at **chunk 2**: the mel model input is 1280
samples and 5 rows are appended instead of the reference 8 (measured ring
window divergence ~17.4, embedding divergence ~18.1 — structural, not float
noise).

## Regenerating

```sh
python3 core/voice/src/test/resources/wake-embeddings/generate_parity_reference.py \
  --model-dir app/src/main/assets/models/wakeword \
  --out core/voice/src/test/resources/wake-embeddings
```

Requires the reference venv (`~/.venvs/acoustic-runner`): openwakeword 0.4.0,
onnxruntime, numpy.  The artifacts must only be regenerated with the same
reference version, and the PCM hash must not change.
