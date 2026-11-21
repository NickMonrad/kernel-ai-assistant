# #1988 ExecuTorch INT8 vs ONNX FP32 asr4all spike (benchmark only)

A standalone Gradle project, deliberately separate from the KernelAI build. It installs only the isolated
package `ai.jandal.spike1988.etprobe` (+ `.test`), never `ai.jandal.assistant*`, and changes no production
code, model selection or Auto default. The ONNX lane compiles Jandal's #1987 engine
(`core/voice/.../asr4all/Asr4all{Engine,ExternalData,Metadata,Pcec,Tensors}.kt`) straight out of Git at
`-PonnxEngineCommit` (default `591e82919851bdd77e4490446fe61162f7109eb0`, the PR #1987 head used here).

## Pins

| Artifact | Pin |
|---|---|
| FUTO asr4all S / M / L | `futo-org/asr4all-s@87a6b5d093723fe37e9f69ad26307a3b95a31af0`, `futo-org/asr4all-m@7d6e9145109a5bece4f05831d046e93d27e38a99`, `futo-org/asr4all-l@79d1c23bc8f1174cee576b40bbaa0118bd95abed` |
| ExecuTorch PTE (`executorch/xnnpack_int8/asr_encoder.pte`) SHA-256 | S `7d5679688ca57a7e42d4b42e093b7a29b85d595f454d41cf0aa5f95e7c8c27ec`, M `d08648b914a90669986f943011099fb518783ca0bebb3fd9a4ded53ad5067763`, L `2670068ab08a2a40d76e6cb79d492ce6879d7165c5f6a8defbf2f4994a49f760` |
| ONNX FP32 (`onnx/`) | same revisions; `asr4all.onnx.data` SHA-256 S `9e6b8b11…`, M `f915ab3c…`, L `4d03c883…` |
| FUTO reference drivers | `executorch/asr4all_executorch.py` SHA-256 `0918a4dd55806de15943112314c701527e818fc0a3628c4879ba709bbea997af` (identical in S/M/L), `onnx/asr4all_onnx.py` `f173c6d61a004d8596d6f9b3623716167176639440920a92917f902d5566bba4` |
| Android runtime | Maven `org.pytorch:executorch-android:1.5.1` (tag `3b606839…`), `onnxruntime-android` 1.24.3 |
| Host reference | `executorch==1.5.1` + `torch==2.14.0` (CPU), Python 3.12 |

Model weights: FUTO Model Weights License (Restricted Use) 1.0; not redistributed here. ExecuTorch (BSD-3-Clause)
and ONNX Runtime (MIT) are used as published binaries.

## Licence boundary: the ExecuTorch driver is not in this repository

FUTO's reference drivers (`executorch/asr4all_executorch.py`, `onnx/asr4all_onnx.py` in the asr4all model repos)
carry no software licence; the repos' `LICENSE` (FUTO Model Weights License) covers only the weights and states
that inference code is governed separately. This project therefore publishes no port, translation or close
paraphrase of either script:

- **Host oracles** (`reference_outputs.py`, `frame_agreement.py`, `repro_prior_harness_bug.py`) import FUTO's
  script unmodified at run time from the pinned Hugging Face snapshot and only call or observe it.
- **Device driver and root-cause probes** live in a **private directory outside Git** and are compiled in:

  | Private path (under `-PprivateEtDecoderDir`) | Contents | Needed for |
  |---|---|---|
  | `main/EtAsr4allDecoder.kt`, `main/Asr4allEtGeometry.kt` | Kotlin ExecuTorch driver (stream tier, PCEC `pcec_c16`/`pcec_c16_flush`) and the PTE method geometry it uses | **required**: the build fails without them |
  | `androidTest/StepProbeTest.kt` | first-steps probe under XNNPACK option variants (§1 of the evidence) | optional; compiled when present |
  | `host/CMakeLists.txt`, `host/xnnpack_options_runner.cpp` | `extension::Module` runner used to rule out XNNPACK weight cache / shared workspace (§1) | optional; built separately |

  Default location `~/.local/share/jandal-private/spike-1988-et-decoder` (outside every checkout, so never
  tracked). The published results were produced with these files as they stood at #2002 commit `53afb5a8`
  (file SHA-256s in [`docs/evidence/1988/README.md`](../../docs/evidence/1988/README.md#licence-note)).

The public code here is Jandal's own: lane plumbing (`StreamingLane.kt`), XNNPACK load options (`EtModules.kt`),
WAV reading, pacing, timing, memory sampling, device orchestration and scoring.

## Contents

- `StreamingLane.kt`: lanes behind one interface: `et` (ExecuTorch, via the private driver), `onnx` (#1987 engine
  as-is), `onnxt4` (ORT control: the same #1987 source generated into package `...asr4all.tuned` with only
  `openSession` changed to 4 intra-op threads, arena + prepacking enabled). `BenchmarkTest` (one lane per process:
  load, PSS/VmHWM, paced 100 ms feed with per-chunk lag/backlog, first partials, flushed finals), `ParityTest`
  (public clips, frame ids + top-2 margins).
- `scripts/`: `run_device.sh` (lock, stage, run, pull, clean, release; phases `parity,bench,breadth,long,control,
  retry`; `UNINSTALL=1` removes the probe packages at the end), `device-lock.sh`,
  `reference_outputs.py` / `check_parity.py` / `frame_agreement.py` (host oracle and parity; run the oracle under
  qemu-user aarch64 for the ARM-kernel comparison), `repro_prior_harness_bug.py` (minimal root-cause
  reproduction), `score.py` (WER and aggregates), `report_table.py` / `control_table.py` (aggregate tables).

## Root cause of the earlier "Android divergence"

The earlier harness indexed logits with `frame * vocab.size` and argmaxed over `vocab.indices`; metadata
`vocab` has 256 entries but logits rows have 257 (blank id 256). `scripts/repro_prior_harness_bug.py`
applies that indexing to correct host logits and reproduces the reported device strings exactly.

## Running

```sh
# host oracle (public clips, 16 kHz mono PCM16)
python scripts/reference_outputs.py <hf>/asr4all-s/snapshots/<rev>/executorch/xnnpack_int8 stream_c16r4,stream_c32r8 *.wav
# build (needs local.properties with sdk.dir, and the private driver: see "Licence boundary")
../../gradlew -p . :probe:assembleDebug :probe:assembleDebugAndroidTest \
    -PprivateEtDecoderDir=$HOME/.local/share/jandal-private/spike-1988-et-decoder   # default location
# one locked device session; private results directory must be outside Git
scripts/run_device.sh s23u <agent-id> <private-dir> parity,bench,breadth
python scripts/score.py <private-dir> phrases.json breadth-phrases.json
```

Owner audio and transcripts stay in app-private storage during the run (removed by `run_device.sh`) and in
the private host directory; only aggregates are published.
