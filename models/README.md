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

### EmbeddingGemma models (`.tflite`)

| File | Devices | Source |
|------|---------|--------|
| `embeddinggemma-300M_seq512_mixed-precision.tflite` | Generic GPU fallback / most devices | [litert-community/embeddinggemma-300m](https://huggingface.co/litert-community/embeddinggemma-300m) |
| `embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite` | Samsung S23 Ultra / Snapdragon 8 Gen 2 NPU path | Same repo |
| `sentencepiece.model` | Tokeniser required with any EmbeddingGemma file | Same repo |

The app tries the Qualcomm-optimised EmbeddingGemma variant first when the device is detected as SM8550, then falls back to the generic file when needed.

## Speech model / voice-pack sizes

| Asset family | Approx. size | Notes |
|--------------|--------------|-------|
| Sherpa Zipformer STT | ~72 MB | Default streaming/offline-capable STT option |
| Sherpa SenseVoice STT | ~100 MB | Optional offline final-only STT option |
| Sherpa Whisper tiny.en STT | ~117 MB | Optional offline final-only STT option |
| Sherpa Paraformer STT | ~220 MB | Optional streaming STT option |
| Sherpa Piper/VITS voice packs | ~64-116 MB each | Downloaded per selected voice; exact per-voice licence must be reviewed before release |
| Semaine Piper/VITS voice pack | ~70 MB | Launch-blocking decision tracked in #1258 |
| Kokoro experimental voice pack | ~130 MB | Experimental; verify licence before release if exposed |

## Device-specific setup notes

### Samsung Galaxy S21

- Launch-compatible tier: Gemma-4 E-2B.
- Primary test role: S21-first ADB, permission, QIR, and harness validation.
- ADB path: `/sdcard/Android/data/com.kernel.ai.debug/files/models/`.

### Samsung Galaxy S23 Ultra (SM8550 / Snapdragon 8 Gen 2)

- Larger comparison tier: Gemma-4 E-4B where available.
- Recommended EmbeddingGemma: `qualcomm.sm8550.tflite` variant for the NPU path, generic as fallback.
- ADB path: `/sdcard/Android/data/com.kernel.ai.debug/files/models/`.

## Launch/legal notes

- Do not commit model binaries to this repository.
- Do not imply Jandal owns or sublicenses upstream model files.
- Keep model source links and approximate sizes current when model choices change.
- Keep gated-model behaviour aligned with the app UI: `Ready`, `Preparing`, `Action Required`, or `Unavailable`.
- Resolve #1258 before shipping Semaine in any Play Store release.
- See [`../docs/LEGAL_AND_ATTRIBUTION.md`](../docs/LEGAL_AND_ATTRIBUTION.md) before release packaging or Play Store publication.
