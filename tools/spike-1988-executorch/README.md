# #1988 ExecuTorch INT8 vs ONNX FP32 asr4all spike (benchmark only)

A standalone Gradle project, deliberately separate from the KernelAI build. It installs only the isolated
package `ai.jandal.spike1988.etprobe` (+ `.test`), never `ai.jandal.assistant*`, and changes no production
code, model selection or Auto default. The ONNX lane compiles Jandal's #1987 engine
(`core/voice/.../asr4all/Asr4all{Engine,ExternalData,Metadata,Pcec,Tensors}.kt`) straight out of Git at
`-PonnxEngineCommit` (default `591e82919851bdd77e4490446fe61162f7109eb0`, the PR #1987 head used here).

## Pins

| Artifact | Pin |
|---|---|
| FUTO asr4all S / M / L | `futo-org/asr4all-s@87a6b5d0…`, `-m@7d6e9145…`, `-l@79d1c23b…` (full SHAs in `Asr4allEtGeometry.kt`) |
| ExecuTorch PTE (`executorch/xnnpack_int8/asr_encoder.pte`) SHA-256 | S `7d567968…c27ec`, M `d08648b9…67763`, L `2670068a…f760` |
| ONNX FP32 (`onnx/`) | same revisions; `asr4all.onnx.data` SHA-256 S `9e6b8b11…`, M `f915ab3c…`, L `4d03c883…` |
| FUTO reference drivers | `executorch/asr4all_executorch.py` SHA-256 `0918a4dd…` (identical in S/M/L), `onnx/asr4all_onnx.py` `f173c6d6…` |
| Android runtime | Maven `org.pytorch:executorch-android:1.5.1` (tag `3b606839…`), `onnxruntime-android` 1.24.3 |
| Host reference | `executorch==1.5.1` + `torch==2.14.0` (CPU), Python 3.12 |

Model weights: FUTO Model Weights License (Restricted Use) 1.0; not redistributed here. ExecuTorch (BSD-3-Clause)
and ONNX Runtime (MIT) are used as published binaries.

## Contents

- `probe/src/main/.../EtAsr4allDecoder.kt`: Kotlin port of FUTO's `asr4all_executorch.py` (stream tier +
  `pcec_c16`/`pcec_c16_flush`). Logits rows are 257 wide (256 vocab + blank); see "Root cause" below.
- `StreamingLane.kt`: ET and ONNX lanes behind one interface; `BenchmarkTest` (one lane per process: load,
  PSS/VmHWM, paced 100 ms feed with per-chunk lag/backlog, first partials, flushed finals), `ParityTest`
  (public clips, frame ids + top-2 margins), `StepProbeTest` (first steps under XNNPACK option variants).
- `scripts/`: `run_device.sh` (lock, stage, run, pull, clean, release), `device-lock.sh`,
  `reference_outputs.py` / `check_parity.py` / `frame_agreement.py` (host oracle and parity),
  `repro_prior_harness_bug.py` (minimal root-cause reproduction), `score.py` (WER and aggregates).
- `host/`: `extension::Module` runner used to rule out XNNPACK weight-cache / shared-workspace options.

## Root cause of the earlier "Android divergence"

The earlier harness indexed logits with `frame * vocab.size` and argmaxed over `vocab.indices`; metadata
`vocab` has 256 entries but logits rows have 257 (blank id 256). `scripts/repro_prior_harness_bug.py`
applies that indexing to correct host logits and reproduces the reported device strings exactly.

## Running

```sh
# host oracle (public clips, 16 kHz mono PCM16)
python scripts/reference_outputs.py <hf>/asr4all-s/snapshots/<rev>/executorch/xnnpack_int8 stream_c16r4,stream_c32r8 *.wav
# build (needs local.properties with sdk.dir)
../../gradlew -p . :probe:assembleDebug :probe:assembleDebugAndroidTest
# one locked device session; private results directory must be outside Git
scripts/run_device.sh s23u <agent-id> <private-dir> parity,bench,breadth
python scripts/score.py <private-dir> phrases.json breadth-phrases.json
```

Owner audio and transcripts stay in app-private storage during the run (removed by `run_device.sh`) and in
the private host directory; only aggregates are published.
