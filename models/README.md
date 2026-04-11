# Local model files (dev only)

This directory is gitignored. Place manually downloaded model files here for local development and testing, then push them to the device via ADB (see below).

---

## On-device storage path

The app stores models in **app-private external storage** (survives reinstall, no special permission needed):

```
/sdcard/Android/data/<package>/files/models/
```

| Build variant | Package | Full path |
|---------------|---------|-----------|
| Debug | `com.kernel.ai.debug` | `/sdcard/Android/data/com.kernel.ai.debug/files/models/` |
| Release | `com.kernel.ai` | `/sdcard/Android/data/com.kernel.ai/files/models/` |

> **Fallback:** If external storage is unavailable the app falls back to internal storage at  
> `/data/user/0/<package>/files/models/`. This path requires `run-as` for ADB access.

---

## ADB push (from host machine)

```bash
# 1. Create the models directory on device (first-time only)
adb shell mkdir -p /sdcard/Android/data/com.kernel.ai.debug/files/models

# 2. Push a model file
adb push models/<filename>.litertlm /sdcard/Android/data/com.kernel.ai.debug/files/models/<filename>.litertlm

# 3. Verify
adb shell ls -lh /sdcard/Android/data/com.kernel.ai.debug/files/models/
```

> **Android 11+ note:** ADB can push to `/sdcard/Android/data/<package>/` directly on most devices
> when USB debugging is enabled. If you see a permission error, grant temporary ADB access with
> `adb shell appops set --uid shell MANAGE_EXTERNAL_STORAGE allow` (development only).

### Fallback: push to internal storage via `run-as`

```bash
# Stage to world-accessible temp area first
adb push models/<filename>.litertlm /data/local/tmp/<filename>.litertlm

# Copy into app's internal files dir and clean up
adb shell run-as com.kernel.ai.debug sh -c \
  'mkdir -p files/models && cp /data/local/tmp/<filename>.litertlm files/models/'
adb shell rm /data/local/tmp/<filename>.litertlm

# Verify
adb shell run-as com.kernel.ai.debug ls -lh files/models/
```

---

## Manually downloading on-device

If you're setting up without a host machine (e.g. directly on the test device):

1. Download the model file to the device using a browser or download manager.
2. Use a file manager app (e.g. Files by Google) to move the file to:
   `Internal storage → Android → data → com.kernel.ai.debug → files → models`
3. Launch the app — it detects files in this folder automatically on startup.

---

## Model files reference

### LiteRT-LM inference models (`.litertlm`)

| File | Size | Required | Source |
|------|------|----------|--------|
| `gemma-4-E2B-it.litertlm` | ~2.4 GB | ✅ | [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) |
| `gemma-4-E4B-it.litertlm` | ~3.4 GB | No (FLAGSHIP tier) | [litert-community/gemma-4-E4B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) |
| `mobile_actions_q8_ekv1024.litertlm` | ~289 MB | No (Phase 3) | [litert-community/functiongemma-270m-ft-mobile-actions](https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions) ⚠️ gated |

### EmbeddingGemma models (`.tflite`) — gated on HuggingFace

Push manually via ADB; not auto-downloaded.

| File | Devices | Source |
|------|---------|--------|
| `embeddinggemma-300M_seq512_mixed-precision.tflite` | **All devices** (GPU, including Pixel 10) | [litert-community/embeddinggemma-300m](https://huggingface.co/litert-community/embeddinggemma-300m) |
| `embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite` | Samsung S23 Ultra (SM8550, Snapdragon 8 Gen 2) | same repo |
| `embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8650.tflite` | Samsung S24 Ultra (SM8650, Snapdragon 8 Gen 3) | same repo |
| `sentencepiece.model` | All devices (tokeniser, required with any EmbeddingGemma) | same repo |

> **Which EmbeddingGemma file do I need?**
> - **Google Pixel 10** → `embeddinggemma-300M_seq512_mixed-precision.tflite` (generic)
> - **Samsung S23 Ultra** → `embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite` for NPU, or generic as fallback
> - **Any other device** → `embeddinggemma-300M_seq512_mixed-precision.tflite` (generic)
>
> The app tries the Qualcomm-optimised variant first (detected by board code), and automatically falls back to the generic file if the device is not SM8550.

---

## Device-specific setup notes

### Samsung Galaxy S23 Ultra (SM8550 / Snapdragon 8 Gen 2)
- Backend: **NPU** (Qualcomm Hexagon via QNN delegate)
- Recommended EmbeddingGemma: `qualcomm.sm8550.tflite` variant
- ADB path: `/sdcard/Android/data/com.kernel.ai.debug/files/models/`

### Google Pixel 10 (Tensor G5)
- Backend: **GPU** (ARM Immortalis-G925 via LiteRT `Backend.GPU`)
- The Qualcomm NPU delegate is **not used** — Pixel 10 will transparently use GPU
- Recommended EmbeddingGemma: generic `embeddinggemma-300M_seq512_mixed-precision.tflite`
- ADB path: `/sdcard/Android/data/com.kernel.ai.debug/files/models/`
- Min Android: 16 (ships with Android 16) ✅

> **Note:** End-user in-app download for gated models is tracked as a Phase 2 task.
