# Local model files (dev only)

This directory is gitignored. Place manually downloaded `.litertlm` model files here for local development and testing. Then push to the device with ADB:

```bash
# Push a model to the app's internal storage
adb push models/<filename>.litertlm /data/user/0/com.kernel.ai.debug/files/models/<filename>.litertlm

# Verify
adb shell run-as com.kernel.ai.debug ls -lh files/models/
```

## Gated models (require HuggingFace login + license acceptance)

| File | Source |
|------|--------|
| `mobile_actions_q8_ekv1024.litertlm` | [litert-community/functiongemma-270m-ft-mobile-actions](https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions) |

> **Note:** End-user download authentication is tracked as a Phase 2 task.
