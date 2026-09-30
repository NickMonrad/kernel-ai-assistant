# #1451 Gemma 4 GPU package A/B — TEST-READY

This is the preparation handoff for issue [#1451](https://github.com/NickMonrad/kernel-ai-assistant/issues/1451), not a benchmark result or model recommendation. Production defaults and LiteRT-LM remain unchanged. No device run is represented by this document.

## Current runtime and compatibility boundary

- Jandal `main` pins `litertlm-android` **0.11.0** in `gradle/libs.versions.toml`.
- Issue [#31](https://github.com/NickMonrad/kernel-ai-assistant/issues/31) remains the runtime-upgrade seam; its current release evaluation target is 0.17.1. This spike does not upgrade the app.
- The candidate cards do not document a minimum LiteRT-LM version. Their package-specific embedded context/KV limit, tokenizer, chat template, capability declaration, and actual required runtime are not established by the public file tree.
- LiteRT-LM 0.11.0 does not expose the newer package capability/template snapshot or native token-count, prefill/decode, KV-cache, and GPU delegate-utilization metrics. Record callback TTFT, callback chunks (not tokens), visible characters/second, PSS/RSS, and selected backend only. The engine's existing `tokens/sec` log also counts callback chunks, not model tokens.
- The least-invasive GPU check is an explicit GPU request, `activeBackend == GPU` assertion after initialization and during generations, and `LiteRtInferenceEngine`'s `Backend GPU initialized successfully` log. This proves the app's GPU backend initialized and did not silently fall back; it is not a per-kernel utilization counter. If a device/runtime cannot establish that backend, record GPU execution as unresolved and do not infer it from `-gpu`.

## Live GPU candidates verified for this preparation

| Family | Repository head | Exact candidate / upload commit | Size | SHA-256 / LFS OID | Upload history |
|---|---|---|---:|---|---|
| E2B | [`b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/commit/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1) (2026-08-31) | [`gemma-4-E2B-it-gpu.litertlm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/commit/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94) — `6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94` | 2,008,432,640 B | `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5` | HF upload commit dated 2026-08-07; #1451 body gives 2026-08-10, so retain the live commit history as authoritative. |
| E4B | [`2eee7ac325f20eb8c9ac1d0e972f7c84663062da`](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/commit/2eee7ac325f20eb8c9ac1d0e972f7c84663062da) | [`gemma-4-E4B-it-gpu.litertlm`](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/commit/2eee7ac325f20eb8c9ac1d0e972f7c84663062da) — `2eee7ac325f20eb8c9ac1d0e972f7c84663062da` | 2,969,059,328 B | `4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff` | HF upload/current repo head dated 2026-08-07. |

The exact blobs were downloaded outside git to `/home/lokhor/.cache/jandal-1451/` and SHA-256 checked. Do not copy model binaries into this repository. Recheck the live HF tree, commit and hash immediately before later device runs.

Both HF cards declare **Apache-2.0** and identify the corresponding Google Gemma 4 E2B/E4B instruct base. The cards describe generic CPU XNNPack and GPU ML Drift backend optimization and family-level context up to 32K; they do not specify a candidate-specific LiteRT-LM minimum or package KV-cache limit. The current HF trees expose a 11,995-byte `chat_template.jinja`, but no sibling tokenizer/config JSON or `prompt_templates` metadata. This external template contains the old `standard_keys` filter for `description`, `type`, `properties`, `required`, and `nullable`; it is not proof of the template embedded in either `.litertlm` package. Upstream LiteRT-LM commit [`38390524`](https://github.com/google-ai-edge/LiteRT-LM/commit/38390524aba95b145ef55aa8934c7cd642dc8edf) removes the filter and adds the schema-key regression case; both candidate uploads predate that fix.

**QAT classification:** `QAT-family artifact with exact artifact-specific layout unverified`. Google AI Edge Gallery issue [#978](https://github.com/google-ai-edge/gallery/issues/978) is first-party family-level QAT evidence, not conversion/layout evidence for these exact files.

## Fixed functional corpus and evidence

Run the single test `Gemma4GpuPackageBenchmarkDeviceTest#benchmarkConfiguredPackage` once per package using the same debug app/test APK, LiteRT-LM 0.11.0, device, GPU selection, and thermal starting condition. It writes incremental JSON to `files/1451/<run_id>.json` in the debug app's private files directory and emits `PR1451_CASE`, `PR1451_CANCEL`, and `PR1451_RESULT` log markers.

The fixed functional prompts cover:

1. normal conversational response;
2. multi-turn context setup and recall of `JANDAL-1451-ORBIT`;
3. thinking-enabled response with observed `thought` channel output;
4. direct `fixture_lookup` tool call and execution result;
5. `load_skill` followed by `fixture_lookup`, requiring recorded call order;
6. two repeated `fixture_lookup` calls in the same conversation;
7. constrained output with legitimate schema property names `type`, `required`, and `nullable` (the prompt requests distinct proof values); and
8. cancellation during a long generation, followed by reset/reuse.

The fixture tools are test-only deterministic substitutes with no device side effects. The corpus checks package/template/tool-schema handling through the existing `LiteRtInferenceEngine` and `ModelConfig` seams; it does not claim to qualify every production tool's external service.

The JSON records exact model path/bytes/hash, source commit, device/build/API, first engine initialization (process-cold only; the OS/page cache is not flushed), same-process warm reload, background/resume reload, selected backend, requested/resolved context token capacity, first-visible-callback TTFT for streamed generation cases, callback chunks, visible-character throughput, thinking characters, structured-output duration, cancellation completion, PSS, `/proc/self/status` RSS/high-water RSS, and thermal status samples during three repeated generations. The SHA-256 is calculated after measured generation so hashing does not pre-warm file pages ahead of initialization/TTFT measurements. Pass `expected_sha256` for pinned GPU candidates; the report always contains the observed hash when the test can finish cleanup.

Native model-token throughput, prefill/decode rates, KV allocation/peak, embedded tokenizer/template/capabilities, and delegate utilization remain unavailable on 0.11.0. Do not relabel callback count as tokens/s. Package load, thinking, tool/schema behavior, context, reset/cancel and selected GPU backend must be determined by the instrumented run.

## Later device procedure (not run in this preparation)

1. Re-verify both exact HF candidates and host cache checksums. Check device free storage/memory and keep the screen interactive. Use S21 for the current E2B baseline vs E2B GPU candidate; S23 Ultra for current E4B vs E4B GPU. Include Honor Magic 8 Pro for the E4B comparison only when the existing tier policy selects E4B there. Do not force an unsupported model tier.
2. Build artifacts from the reviewed commit with `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`; install both APKs later using `adb install -r`. Never clear app data. Install is a later device step, not part of preparation.
3. Current packages live under `getExternalFilesDir("models")`, normally `/sdcard/Android/data/com.kernel.ai.debug/files/models/`: `gemma-4-E2B-it.litertlm` and `gemma-4-E4B-it.litertlm`. Push the corresponding `-gpu.litertlm` file to that same directory under its exact HF filename. Keep the installed current package in place for its baseline run.
4. Run each pair in counterbalanced order (baseline→GPU, then GPU→baseline), with a consistent cool starting state and a unique run ID. Repeat at least three paired runs per applicable device/family. Force the GPU backend in both runs; a CPU fallback is a failed GPU-evidence run, not a successful candidate result.

Example E2B GPU run; replace the device model path only for the paired baseline run. These commands are for the later authorized device phase, not executed here:

```bash
SOURCE_COMMIT=$(git rev-parse HEAD)

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push /home/lokhor/.cache/jandal-1451/gemma-4-E2B-it-gpu.litertlm \
  /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E2B-it-gpu.litertlm
adb shell am force-stop com.kernel.ai.debug
adb shell am instrument -w \
  -e class com.kernel.ai.Gemma4GpuPackageBenchmarkDeviceTest \
  -e model_path /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E2B-it-gpu.litertlm \
  -e candidate e2b-gpu \
  -e source_commit "$SOURCE_COMMIT" \
  -e expected_bytes 2008432640 \
  -e expected_sha256 a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5 \
  -e run_id s21-e2b-gpu-pair1 \
  com.kernel.ai.debug.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.kernel.ai.debug cat files/1451/s21-e2b-gpu-pair1.json \
  > s21-e2b-gpu-pair1.json
adb logcat -d -s LiteRtInferenceEngine Gemma4Package1451
```

For the E4B candidate use `gemma-4-E4B-it-gpu.litertlm`, `2969059328`, SHA-256 `4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff`, and a unique device/run label. The current generic baseline files are `gemma-4-E2B-it.litertlm` (2,583,085,056 B in `KernelModel`) and `gemma-4-E4B-it.litertlm` (3,654,467,584 B). Let the test report each installed baseline's SHA-256, then pin that digest on subsequent repeats. Retrieve every JSON report and preserve the corresponding logcat, APK commit, live HF metadata, start/end thermal status, and run order together.
| Later run | `model_path` | `candidate` | `expected_bytes` | `expected_sha256` |
|---|---|---|---:|---|
| S21 current E2B | `/sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E2B-it.litertlm` | `e2b-current` | omit to record the installed size | omit on first baseline run; pin the report hash on repeats |
| S21 GPU E2B | `/sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E2B-it-gpu.litertlm` | `e2b-gpu` | `2008432640` | `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5` |
| S23 Ultra current E4B | `/sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E4B-it.litertlm` | `e4b-current` | omit to record the installed size | omit on first baseline run; pin the report hash on repeats |
| S23 Ultra GPU E4B | `/sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E4B-it-gpu.litertlm` | `e4b-gpu` | `2969059328` | `4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff` |
| Honor Magic 8 Pro | Use the same current/GPU E4B paths only if its existing tier policy selects E4B. | Use `e4b-current` / `e4b-gpu` with Honor-specific run IDs. | As above. | As above. |

For each row, replace the example command's `model_path`, `candidate`, expected size/hash, `source_commit`, and `run_id`. On first baseline run, retain the reported file hash and pass it as `expected_sha256` for subsequent repeats. GPU rows always pass both pinned size and hash. Do not compare runs with different app commits, LiteRT-LM runtime, backend selection, device tier, or corpus.

## TEST-READY blockers and stop line

- No device evidence exists yet: load compatibility on 0.11.0, the embedded chat-template version, functional corpus outcomes, memory/thermal stability, and GPU execution remain unverified until the later device phase.
- The public cards do not establish a minimum runtime. If the 0.11.0 test exposes a runtime requirement, record the exact failure/evidence and coordinate a bounded runtime decision through #31; do not upgrade this app in #1451.
- The schema-property regression is intentionally a hard functional case. Do not rename or rewrite the schema to hide an old embedded-template failure.
- Newer device-specific NPU packages, model replacement/default changes, custom JNI/delegate telemetry, and broad runtime architecture work are out of scope.
