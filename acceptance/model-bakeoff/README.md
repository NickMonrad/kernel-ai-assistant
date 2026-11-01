# Debug model bake-off

This isolated Android test application exercises pinned `.litertlm` files directly through the repository's LiteRT-LM 0.17.1 SDK dependency. It is not part of `:app`, model registry, or runtime model selection. It adds no production dependencies and does not change v0.1 behavior.

## Run on S23U

Build and install only the debug probe and its instrumentation APK:

```sh
./gradlew :acceptance:model-bakeoff:assembleDebug :acceptance:model-bakeoff:assembleDebugAndroidTest
adb -s RFCW11265XH install -r acceptance/model-bakeoff/build/outputs/apk/debug/model-bakeoff-debug.apk
adb -s RFCW11265XH install -r acceptance/model-bakeoff/build/outputs/apk/androidTest/debug/model-bakeoff-debug-androidTest.apk
```

Stage the four pinned files under `/sdcard/Android/data/com.kernel.ai.debug.modelbakeoff/files/models/`; each invocation selects and verifies only its requested contender. Run one contender/backend at a time:

```sh
adb -s RFCW11265XH shell am instrument -w \
  -e model gemma -e backend GPU -e mtp false \
  com.kernel.ai.debug.modelbakeoff.test/androidx.test.runner.AndroidJUnitRunner
```

Claim the exclusive S23U lane on #1682 before device work. Take a fresh thermal check before each backend run; proceed only at Android Thermal Status 0–1. Stop on status above 1, OOM, or LMKD, allow cooldown, and re-claim only when the lane is free. Release the claim after the matrix or any blocker.

`model` is `gemma`, `qwen`, `agents`, or `nemotron`; `backend` is `GPU` or `CPU`; `mtp` is `true` or `false`. The test verifies exact byte count and SHA-256 against the pinned model metadata before loading. Run GPU before CPU; compare E4B with MTP disabled and enabled. Each run uses the unchanged nine #1684 device transition fixtures, #385/#968 numeric forms and grounding contexts, and all five #1611 golden tool transcripts. Tool responses are local, test-only stubs; no network or real device actions are invoked. Fixture and lifecycle reports are written under the probe app's private `files/J691Bakeoff/results/` directory and logged with tag `J691Bakeoff`.

For a same-prompt E4B/GPU decode comparison when the full MTP-on suite fails on constrained output, use `-e probe mtp-throughput` with `mtp=false`, then `mtp=true`. This plain-text throughput probe is supplementary; it does not replace the shared fixture run or establish MTP compatibility with constrained responses.

Gemma and Nemotron use the LiteRT-LM function-call API. Qwen3.5 and Agents-A1 declare canonical XML calls in their model templates but are not parsed by their generic LiteRT-LM model type; the debug harness parses that XML and returns each local stub result as a model-template `tool_response`. It does not alter production inference or tool handling.

The transition output distinguishes schema/structure validity, intended semantic decision, and proposed dispatch match. It does not execute a Jandal action. GPU-memory readings use process graphics PSS where exposed and otherwise KGSL sysfs; the fallback may be device-wide, so treat `gpuKb` as a proxy rather than definitive per-model allocation. Device run results, including raw transcripts under `files/J691Bakeoff/transcripts/`, stay local unless separately approved for publication.

## Model pins and licences

Source repositories are Hugging Face model repositories pinned to immutable commits. The byte count and SHA-256 are checked before each engine load; licence identifiers were checked at the pinned revision. Model files are staging assets, not repository content.

| Model | Repository @ revision | File | Bytes | SHA-256 | Licence |
|---|---|---|---:|---|---|
| Gemma 4 E4B baseline | `litert-community/gemma-4-E4B-it-litert-lm` @ `e6a3892a8c22cf87ff99583996149cf3cb32452b` | `gemma-4-E4B-it.litertlm` | 3,659,530,240 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` | Apache-2.0 |
| Qwen3.5-4B mixed-int4 | `litert-community/Qwen3.5-4B` @ `3e4011ec2e502694f4523ac12f6348eeb21ad4ab` | `Qwen3.5-4B_mixed_int4.litertlm` | 2,754,070,624 | `7243c33c9c6192150ab6da7ac13a9efdc24ff1a653eabc93b2757de4b44b012a` | Apache-2.0 |
| Agents-A1-4B mixed-int4 | `litert-community/Agents-A1-4B` @ `6c44e56f51b2d418cd3b8266bf7633cc96535e83` | `Agents-A1-4B_mixed_int4.litertlm` | 2,754,365,536 | `13c53e3ada0655ed1af4204aa2b659fe7cd553a1e7d5ef8e8b11d1ed3c15203c` | Apache-2.0 |
| NVIDIA Nemotron 3.5 Bolt E4B int4 | `litert-community/NVIDIA-Nemotron-3.5-Bolt-E4B` @ `a98def827e7f80e991aa894d2c91d2ec7e865784` | `NVIDIA-Nemotron-3.5-Bolt-E4B_int4.litertlm` | 4,404,768,064 | `e24456d06bd9155470593f036d4b63e5b47d53ae7e88d8511c6ba768c6e68d6b` | OpenMDW-1.1 |
