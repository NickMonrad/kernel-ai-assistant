# Arctic Embed M v1.5 Phase A spike

This directory contains conversion and fidelity tooling only. It does not alter the production embedding engine, model catalogue, vector schema, retrieval thresholds, or stored vectors.

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

## Compare the current gated production baseline on-device

The test-only `benchmarkGenericProductionEmbeddingGemmaAgainstSameTextInputs` case benchmarks the generic mixed-precision model listed as `KernelModel.EMBEDDING_GEMMA_300M`, not the deprecated SM8550-specific variant. It uses the matching `sentencepiece.model` and the same five text cases as the Arctic device fixture; tokenization and input preparation occur before timing. Each run uses TFLite Interpreter 2.17.0, CPU, four threads, and reports load/allocation, first and steady inference, loaded/peak PSS, RSS high-water, and model-only memory deltas. The instrumentation test does not modify production DI or model files.

After authenticating locally to the gated Hugging Face repository, retrieve both files at the exact revision recorded in the evidence report. Do not place a token in commands, files, or logs. Push the downloaded model and tokenizer, plus the generated candidate and shared fixture JSON, to `/data/local/tmp` on each device. Run the baseline and candidate test methods in separate instrumentation invocations on S23 Ultra and S21 so each process has an independent RSS high-water baseline:

```sh
ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.kernel.ai.ArcticLiteRtDeviceBenchmarkTest#benchmarkGenericProductionEmbeddingGemmaAgainstSameTextInputs
ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.kernel.ai.ArcticLiteRtDeviceBenchmarkTest#benchmarkPinnedCandidateAgainstReferenceInputs
```
