# Debug probe model and runtime provenance

The `llamaProbe` debug build type packages this arm64-v8a library in a separate app ID (`com.kernel.ai.debug.llamaprobe`) and uses a test-only `ProbeApplication`; normal debug and release identities/startup remain unchanged. `ProbeIsolatedRunner` suppresses `Application.onCreate` only when the explicit `probeOnly=true` argument is set. The probe instrumentation asserts its package/Application identity, verifies the marker proves `onCreate` was skipped, and refuses to proceed if its package-local `JobScheduler` has pending work. Because Android scopes scheduled jobs to their scheduling application/UID, pre-existing jobs for `com.kernel.ai.debug` cannot be dispatched into this distinct package. No job is disabled, canceled, or rescheduled. No model is bundled.

## Runtime

- Upstream: [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp)
- Pin: [`620d2225cc436ab0b001e3200e590e3b286ef9ab`](https://github.com/ggml-org/llama.cpp/tree/620d2225cc436ab0b001e3200e590e3b286ef9ab), release `v0.7.0`.
- Native constrained decoding: `llama_sampler_init_grammar`; JSON Schema conversion: upstream `common/json-schema-to-grammar.cpp`.
- The native runtime is CPU-only (`n_gpu_layers=0`). No llama.cpp Android GPU capability is claimed.

The Debug native build adds `-O3` to every C/C++ target in this CMake tree, including fetched llama.cpp/ggml, while retaining the Android Debug configuration and its debug information. This follows the verified Box SmolLM build at [`f27b78d`](https://github.com/jegly/Box/blob/f27b78d3a86b5de0e94ef0bfc8634f5410d6f8eb/Android/src/smollm/build.gradle.kts), which selects CMake Release; [its CMake targets](https://github.com/jegly/Box/blob/f27b78d3a86b5de0e94ef0bfc8634f5410d6f8eb/Android/src/smollm/src/main/cpp/CMakeLists.txt) also use `-O3` for native wrappers. This is a compiler-configuration comparison, not measured performance evidence; the probe remains CPU-only and the existing cancellation/teardown instrumentation is unchanged.
- Upstream license: MIT (`https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/LICENSE`).

## Model

- Repository: [LiquidAI/LFM2.5-2.6B-GGUF](https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF)
- Repository revision: [`e7caca5d835a3901a8e0d63e94009429bafafdfc`](https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF/tree/e7caca5d835a3901a8e0d63e94009429bafafdfc)
- File: `LFM2.5-2.6B-Q4_K_M.gguf`
- Size: `1,674,455,040` bytes
- Hugging Face LFS SHA-256: `02a8b7e17487d326e46d68ce0ba24211e1b80a14c4cd0597fa73c1cd697f52ed`
- License: LFM Open License v1.0; commercial use is not granted to entities with annual revenue of at least USD 10 million. See the pinned model card and [license](https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF/blob/e7caca5d835a3901a8e0d63e94009429bafafdfc/LICENSE).
- Runtime/template compatibility: the pinned llama.cpp v0.7.0 revision includes `LLM_ARCH_LFM2` and the `lfm2` tokenizer pre-tokenizer. Its public chat-template API detects this GGUF's ChatML body but omits the model's `<think>` generation suffix. The probe calls `llama_model_chat_template` and `llama_chat_apply_template`, accepts only the pinned LFM2.5 BOS/ChatML/`<think>` template signature and exact formatted two-message body, then adds the metadata-verified `<think>` suffix; other template shapes fail clearly. Tokenization enables the model BOS and special-token parsing. This is source-level compatibility only; no model file was downloaded or executed in this host check.
- The pinned GGUF is a 2.69B-parameter LFM2.5 checkpoint with 128K trained context; the probe deliberately caps runtime context at 4096 tokens for this bounded comparison.

## Schema constraints

The plan schema follows the parser's dynamic day-index contract and Issue #975's canonical 3–7 day range; `protein_tags` is optional. The comparison request asks for three days so both model paths use the same fixed input.

The recipe schema follows the current `MealPlanJsonParser` and `MealPlanQuantityValidator`: ingredient objects use `original_text`, `amount`, `unit`, and `item`, and `method_steps` are strings. Issue #975 lists `name`, but the current parser does not consume it and instead requires `original_text`; the current source is authoritative. The parser also accepts numeric amounts by converting them to strings, but the pinned llama.cpp schema subset cannot express the canonical string-or-number union. The constrained schema therefore emits numeric amounts as strings. Unsupported schema keywords and type unions are rejected before JNI; the upstream converter never receives a schema this probe cannot validate.

The instrumentation validates generated output through the same application plan parser or recipe parser plus quantity validator. On semantic validation failure it makes one bounded corrective generation; `wall_to_usable_ms` includes the cold load (for the first plan), every generation/retry, and application validation. `plan_and_recipe_wall_ms` starts before model initialization/load and ends after both outputs pass application validation. Failed output has `wall_to_usable_ms:null` and its elapsed `attempt_wall_ms`; per-sample and aggregate retry/repair counters are recorded.

The model file is supplied separately to the debug probe and is not included in the APK or repository.

## Host build and bounded device invocation

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleLlamaProbe :app:assembleLlamaProbeAndroidTest :debug:llama-probe:testDebugUnitTest
```

After confirming APK package IDs, signer and install safety, install only the dedicated `llamaProbe` app/test APKs. Do not read or modify the normal debug app's data. Stage the separately checksum-verified model from the host into the isolated package's external model directory, then copy it into the isolated app's private files directory. The probe only reads the isolated copy.

```sh
# Set LFM2_5_GGUF to the host path of the pinned file verified against MODEL_PROVENANCE.md.
echo "02a8b7e17487d326e46d68ce0ba24211e1b80a14c4cd0597fa73c1cd697f52ed  $LFM2_5_GGUF" | sha256sum --check
adb shell mkdir -p /sdcard/Android/data/com.kernel.ai.debug.llamaprobe/files/models
adb push "$LFM2_5_GGUF" /sdcard/Android/data/com.kernel.ai.debug.llamaprobe/files/models/LFM2.5-2.6B-Q4_K_M.gguf
adb shell run-as com.kernel.ai.debug.llamaprobe mkdir -p files/models
adb shell run-as com.kernel.ai.debug.llamaprobe cp -n /sdcard/Android/data/com.kernel.ai.debug.llamaprobe/files/models/LFM2.5-2.6B-Q4_K_M.gguf files/models/LFM2.5-2.6B-Q4_K_M.gguf
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

If the LiteRT/Gemma stage is blocked or intentionally not being repeated, run the same bounded GGUF stages independently with `llamaOnly=true`; this skips Gemma model discovery, initialization, and Gemma-only assertions while retaining the LFM2.5 plan/recipe, validation, schema, cancellation/reuse, and unload/reload checks:

```sh
adb shell am instrument -w \
  -e class com.kernel.ai.debug.llamaprobe.LlamaMealPlanComparisonTest \
  -e probeOnly true \
  -e llamaOnly true \
  com.kernel.ai.debug.llamaprobe.test/com.kernel.ai.debug.llamaprobe.ProbeIsolatedRunner
```

Keep prior Gemma measurements/results separate; this mode does not validate or replace the Gemma comparator.

For a bounded first-token check without repeating the stalled full comparison, run this isolated LFM2.5 diagnostic. It loads only the official GGUF, applies its model-provided template and JSON grammar, and caps generation at one token with a 60-second deadline. The test records the first complete UTF-8 stream chunk and sanitized native TTFT/decode markers; it never logs prompt or generated text.

```sh
adb shell am instrument -w \
  -e class 'com.kernel.ai.debug.llamaprobe.LlamaMealPlanComparisonTest#boundedLfmFirstTokenStream' \
  -e probeOnly true \
  -e llamaFirstTokenOnly true \
  com.kernel.ai.debug.llamaprobe.test/com.kernel.ai.debug.llamaprobe.ProbeIsolatedRunner
```

To verify cooperative cancellation during the known long CPU prefill and same-session reuse, run this single bounded isolated test after the normal package/signer/version and memory preflight:

```sh
adb shell am instrument -w \
  -e class 'com.kernel.ai.debug.llamaprobe.LlamaMealPlanComparisonTest#boundedLfmPrefillCancellationDrainsAndReusesSession' \
  -e probeOnly true \
  -e llamaPrefillCancelOnly true \
  com.kernel.ai.debug.llamaprobe.test/com.kernel.ai.debug.llamaprobe.ProbeIsolatedRunner
```

`LlamaProbeNative` emits only sanitized phase markers and token counts/timings (grammar, tokenization, prefill, sampling, token decode); it never logs prompt or generated text. In progress mode it also records effective context/batch/thread settings and process-wide CPU-time deltas every five seconds while prefill is blocked. Rising CPU deltas indicate active work; near-zero deltas suggest a wait or starvation but do not prove deadlock. A one-token cap is only runtime/streaming evidence, not a usable meal plan or acceptance pass. On timeout, inspect the last native phase marker and stop only the isolated probe package if it remains active; do not repeat without new evidence.

The comparison records only sanitized timing, validation and memory metrics; it does not print generated text. It loads/unloads one model at a time and retains the existing low-memory stop behavior. Results are emitted as `result_json part=i/n data=...`; concatenate `data` values in part order before parsing. Each UTF-8-safe JSON payload is limited to 2,800 bytes to avoid Android logcat truncation.

The JNI bridge sends raw token-piece bytes to Kotlin through a reusable byte array. Kotlin's strict incremental UTF-8 decoder buffers split code points and rejects malformed or truncated output; `onText` runs synchronously on the single native worker. A complete generation returns `LlamaGeneration`; schema/native/cancellation failures remain terminal exceptions. Cancellation is serialized against callbacks. The pinned CPU backend's abort callback can stop a request at graph-node boundaries, but cannot interrupt an operation already in progress. `awaitNativeIdle()` and `close()` bound worker draining to 30 seconds; on close timeout, native destruction remains queued behind the worker rather than freeing callback state concurrently. Streaming begins only after prompt prefill returns.

Each stage logs a sanitized `MealPlanProbe` start/completion/failure marker with elapsed time; no prompt or generated text is included. Each suspending stage has a five-minute coroutine deadline. After a host-side timeout, inspect `adb logcat -d -s MealPlanProbe`. If instrumentation remains active, stop only the isolated package with `adb shell am force-stop com.kernel.ai.debug.llamaprobe`; never stop the normal debug package. A native call may not observe coroutine cancellation until it returns, so do not rerun until the isolated process has exited.
