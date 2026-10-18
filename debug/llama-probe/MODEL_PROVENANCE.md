# Debug probe model and runtime provenance

This arm64-v8a **debug-only** library is included in the Jandal app debug variant only; the release variant has no dependency on it and no model is bundled. The app AndroidJUnitRunner skips `KernelAIApplication.onCreate` only for the explicit `probeOnly=true` instrumentation argument, leaving ordinary instrumentation tests unchanged.

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

The probe derives its two-day plan and recipe schemas from the existing `MealPlanJsonParser`, `MealPlanQuantityValidator`, and `MealPlannerCoordinator` prompt contracts. It uses only object/array/string/integer nodes, `properties`, `required`, `items`, `additionalProperties:false`, item/length bounds, and integer minimum/maximum. Each schema is checked against that allowlist before calling the upstream converter. Unsupported keywords (including `uniqueItems`, `pattern`, refs, unions, and conditionals) are rejected; the converter is never allowed to silently relax one of these schemas. Generated strings are then passed through the existing application parser and quantity validator.

The model file is supplied separately to the debug probe and is not included in the APK or repository.

## Host build and bounded device invocation

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :debug:llama-probe:testDebugUnitTest
adb shell mkdir -p /sdcard/Android/data/com.kernel.ai.debug/files/models
adb push qwen2.5-1.5b-instruct-q4_k_m.gguf /sdcard/Android/data/com.kernel.ai.debug/files/models/
adb shell am instrument -w \
  -e class com.kernel.ai.debug.llamaprobe.LlamaMealPlanComparisonTest \
  -e probeOnly true \
  com.kernel.ai.debug.test/com.kernel.ai.debug.llamaprobe.ProbeIsolatedRunner
```

The test finds an existing E-4B or E-2B file under the target app's private `files/models` directory, and the GGUF under app-specific external `files/models`. It records only sanitized timing, validation and memory metrics; it does not print generated text. The test loads/unloads one model at a time and stops if Android reports `ActivityManager.MemoryInfo.lowMemory`.
