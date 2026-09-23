# Local model files

Model files are not committed to this repository. Use this directory only for local development and testing.

Some model downloads are gated. Users or testers may need to sign in to Hugging Face and accept the upstream model licence/terms before Jandal can download or use those models. The model files remain subject to their upstream model cards, licences, and provider terms.

## On-device storage path

The app stores models in **app-private external storage**. This survives normal debug reinstall flows and does not require a runtime storage permission:

```text
/sdcard/Android/data/<package>/files/models/
```

| Build variant | Package | Full path |
|---------------|---------|-----------|
| Debug | `com.kernel.ai.debug` | `/sdcard/Android/data/com.kernel.ai.debug/files/models/` |
| Release | `com.kernel.ai` | `/sdcard/Android/data/com.kernel.ai/files/models/` |

Fallback internal-storage path:

```text
/data/user/0/<package>/files/models/
```

The internal path requires `run-as` for ADB access.

## ADB push from host machine

```bash
# 1. Create the models directory on device.
adb shell mkdir -p /sdcard/Android/data/com.kernel.ai.debug/files/models

# 2. Push a model file.
adb push models/<filename> /sdcard/Android/data/com.kernel.ai.debug/files/models/<filename>

# 3. Verify.
adb shell ls -lh /sdcard/Android/data/com.kernel.ai.debug/files/models/
```

Android 11+ note: ADB can usually push to `/sdcard/Android/data/<package>/` when USB debugging is enabled. If a development device denies access, a temporary development-only workaround is:

```bash
adb shell appops set --uid shell MANAGE_EXTERNAL_STORAGE allow
```

### Fallback: push to internal storage via `run-as`

```bash
adb push models/<filename> /data/local/tmp/<filename>
adb shell run-as com.kernel.ai.debug sh -c \
  'mkdir -p files/models && cp /data/local/tmp/<filename> files/models/'
adb shell rm /data/local/tmp/<filename>
adb shell run-as com.kernel.ai.debug ls -lh files/models/
```

## Manually downloading on device

For manual setup without a host machine:

1. Download the model file to the device using a browser or download manager.
2. Move the file to `Internal storage -> Android -> data -> com.kernel.ai.debug -> files -> models`.
3. Launch the app. It detects files in this folder on startup.

## Model files reference

### LiteRT-LM inference models (`.litertlm`)

| File | Approx. size | Required | Source |
|------|--------------|----------|--------|
| `gemma-4-E2B-it.litertlm` | ~2.4 GB | Yes, launch-compatible tier | [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) |
| `gemma-4-E4B-it.litertlm` | ~3.4 GB | Optional flagship tier | [litert-community/gemma-4-E4B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) |
| `mobile_actions_q8_ekv1024.litertlm` | ~289 MB | Optional / experimental | [litert-community/functiongemma-270m-ft-mobile-actions](https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions) |

### Arctic Embed M v1.5 (`.tflite` + `vocab.txt`)

| File | Size | Device/source |
|------|------|---------------|
| `arctic-embed-m-v1.5-int8.tflite` | 113,850,784 bytes | CPU inference; converted from `Snowflake/snowflake-arctic-embed-m-v1.5` at pinned revision `e58a8f756156a1293d763f17e3aae643474e9b8a` |
| `vocab.txt` | 231,508 bytes | Uncased WordPiece vocabulary from the same pinned upstream revision |

The converted model and vocabulary have pinned SHA-256 digests in `KernelModel`. Both are ungated and require no Hugging Face account. The public versioned GitHub prerelease assets are published and anonymous downloads matched their pins. GitHub reports `immutable=false`; repository admin access is required to enable immutable-release protection, so release sign-off remains on hold.

## Speech, wake-word, and voice asset sources

These assets are downloaded or bundled by the app rather than manually placed in this directory, but they are part of the launch attribution/licence review.

| Asset family | Approx. size | Runtime/source | Notes |
|--------------|--------------|----------------|-------|
| Hey Jandal wake word | bundled ONNX assets | openWakeWord-derived Jandal-specific ONNX assets | openWakeWord is Apache-2.0; final release must record generated model/training-data provenance. |
| Sherpa Zipformer STT | ~72 MB | Hugging Face `csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21` | Streaming/online ONNX recogniser: encoder, decoder, joiner, tokens. |
| Sherpa SenseVoice STT | ~100 MB | Hugging Face `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` | Offline/final-only ONNX recogniser: model + tokens. |
| Sherpa Whisper tiny.en STT | ~117 MB | Hugging Face `csukuangfj/sherpa-onnx-whisper-tiny.en` | Offline/final-only ONNX recogniser: encoder, decoder, tokens. |
| Sherpa Paraformer STT | ~220 MB | Hugging Face `csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en` | Streaming/online ONNX recogniser: encoder, decoder, tokens. |
| Sherpa Piper/VITS voice packs | ~64-116 MB each | Sherpa-ONNX GitHub `tts-models` release assets | Downloaded per selected voice; exact per-voice/dataset licence must be reviewed before release. |
| Semaine Piper/VITS voice pack | ~70 MB | Sherpa-ONNX GitHub `tts-models/vits-piper-en_GB-semaine-medium.tar.bz2` | Launch-blocking decision tracked in #1258. |
| Kokoro experimental voice pack | ~130 MB | Sherpa-ONNX GitHub `tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2` | Experimental 53-speaker Kokoro pack; verify Kokoro model/voice licence before exposing in release. |

## Device-specific setup notes

### Samsung Galaxy S21

- Launch-compatible tier: Gemma-4 E-2B.
- Primary test role: S21-first ADB, permission, QIR, and harness validation.
- ADB path: `/sdcard/Android/data/com.kernel.ai.debug/files/models/`.

### Samsung Galaxy S23 Ultra (SM8550 / Snapdragon 8 Gen 2)

- Larger comparison tier: Gemma-4 E-4B where available.
- Arctic Embed M v1.5 uses the same CPU inference path on S21 and S23 Ultra; the old EmbeddingGemma Qualcomm NPU variant is no longer active.
- ADB path: `/sdcard/Android/data/com.kernel.ai.debug/files/models/`.

## Launch/legal notes

- Do not commit model binaries to this repository.
- Do not imply Jandal owns or sublicenses upstream model files.
- Keep model source links and approximate sizes current when model choices change.
- Keep gated-model behaviour aligned with the app UI: `Ready`, `Preparing`, `Action Required`, or `Unavailable`.
- Resolve #1258 before shipping Semaine in any Play Store release.
- Verify the Kokoro model/voice licence before exposing Kokoro in release.
- See [`../docs/LEGAL_AND_ATTRIBUTION.md`](../docs/LEGAL_AND_ATTRIBUTION.md) before release packaging or Play Store publication.
