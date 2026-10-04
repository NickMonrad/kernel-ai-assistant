# #1451 Gemma 4 GPU package A/B — TEST-READY

This is the preparation and device-evidence handoff for issue [#1451](https://github.com/NickMonrad/kernel-ai-assistant/issues/1451). It records one S21 baseline diagnostic from the previous runner ordering, but no completed baseline arm, GPU-candidate run, A/B comparison, or model recommendation. Production defaults, LiteRT-LM, and the 2 GiB GPU memory threshold remain unchanged.

## Current runtime and compatibility boundary

- Post-#1590 Jandal `main` pins `litertlm-android` **0.17.1** in `gradle/libs.versions.toml`.
- The refreshed #1451 benchmark uses that current runtime baseline; it makes no further runtime changes.
- The candidate cards do not document a minimum LiteRT-LM version. Their package-specific embedded context/KV limit, tokenizer, chat template, capability declaration, and actual required runtime are not established by the public file tree.
- LiteRT-LM 0.17.1 `Conversation.getBenchmarkInfo()` values are emitted after completed streamed generations in the `LiteRtInferenceEngine` log: native initialization phase sum, native TTFT, and prefill/decode model-token counts and rates. The report records the source and field names; preserve logcat for the values. `first_engine_init_ms`, `warm_engine_reload_ms`, and `background_resume_engine_reload_ms` remain app wall-clock timings. Do not conflate native initialization phases with engine-ready wall time, or callback chunks with model tokens. `BenchmarkInfo` does not include KV allocation/peak or GPU delegate-utilization; the benchmark does not collect embedded package capability/template metadata.
- The least-invasive GPU check is an explicit GPU request, `activeBackend == GPU` assertion after initialization and during generations, and `LiteRtInferenceEngine`'s `Backend GPU initialized successfully` log. This proves the app's GPU backend initialized and did not silently fall back; it is not a per-kernel utilization counter. If a device/runtime cannot establish that backend, record GPU execution as unresolved and do not infer it from `-gpu`.

## Live GPU candidates verified for this preparation

| Family | Repository head | Exact candidate / upload commit | Size | SHA-256 / LFS OID | Upload history |
|---|---|---|---:|---|---|
| E2B | [`b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/commit/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1) (2026-08-31) | [`gemma-4-E2B-it-gpu.litertlm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/commit/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94) — `6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94` | 2,008,432,640 B | `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5` | HF upload commit dated 2026-08-07, correcting the 2026-08-10 date in #1451's issue body; live commit history is authoritative. |
| E4B | [`2eee7ac325f20eb8c9ac1d0e972f7c84663062da`](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/commit/2eee7ac325f20eb8c9ac1d0e972f7c84663062da) | [`gemma-4-E4B-it-gpu.litertlm`](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/commit/2eee7ac325f20eb8c9ac1d0e972f7c84663062da) — `2eee7ac325f20eb8c9ac1d0e972f7c84663062da` | 2,969,059,328 B | `4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff` | HF upload/current repo head dated 2026-08-07. |

The exact blobs were downloaded outside git to `/home/lokhor/.cache/jandal-1451/` and SHA-256 checked. Do not copy model binaries into this repository. Recheck the live HF tree, commit and hash immediately before later device runs.

Both HF cards declare **Apache-2.0** and identify the corresponding Google Gemma 4 E2B/E4B instruct base. The cards describe generic CPU XNNPack and GPU ML Drift backend optimization and family-level context up to 32K; they do not specify a candidate-specific LiteRT-LM minimum or package KV-cache limit. The current HF trees expose a 11,995-byte `chat_template.jinja`, but no sibling tokenizer/config JSON or `prompt_templates` metadata. This external template contains the old `standard_keys` filter for `description`, `type`, `properties`, `required`, and `nullable`; it is not proof of the template embedded in either `.litertlm` package. Upstream LiteRT-LM commit [`38390524`](https://github.com/google-ai-edge/LiteRT-LM/commit/38390524aba95b145ef55aa8934c7cd642dc8edf) removes the filter and adds the schema-key regression case; both candidate uploads predate that fix.

**QAT classification:** `QAT-family artifact with exact artifact-specific layout unverified`. Google AI Edge Gallery issue [#978](https://github.com/google-ai-edge/gallery/issues/978) is first-party family-level QAT evidence, not conversion/layout evidence for these exact files.

## Fixed functional corpus and evidence

Run the single test `Gemma4GpuPackageBenchmarkDeviceTest#benchmarkConfiguredPackage` once per package using the same debug app/test APK, LiteRT-LM 0.17.1, device, GPU selection, and thermal starting condition. It writes incremental JSON to `files/1451/<run_id>.json` in the debug app's private files directory and emits `PR1451_CASE`, `PR1451_CANCEL`, and `PR1451_RESULT` log markers.

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

The JSON records the exact model path, expected and actual byte counts and SHA-256 values before initialization and after the benchmark, the full app source head and supplied `source_commit`, device/build/API, first engine initialization, same-process warm reload, background/resume reload, selected backend, requested/resolved context token capacity, first-visible-callback TTFT for streamed generation cases, callback chunks, visible-character throughput, thinking characters, structured-output duration, cancellation completion, PSS, `/proc/self/status` RSS/high-water RSS, and thermal status samples during three repeated generations. The required preflight hashes the entire model before constructing the runtime, so it reads the entire file and may warm the OS page cache. `first_engine_init_ms` is process-cold but follows this preflight read; cache state may remain warm or be partially evicted. Do not describe it as cold-disk initialization. Apply the same preflight to both A/B arms. The post-run check records the same pins and fails if the file changed.

### First S21 baseline diagnostic

On S21, the first initialization selected GPU in **31.327 s** with process PSS around **2,036 MiB**. Under the previous ordering, warm reload found **1,925 MiB** available system memory—**123 MiB below** the unchanged **2,048 MiB** GPU floor—and selected CPU after **6.984 s**. The runner aborted before functional or performance cases. This is diagnostic evidence only, not a completed baseline arm or candidate comparison; it motivated running the fixed corpus and native metrics immediately after cold GPU initialization and recording reloads separately at the end.

For each completed streamed generation, `LiteRtInferenceEngine` logs `LiteRT benchmark: init=..., TTFT=..., prefill=... tokens @ ... tokens/s, decode=... tokens @ ... tokens/s [backend=...]` from `Conversation.getBenchmarkInfo()`. `initTimeInSecond` is the native engine-plus-conversation initialization phase sum; the `*_engine_init_ms` report fields are app wall-clock durations around `engine.initialize()`. Capture the native values with the corresponding JSON using the logcat command below.

Both A/B arms fix `ModelConfig.speculativeDecodingEnabled=false`. Preserve the existing `LiteRtInferenceEngine` `Speculative decoding: requested=..., active=...` log line alongside each report so requested and resolved state are auditable.

`BenchmarkInfo` does not include KV allocation/peak or GPU delegate-utilization; the benchmark does not collect embedded tokenizer/template/capability metadata. Do not relabel callback count as tokens/s. Package load, thinking, tool/schema behavior, context, reset/cancel and selected GPU backend must be determined by the instrumented run.

## Device procedure

1. Re-verify both exact HF candidates and host cache checksums. Check device free storage/memory and keep the screen interactive. Use S21 for the current E2B baseline vs E2B GPU candidate; S23 Ultra for current E4B vs E4B GPU. Include Honor Magic 8 Pro for the E4B comparison only when the existing tier policy selects E4B there. Do not force an unsupported model tier.
2. From the exact reviewed commit, build the app and test APKs with `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PversionCode=3303 -Ppr1451BenchmarkIsolation=true`. The source head is embedded in the debug app; the runner requires the supplied full `source_commit` to match it. Release builds hard-disable this isolation flag.
3. Run the strict APK metadata check below before installing either APK. It requires the debug app package/variant and versionCode ≥3303, plus the matching AndroidTest package/variant/output metadata (versionCode 0).
4. Only after the metadata check passes, install both APKs later with `adb install -r`. Never clear app data. The debug startup gate cancels backfill/Nextcloud work while preserving archive cleanup.
5. Before any benchmark invocation, place the exact GPU candidate beside the current baseline in `getExternalFilesDir("models")`. Verify the local candidate against its live HF size/hash before pushing it.
6. Measure the current baseline and staged GPU file on-device before either arm runs, and compare each measured byte count/SHA-256 against its trusted source. This identical pre-run read warms both model files; if either digest is untrusted or mismatched, stop. Supply both exact pins on every invocation.
7. Run one baseline→GPU pair with both expected pins, fixed startup isolation, unique run IDs, and the same cool/interactive starting condition. One clean comparable pair is sufficient; perform the mirrored GPU→baseline repeat only if a material result is ambiguous. Each arm's cold initialization and every functional/native-performance generation must stay on GPU; CPU fallback there is a hard failure. Complete all functional and thermal measurements on the initial GPU session before reload diagnostics.

Record `warm_reload_backend` and `background_reload_backend`, the corresponding post-init readiness fields (`warm_reload_engine_ready` / `background_reload_engine_ready`) and reload elapsed times (`warm_engine_reload_ms` / `background_resume_engine_reload_ms`). Each reload also records pre-init available system memory (`warm_reload_pre_init_available_system_memory_mib` / `background_reload_pre_init_available_system_memory_mib`), the unchanged 2,048 MiB floor and headroom, plus process PSS/RSS, `warm_reload_gpu_retained` / `background_reload_gpu_retained`, and its outcome or error. `reload_gpu_stability_status` is independent of top-level `status`: `passed` only when both reloads retain GPU; `failed_cpu_fallback` for a CPU selection; `failed_reload_error` for a reload error or timeout; otherwise `unknown_backend`. Reload diagnostics run no generation; CPU reload latency is not GPU-comparable throughput. The production 2 GiB GPU memory floor is unchanged.

The metadata check is fail-closed; it verifies the exact APK files that will be installed:

```bash
python3 - <<'PY'
import json
from pathlib import Path

def verify(path, application_id, variant, output_name, required_version_code):
    metadata = json.loads(path.read_text())
    assert metadata["applicationId"] == application_id, metadata
    assert metadata["variantName"] == variant, metadata
    matches = [item for item in metadata["elements"] if item["outputFile"] == output_name]
    assert len(matches) == 1, metadata
    element = matches[0]
    assert int(element["versionCode"]) >= required_version_code, element
    apk = path.parent / output_name
    assert apk.is_file(), apk
    return {
        "applicationId": metadata["applicationId"],
        "variantName": metadata["variantName"],
        "versionCode": int(element["versionCode"]),
        "apk": str(apk),
    }

root = Path("app/build/outputs/apk")
app = verify(
    root / "debug/output-metadata.json",
    "com.kernel.ai.debug",
    "debug",
    "app-debug.apk",
    3303,
)
test = verify(
    root / "androidTest/debug/output-metadata.json",
    "com.kernel.ai.debug.test",
    "debugAndroidTest",
    "app-debug-androidTest.apk",
    0,
)
assert test["versionCode"] == 0, test
print(json.dumps({"app": app, "androidTest": test}, indent=2))
PY
```

After the metadata check passes, install the APKs:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

After installation and before either benchmark arm, stage the E2B GPU candidate and read both current/GPU package sizes and hashes. These checks prepare the exact pins; they do not invoke the benchmark:

```bash
adb push /home/lokhor/.cache/jandal-1451/gemma-4-E2B-it-gpu.litertlm \
  /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E2B-it-gpu.litertlm
adb shell run-as com.kernel.ai.debug wc -c \
  /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E2B-it.litertlm
adb shell run-as com.kernel.ai.debug sha256sum \
  /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E2B-it.litertlm
adb shell run-as com.kernel.ai.debug wc -c \
  /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E2B-it-gpu.litertlm
adb shell run-as com.kernel.ai.debug sha256sum \
  /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E2B-it-gpu.litertlm
```

For E4B, push and measure `gemma-4-E4B-it-gpu.litertlm` and `gemma-4-E4B-it.litertlm` the same way. Compare both measured identities with their trusted sources, and record them before running. If no independent trusted digest is available for either package, stop instead of generating a post-run pin.

Example E2B GPU invocation. For the paired baseline, replace `model_path`, `candidate`, `expected_bytes`, `expected_sha256`, and `run_id` with that baseline's pre-verified values. These commands are for the later authorized device phase, not executed here:

```bash
SOURCE_COMMIT=$(git rev-parse HEAD)

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

For the E4B candidate use `gemma-4-E4B-it-gpu.litertlm`, `2969059328`, SHA-256 `4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff`, and a unique device/run label. The current generic baseline files are `gemma-4-E2B-it.litertlm` (reference 2,583,085,056 B in `KernelModel`) and `gemma-4-E4B-it.litertlm` (reference 3,654,467,584 B). Acquire and verify each installed baseline SHA-256 before instrumentation; never use an unpinned run report to establish its own pin. Retrieve every JSON report and preserve the corresponding logcat, APK commit, live HF metadata, start/end thermal status, and run order together.
| Later run | `model_path` | `candidate` | `expected_bytes` | `expected_sha256` |
|---|---|---|---:|---|
| S21 current E2B | `/sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E2B-it.litertlm` | `e2b-current` | `2583085056` (confirm before run) | required trusted pre-run SHA-256; never omit |
| S21 GPU E2B | `/sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E2B-it-gpu.litertlm` | `e2b-gpu` | `2008432640` | `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5` |
| S23 Ultra current E4B | `/sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E4B-it.litertlm` | `e4b-current` | `3654467584` (confirm before run) | required trusted pre-run SHA-256; never omit |
| S23 Ultra GPU E4B | `/sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E4B-it-gpu.litertlm` | `e4b-gpu` | `2969059328` | `4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff` |
| Honor Magic 8 Pro | Use the same current/GPU E4B paths only if its existing tier policy selects E4B. | Use `e4b-current` / `e4b-gpu` with Honor-specific run IDs. | As above. | As above. |

Every row requires both `expected_bytes` and `expected_sha256` on every invocation, including the first baseline. Acquire baseline pins before any benchmark and pass those exact values; GPU rows always use the artifact-table pins. Do not compare runs with different app commits, LiteRT-LM runtime, backend selection, device tier, or corpus.

## TEST-READY blockers and stop line

- No matched A/B or candidate evidence exists yet. The first S21 baseline diagnostic ended before the fixed corpus and performance measurements under the previous ordering; GPU-candidate compatibility, corpus outcomes, and matched memory/thermal behavior remain unverified.
- The public cards do not establish a minimum runtime. Record any 0.17.1 compatibility failure and exact evidence; a further runtime change is outside this #1451 refresh and requires a separate decision.
- The schema-property regression is intentionally a hard functional case. Do not rename or rewrite the schema to hide an old embedded-template failure.
- Newer device-specific NPU packages, model replacement/default changes, custom JNI/delegate telemetry, and broad runtime architecture work are out of scope.
