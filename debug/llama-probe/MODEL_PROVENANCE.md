# Debug probe model and runtime provenance

The `llamaProbe` debug build type packages this arm64-v8a library in a separate app ID (`com.kernel.ai.debug.llamaprobe`) and uses a test-only `ProbeApplication`; normal debug and release identities/startup remain unchanged. `ProbeIsolatedRunner` suppresses `Application.onCreate` only when the explicit `probeOnly=true` argument is set. The probe instrumentation asserts its package/Application identity, verifies the marker proves `onCreate` was skipped, and refuses to proceed if its package-local `JobScheduler` has pending work. Because Android scopes scheduled jobs to their scheduling application/UID, pre-existing jobs for `com.kernel.ai.debug` cannot be dispatched into this distinct package. No job is disabled, canceled, or rescheduled. No model is bundled.

## Runtime

- Upstream: [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp)
- Pin: [`620d2225cc436ab0b001e3200e590e3b286ef9ab`](https://github.com/ggml-org/llama.cpp/tree/620d2225cc436ab0b001e3200e590e3b286ef9ab), release `v0.7.0`.
- Native constrained decoding: `llama_sampler_init_grammar`; JSON Schema conversion: upstream `common/json-schema-to-grammar.cpp`.
- The native runtime is CPU-only (`n_gpu_layers=0`). No llama.cpp Android GPU capability is claimed.
- Upstream license: MIT (`https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/LICENSE`).

## Model

- Repository: [Qwen/Qwen2.5-1.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF)
- Repository revision: [`91cad51170dc346986eccefdc2dd33a9da36ead9`](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/tree/91cad51170dc346986eccefdc2dd33a9da36ead9)
- File: `qwen2.5-1.5b-instruct-q4_k_m.gguf`
- Size: `1,117,320,736` bytes
- Hugging Face LFS SHA-256: `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`
- License: Apache-2.0; the pinned model card and license are in the repository above. The 1.54B Qwen2.5 Instruct checkpoint is chosen as a small CPU-first GGUF, not as a claim of parity with Gemma E-2B/E-4B.
- Tokenizer/chat template source: [Qwen/Qwen2.5-1.5B-Instruct revision `989aa7980e4cf806f80c7fef2b1adb7bc71aa306`](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct/tree/989aa7980e4cf806f80c7fef2b1adb7bc71aa306), `model_type=qwen2`; its tokenizer configuration specifies the ChatML `<|im_start|>role\n...<|im_end|>` template. The probe formats the two-message system/user prompt with that template.

## Schema constraints

The plan schema follows the parser's dynamic day-index contract and Issue #975's canonical 3–7 day range; `protein_tags` is optional. The comparison request asks for three days so both model paths use the same fixed input.

The recipe schema follows the current `MealPlanJsonParser` and `MealPlanQuantityValidator`: ingredient objects use `original_text`, `amount`, `unit`, and `item`, and `method_steps` are strings. Issue #975 lists `name`, but the current parser does not consume it and instead requires `original_text`; the current source is authoritative. The parser also accepts numeric amounts by converting them to strings, but the pinned llama.cpp schema subset cannot express the canonical string-or-number union. The constrained schema therefore emits numeric amounts as strings. Unsupported schema keywords and type unions are rejected before JNI; the upstream converter never receives a schema this probe cannot validate.

The instrumentation validates generated output through the same application plan parser or recipe parser plus quantity validator. On semantic validation failure it makes one bounded corrective generation; `wall_to_usable_ms` includes the cold load (for the first plan), every generation/retry, and application validation. `plan_and_recipe_wall_ms` starts before model initialization/load and ends after both outputs pass application validation. Failed output has `wall_to_usable_ms:null` and its elapsed `attempt_wall_ms`; per-sample and aggregate retry/repair counters are recorded.

The model file is supplied separately to the debug probe and is not included in the APK or repository.

## Host build and bounded device invocation

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleLlamaProbe :app:assembleLlamaProbeAndroidTest :debug:llama-probe:testDebugUnitTest
```

After confirming APK package IDs, signer and install safety, install only the dedicated `llamaProbe` app/test APKs. Copy (do not move or remove) the existing model files from the normal debug package to the isolated package's external model directory, then copy them into the isolated app's private files directory. The private copies let LiteRT and llama.cpp open models without changing permissions on the original files.

```sh
adb shell mkdir -p /sdcard/Android/data/com.kernel.ai.debug.llamaprobe/files/models
adb shell cp -n /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E4B-it-gpu.litertlm /sdcard/Android/data/com.kernel.ai.debug.llamaprobe/files/models/gemma-4-E4B-it-gpu.litertlm
adb shell cp -n /sdcard/Android/data/com.kernel.ai.debug/files/models/qwen2.5-1.5b-instruct-q4_k_m.gguf /sdcard/Android/data/com.kernel.ai.debug.llamaprobe/files/models/qwen2.5-1.5b-instruct-q4_k_m.gguf
adb shell chmod 644 /sdcard/Android/data/com.kernel.ai.debug.llamaprobe/files/models/gemma-4-E4B-it-gpu.litertlm /sdcard/Android/data/com.kernel.ai.debug.llamaprobe/files/models/qwen2.5-1.5b-instruct-q4_k_m.gguf
adb shell run-as com.kernel.ai.debug.llamaprobe mkdir -p files/models
adb shell run-as com.kernel.ai.debug.llamaprobe cp -n /sdcard/Android/data/com.kernel.ai.debug.llamaprobe/files/models/gemma-4-E4B-it-gpu.litertlm files/models/gemma-4-E4B-it-gpu.litertlm
adb shell run-as com.kernel.ai.debug.llamaprobe cp -n /sdcard/Android/data/com.kernel.ai.debug.llamaprobe/files/models/qwen2.5-1.5b-instruct-q4_k_m.gguf files/models/qwen2.5-1.5b-instruct-q4_k_m.gguf
```

Run the short startup/job-isolation check before any model initialization:

```sh
adb shell am instrument -w \
  -e class 'com.kernel.ai.debug.llamaprobe.LlamaMealPlanComparisonTest#probePackageIsIsolatedFromPersistedAppJobs' \
  -e probeOnly true \
  com.kernel.ai.debug.llamaprobe.test/com.kernel.ai.debug.llamaprobe.ProbeIsolatedRunner
```

Only after that check passes, run the full bounded comparison. It discovers models in the isolated app's private `files/models` directory:

```sh
adb shell am instrument -w \
  -e class com.kernel.ai.debug.llamaprobe.LlamaMealPlanComparisonTest \
  -e probeOnly true \
  com.kernel.ai.debug.llamaprobe.test/com.kernel.ai.debug.llamaprobe.ProbeIsolatedRunner
```

If the LiteRT/Gemma stage is blocked or intentionally not being repeated, run the same bounded GGUF stages independently with `llamaOnly=true`; this skips Gemma model discovery, initialization, and Gemma-only assertions while retaining the Qwen plan/recipe, validation, schema, cancellation/reuse, and unload/reload checks:

```sh
adb shell am instrument -w \
  -e class com.kernel.ai.debug.llamaprobe.LlamaMealPlanComparisonTest \
  -e probeOnly true \
  -e llamaOnly true \
  com.kernel.ai.debug.llamaprobe.test/com.kernel.ai.debug.llamaprobe.ProbeIsolatedRunner
```

Keep prior Gemma measurements/results separate; this mode does not validate or replace the Gemma comparator.

The comparison records only sanitized timing, validation and memory metrics; it does not print generated text. It loads/unloads one model at a time and retains the existing low-memory stop behavior.

Each stage logs a sanitized `MealPlanProbe` start/completion/failure marker with elapsed time; no prompt or generated text is included. Each suspending stage has a five-minute coroutine deadline. After a host-side timeout, inspect `adb logcat -d -s MealPlanProbe`. If instrumentation remains active, stop only the isolated package with `adb shell am force-stop com.kernel.ai.debug.llamaprobe`; never stop the normal debug package. A native call may not observe coroutine cancellation until it returns, so do not rerun until the isolated process has exited.
