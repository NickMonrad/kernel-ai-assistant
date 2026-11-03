# Debug model bake-off

This isolated Android test application exercises pinned `.litertlm` files directly through the repository's LiteRT-LM SDK dependency. It is not part of `:app`, model registry, or runtime model selection. It adds no production dependencies and does not change v0.1 behavior.

## Run on S23U

Build and install only the debug probe and its instrumentation APK:

```sh
./gradlew :acceptance:model-bakeoff:assembleDebug :acceptance:model-bakeoff:assembleDebugAndroidTest
adb -s RFCW11265XH install -r acceptance/model-bakeoff/build/outputs/apk/debug/model-bakeoff-debug.apk
adb -s RFCW11265XH install -r acceptance/model-bakeoff/build/outputs/apk/androidTest/debug/model-bakeoff-debug-androidTest.apk
```

Stage the selected pinned file under `/sdcard/Android/data/com.kernel.ai.debug.modelbakeoff/files/models/`; each invocation selects and verifies only its requested contender. Run one contender/backend at a time:

```sh
adb -s RFCW11265XH shell am instrument -w \
  -e model lfm3-a -e backend GPU -e mtp false \
  com.kernel.ai.debug.modelbakeoff.test/androidx.test.runner.AndroidJUnitRunner
```

Claim the exclusive S23U lane on the active issue before device work; this LFM3 run is tracked on #699. Take a fresh thermal check before each backend run; proceed only at Android Thermal Status 0–1. Stop on status above 1, OOM, LMKD, or RSS above approximately 4 GB, allow cooldown, and re-claim only when the lane is free. Release the claim after the matrix or any blocker.

`model` is `gemma`, `qwen`, `agents`, `nemotron`, `lfm3-a`, or `lfm3-b`; `backend` is `GPU` or `CPU`; `mtp` is `true` or `false`. The test verifies exact byte count and SHA-256 against the pinned model metadata before each engine load. For this LFM3 matrix, use GPU with `mtp=false`; run E4B with the same context targets as the baseline. Measure each target with `thinking=false` and `thinking=true`. Each corpus run uses the unchanged nine #1684 device transition fixtures, #385/#968 numeric forms and grounding contexts, and all five #1611 golden tool transcripts. Tool responses are local, test-only stubs; no network or real device actions are invoked. Fixture and lifecycle reports are written under the probe app's private `files/J691Bakeoff/results/` directory and logged with tag `J691Bakeoff`.

For a same-prompt E4B/GPU decode comparison when the full MTP-on suite fails on constrained output, use `-e probe mtp-throughput` with `mtp=false`, then `mtp=true`. This plain-text throughput probe is supplementary; it does not replace the shared fixture run or establish MTP compatibility with constrained responses.
For a long-context measurement, pass one target and thinking mode per invocation so RSS and thermal status can be checked before continuing:

```sh
adb -s RFCW11265XH shell am instrument -w \
  -e model lfm3-a -e backend GPU -e probe context -e contextTokens 4096 -e thinking false \
  com.kernel.ai.debug.modelbakeoff.test/androidx.test.runner.AndroidJUnitRunner
```

Run `contextTokens=4096,8192` for A, B, and the E4B baseline; run `16384` for A only. Repeat each target with `thinking=false` and `thinking=true`. Record an unsupported or failed target rather than silently omitting it. Use `probe=state-leak` for the five-trial reused-Engine isolation gate.

Gemma and Nemotron use the LiteRT-LM function-call API. Qwen3.5, Agents-A1, and LFM3 declare XML tool calls in their model templates; the debug harness parses those XML calls and returns each local stub result as a model-template `tool_response`. It does not alter production inference or tool handling.

The transition output distinguishes schema/structure validity, intended semantic decision, and proposed dispatch match. It does not execute a Jandal action. GPU-memory readings use process graphics PSS where exposed and otherwise KGSL sysfs; the fallback may be device-wide, so treat `gpuKb` as a proxy rather than definitive per-model allocation. Device run results, including raw transcripts under `files/J691Bakeoff/transcripts/`, stay local unless separately approved for publication.

## Model pins and licences

Upstream model source revisions and locally exported model provenance are pinned below. The byte count and SHA-256 are checked before each engine load; licence identifiers are recorded from the pinned source revisions. Model files are staging assets, not repository content.

| Model | Repository @ revision | File | Bytes | SHA-256 | Licence |
|---|---|---|---:|---|---|
| Gemma 4 E4B baseline | `litert-community/gemma-4-E4B-it-litert-lm` @ `e6a3892a8c22cf87ff99583996149cf3cb32452b` | `gemma-4-E4B-it.litertlm` | 3,659,530,240 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` | Apache-2.0 |
| Qwen3.5-4B mixed-int4 | `litert-community/Qwen3.5-4B` @ `3e4011ec2e502694f4523ac12f6348eeb21ad4ab` | `Qwen3.5-4B_mixed_int4.litertlm` | 2,754,070,624 | `7243c33c9c6192150ab6da7ac13a9efdc24ff1a653eabc93b2757de4b44b012a` | Apache-2.0 |
| Agents-A1-4B mixed-int4 | `litert-community/Agents-A1-4B` @ `6c44e56f51b2d418cd3b8266bf7633cc96535e83` | `Agents-A1-4B_mixed_int4.litertlm` | 2,754,365,536 | `13c53e3ada0655ed1af4204aa2b659fe7cd553a1e7d5ef8e8b11d1ed3c15203c` | Apache-2.0 |
| NVIDIA Nemotron 3.5 Bolt E4B int4 | `litert-community/NVIDIA-Nemotron-3.5-Bolt-E4B` @ `a98def827e7f80e991aa894d2c91d2ec7e865784` | `NVIDIA-Nemotron-3.5-Bolt-E4B_int4.litertlm` | 4,404,768,064 | `e24456d06bd9155470593f036d4b63e5b47d53ae7e88d8511c6ba768c6e68d6b` | OpenMDW-1.1 |
| LFM3-1.7B int8, A (32K-labeled artifact; context unvalidated) | `maruel/LFM3-1.7B-LiteRT-INT8-32K` @ `dcc46e2491394dc6b1780fd5ad60b97c4c7f58f4` | `LFM3-1.7B_int8_32k_fp16.litertlm` | 1,787,337,904 | `695e33f821cde8e01d9ecc90edb0273e2a4db7ff40037066d2ea57da373b80a8` | LFM Open License v1.0 |
| LFM3-1.7B int8, B (`int8_ekv4096` artifact) | `litert-community/LFM3-1.7B-LiteRT` @ `9bbda264c623f30c905b9c247761ee7463f2b00d` | `LFM3-1.7B_int8_ekv4096.litertlm` | 1,801,557,456 | `8b83651037fcec23f232e8f4e670683a44cc7097a4d5f74a1abddeb7bc0dae27` | LFM Open License v1.0 |
| LFM3-1.7B custom 16K INT8/FP16 export | `LiquidAI/LFM3-1.7B` @ `40ebe0aa6662c9d40389c2f5d565b6a2c6aebacd`; exporter `maruel/gomode` @ `fa6349e3f6a782a9776b8aba1a1c57c57f8c2fb1` with local 16K/1024-prefill patch | `LFM3-1.7B_int8_16k_fp16.litertlm` | 1,795,978,992 | `d564c1a68b43489153d7f7b49a758968718f42a06c2220ae8f4c826d6103168a` | LFM Open License v1.0 |

The custom export used `scripts/export_lfm_litert.py` from `maruel/gomode` commit `fa6349e3f6a782a9776b8aba1a1c57c57f8c2fb1`, locally changed to expose 8K/16K variants and a 1,024-token prefill signature. Export options: context 16,384; prefill signatures 1, 4, 16, 64, 256, and 1,024. Patched script SHA-256: `7623f2ee447446704f64ee0ad8d8c095412d9499ad2b2f69aebdc7866d1efe54`; lockfile SHA-256: `945cd4de2f604117a9885b8c7682470a098ccc45258fa177a039102044c6363b`. Exporter commit: `f61d0b792013300ae7a1ca215d8c2f9de5ef4adf`; extension commit: `f3b7baad40ec0f6a4b413912a68497bf8c5d8aa3`. The manifest records the output and source pins; inference quality and device performance remain unvalidated.
