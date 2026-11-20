# ExecuTorch runtime (`:core:executorch`)

Jandal integrates ExecuTorch once. `:core:executorch` owns the dependency, its version and its
packaging rules; every ExecuTorch consumer (asr4all STT, a future LLM) goes through it.
Owner decision, 21 Nov 2026: asr4all STT moves to ExecuTorch (XNNPACK INT8 `.pte` models), and
the LLM work in #1755 must reuse the same runtime. Related: #1977 (asr4all epic), #1755.

## Ownership and versioning

- `gradle/libs.versions.toml` declares `executorch = "1.5.1"` and `libs.executorch.android`
  (`org.pytorch:executorch-android`). Only `core/executorch/build.gradle.kts` may reference it.
  Never add a second ExecuTorch artifact (Maven or a local AAR) or a second version.
- The dependency is `implementation`, so the raw `org.pytorch.executorch` API does not leak.
  Consumers depend on `project(":core:executorch")` and use its Kotlin API.
- One AAR covers both use cases. The 1.5.1 AAR (BSD-3-Clause, published from
  `pytorch/executorch` per its Android docs) contains `org.pytorch.executorch.Module` (generic
  programs) and `org.pytorch.executorch.extension.llm.LlmModule` (LLM runner, XNNPACK,
  quantized and LLM custom ops) in one `libexecutorch.so`. #1755 needs no second AAR.
- Transitive runtime dependencies: `com.facebook.fbjni:fbjni:0.7.0` (`libfbjni.so`,
  `libc++_shared.so`) and `com.facebook.soloader:nativeloader:0.10.5`. Licences are in
  `NOTICE` and `docs/LEGAL_AND_ATTRIBUTION.md`.
- `.pte` compatibility: the runtime accepts programs with the `ET12` flatbuffer identifier.
  Export models with the PyPI `executorch` release of the same version, and the torch version
  that release pins (`torch_pin.py` at the tag; 2.14.0 for 1.5.1).

### Upgrading

Change the version in one place, then re-check everything this document states about 1.5.1:
the AAR contents (`Module`, `LlmModule`), the thread-pool behaviour, the native error path,
`PteHeader.PROGRAM_IDENTIFIER`, the 16 KB checks and the APK size table. Regenerate the smoke
program with `core/executorch/tools/generate_smoke_pte.py` and run the instrumented test.

## API

```kotlin
val encoder = MethodSignature(
    "forward",
    inputs = listOf(TensorSpec("features", TensorDType.FLOAT32, 1, TensorSpec.ANY, 80)),
    outputs = listOf(TensorSpec("logits", TensorDType.FLOAT32, 1, TensorSpec.ANY, 1025)),
)
ExecuTorchModel.load(file, threads = 4).use { model ->
    val logits = model.run(encoder, listOf(ExecuTorchTensor.Float32(data, longArrayOf(1, frames, 80))))
}
```

- `ExecuTorchModel.load(file, threads, loadMode = MMAP)` takes an explicit thread count; there is
  no "auto" value.
- `run(signature, inputs)` checks the inputs against the signature before native code and every
  output after it. ExecuTorch's Java API has no shape metadata, so the signature is how a caller
  states what the program must return; a mismatch throws `OutputShapeMismatchException` (or
  `InputShapeMismatchException`) naming the method, the tensor and both shapes. `assertOutputShapes`
  and `requireInputShapes` are public for callers that hold tensors from elsewhere.
- Tensors: `ExecuTorchTensor.Float32`, `Int32`, `Int64`, `Int8`, `UInt8`. Construction fails if
  the data does not fill the shape.
- Lifecycle: `close()` is idempotent and waits for an in-flight `run`; `run` after `close`
  throws `IllegalStateException` and never reaches freed native memory. Calls are serialised per
  model and all block: call from a background dispatcher, not the main thread.

## Runtime constraints (1.5.1)

### Native errors abort the process

On Android, every runtime error path in the 1.5.1 AAR (an invalid or truncated program at load,
an input the program rejects at run) aborts the process with `F log: ptr` / SIGABRT instead of
throwing `ExecutorchRuntimeException`. Observed on the S21 (`R5CR605B71K`) with this module's
instrumented test. Cause: `libexecutorch.so` links libc++ statically and hides it (12 exported
dynamic symbols, no `__cxa_*`), while fbjni throws through `libc++_shared.so`. The exception
crosses C++ runtimes, `std::current_exception()` returns null, and fbjni's `FBJNI_ASSERT(ptr)`
aborts. Upstream pull request [pytorch/executorch#22739](https://github.com/pytorch/executorch/pull/22739)
(dynamic libc++) describes this failure mode; it is not in 1.5.1.

Mitigation in this module: reject what Kotlin can check before native code.

- `PteHeader` checks the `ET12` identifier and that the file holds the bytes its extended header
  declares (catches HTML error pages, wrong files and truncated downloads).
- `MethodSignature.inputs` is checked before every run.
- Consumers that download models must verify the SHA-256 before `load`.

A program whose real input shapes differ from the declared signature still aborts the process.

### One process-wide thread pool

ExecuTorch has one XNNPACK thread pool per process. Each `Module` load resets it to the
requested count, and each `LlmModule` construction resets it to (performant cores - 1). A
reset that changes the count destroys the old `pthreadpool`, but each loaded XNNPACK delegate
keeps the raw pointer it captured at init (`backends/xnnpack/runtime/XNNCompiler.cpp`): changing
the count while another model is open is a use-after-free.

`ExecuTorchThreadPool` enforces: while any model is open, every load uses the same thread count;
a conflicting load throws `IllegalStateException` before reaching native code. Loads are
serialised. Consequences:

- asr4all and an LLM that are open at the same time must agree on one count. Because 1.5.1's
  `LlmModule` always picks (performant cores - 1), STT would have to use that count, or close its
  models before the LLM loads.
- An `LlmModule` wrapper (#1755) belongs in this module and must construct `LlmModule` inside
  `ExecuTorchThreadPool.load(performantCores - 1) { ... }` and release it on close.

### CPU baseline

The 1.5.1 arm64 library is built with `-march=armv8.2-a+dotprod` flags leaking from its LLM
custom ops, so it raises SIGILL on Armv8.0 CPUs without LSE atomics
([pytorch/executorch#25162](https://github.com/pytorch/executorch/issues/25162)). The S21 and
S23U are Armv8.2+. Any device-compatibility claim for an ExecuTorch feature must account for it.

## Consumers

- **asr4all STT** (clean-room engine, separate work): depends on `:core:executorch`, declares a
  `MethodSignature` per exported method, picks its thread count explicitly, verifies model
  SHA-256 before `load`, and closes models when STT stops.
- **LLM (#1755)**: adds an `LlmModule` wrapper inside this module (same AAR, no new dependency)
  that follows the thread-pool rule above and the same pre-validation approach.

## Coexistence with ONNX Runtime

ONNX Runtime stays: it runs openWakeWord (`OnnxWakeWordDetector`, `:core:voice`) and
sherpa-onnx (`libsherpa-onnx-jni.so` linked against the app's `libonnxruntime.so`). ExecuTorch
and ONNX Runtime share no native libraries, symbols or thread pools, so they load side by side.
The shared native file is `libc++_shared.so`; see packaging.

## Packaging

- ExecuTorch ships `arm64-v8a` and `x86_64` only. In `app/build.gradle.kts`:
  - `pickFirsts` for `lib/{arm64-v8a,x86_64}/libc++_shared.so`: fbjni 0.7.0 (NDK r27b) and
    `:core:memory`'s `libkernelvec` (NDK r28c) both ship it. The packaged copy is the r28c one
    (verified by hash; libc++ is backward compatible with older-NDK binaries).
  - `excludes` for fbjni's `armeabi-v7a`/`x86` `libfbjni.so` and `libc++_shared.so`: no 32-bit
    library uses them, and there is no 32-bit `libexecutorch.so`. On a 32-bit-only device
    `ExecuTorchModel.load` throws `ExecuTorchException` (runtime unavailable).
  - `libfbjni.so` has no other provider today, so it needs no pick rule.
- 16 KB pages: `libexecutorch.so`, `libfbjni.so` and `libc++_shared.so` use 16 KB `PT_LOAD`
  alignment and pass `scripts/check_android_16kb_artifacts.py` (debug, release, androidTest APKs
  and the release AAB).
- R8: `core/executorch/consumer-rules.pro` keeps `org.pytorch.executorch.**` and
  `com.facebook.jni.**`. The AAR's own rules let R8 rename `DType` and the `Module`/`EValue`
  companions, and miss fbjni's `HybridData$Destructor`, which aborts minified apps
  ([pytorch/executorch#22174](https://github.com/pytorch/executorch/issues/22174)).
  `verifyReleaseJniKeepRules` now guards both packages.
- The ExecuTorch AAR manifest declares its own test `<instrumentation>` (target
  `org.pytorch.executorch`). `core/executorch/src/main/AndroidManifest.xml` removes it, so it
  does not reach the app manifest.

## APK size impact

Measured 21 Nov 2026 against `main` at `00025895`, before and after this module was added to
`:app` (release build; per-ABI download from `bundletool get-size total --dimensions=ABI` on the
release AAB, MAX column):

| ABI | Native libs added (installed, uncompressed) | Play download per ABI |
| --- | --- | --- |
| arm64-v8a | +9,234,072 B (`libexecutorch.so` 9,057,072 + `libfbjni.so` 177,000) | 48,239,102 → 51,620,741 B (+3.38 MB) |
| x86_64 | +12,227,424 B (`libexecutorch.so` 12,054,472 + `libfbjni.so` 172,952) | 52,153,502 → 56,454,329 B (+4.30 MB) |
| armeabi-v7a, x86 | none (fbjni's 32-bit copies are excluded) | +43 KB (dex only) |

`libc++_shared.so` is unchanged (the packaged copy is byte-identical to the baseline). Release dex
grows by 104,624 B compressed. A universal APK (the tester APK, debug and PR-preview APKs) carries
both 64-bit ABIs: the release universal APK grows from 234,147,970 to 255,726,222 B
(+21.58 MB), of which x86_64 is 12.2 MB.

### x86_64 options (owner decision; no `abiFilters` change yet)

x86_64 matters only for emulators and x86 Chromebooks; Play's per-ABI splits already keep it off
phones. The cost lands on universal APKs.

1. **Keep as is.** ExecuTorch works on x86_64 emulators (useful for local instrumented tests);
   universal APKs pay +12.2 MB.
2. **Exclude only ExecuTorch's x86_64 libraries** (`lib/x86_64/libexecutorch.so`,
   `lib/x86_64/libfbjni.so`) in `packaging.jniLibs.excludes`. Saves 12.2 MB in universal APKs;
   on x86_64, `ExecuTorchModel.load` throws `ExecuTorchException` and the STT engine must fall
   back. The instrumented smoke test would need an arm64 device.
3. **Per-ABI split APKs for sideloaded builds** (`splits.abi`). Keeps every ABI working and
   removes the cost from arm64 sideloads; changes the tester/preview publishing (several APKs).
4. **`abiFilters "arm64-v8a"` for the tester/preview APKs only.** Largest saving (all x86_64,
   x86 and armeabi-v7a libraries, not just ExecuTorch); drops emulator support for those APKs.
