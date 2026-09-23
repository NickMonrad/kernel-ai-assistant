# Arctic Embed M v1.5 Phase A spike

This directory contains pinned Phase A conversion/fidelity tooling and a Phase B retrieval-calibration evaluator. The tools do not modify production code, schemas, thresholds, indexes, or model files.

## Reproduce conversion and desktop comparison

Requirements are pinned by `pyproject.toml` and `uv.lock` (Python 3.12):

```sh
uv sync --project tools/arctic_phase_a
uv run --project tools/arctic_phase_a python tools/arctic_phase_a/run.py \
  --model-dir tools/arctic_phase_a/.cache/arctic \
  --artifact /tmp/arctic_m_v1_5_int8.tflite \
  --report /tmp/arctic_phase_a_fidelity.json \
  --device-fixtures /tmp/arctic_phase_a_device_fixtures.json
```

The runner resolves `Snowflake/snowflake-arctic-embed-m-v1.5` at commit `e58a8f756156a1293d763f17e3aae643474e9b8a`, obtains tokenizer and weights from that immutable revision, exports the CLS-plus-L2-normalization wrapper with LiteRT Torch, applies the checked-in AI Edge Quantizer symmetric channel-wise 8-bit weight recipe, and scores the artifact against the pinned SentenceTransformers/PyTorch model. Model files and generated `.tflite` binaries are local outputs; do not commit them.

The Android instrumentation benchmark in `app/src/androidTest/.../ArcticLiteRtDeviceBenchmarkTest.kt` consumes the generated model and fixture JSON from `/data/local/tmp`. It runs through Jandal's existing TensorFlow Lite Interpreter dependency; it is test-only and does not wire into production DI or model distribution.

## Historical on-device EmbeddingGemma comparison

Phase A's same-device EmbeddingGemma comparison is preserved in [`docs/research/1559-arctic-phase-a-evidence.md`](../../docs/research/1559-arctic-phase-a-evidence.md). Phase B removes the active EmbeddingGemma metadata and SentencePiece tokenizer, so the test-only EmbeddingGemma benchmark and its gated local inputs are no longer in the current instrumentation source. The Arctic candidate benchmark remains available for reference-parity and runtime measurements.

## Phase B cosine retrieval calibration

The report records per-consumer cosine-distance cutoffs, held-out scores, and dataset limits. Calibration remains exploratory: message/core/episodic scores use six-query held-out sets, Kiwi uses 25 labeled queries for 144 entries, and core has substantial false positives. Production release sign-off remains held until the SHA-pinned assets are served from an immutable GitHub release.
