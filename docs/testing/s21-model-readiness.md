# S21 Model Readiness Preflight

After a clean reinstall, the required conversation model (Gemma 4 E-2B) must be downloaded and the inference engine initialised before ADB test evidence can be trusted. This preflight automates that bootstrap.

## Target

- **Device:** S21 over USB ADB
- **Model tier:** Gemma 4 E-2B (S21 launch-compatible)
- **Model file:** `gemma-4-E2B-it.litertlm`

## Selecting the device

Two equivalent ways:

1. `ANDROID_SERIAL=R5CR605B71K` environment variable (preferred — works with any tool)
2. `--serial R5CR605B71K` where supported (standalone module only)

## Recommended standalone command

```bash
ANDROID_SERIAL=R5CR605B71K python3 -m adb_harness.model_readiness \
  --unlock-pin <PIN> \
  --timeout-download 480 \
  --timeout-engine 120 \
  --json
```

### Flags

| Flag | Description |
|------|-------------|
| `--unlock-pin <PIN>` | Device unlock PIN to dismiss keyguard (required if device has PIN lock) |
| `--timeout-download 480` | Max seconds to wait for model download (default 600; 480 is enough for S21 on USB) |
| `--timeout-engine 120` | Max seconds to wait for engine initialisation after download |
| `--json` | Prints structured evidence record to stdout (use with `> evidence.json` to capture) |
| `--serial` | Explicit device serial if `ANDROID_SERIAL` isn't set |

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | Model ready — `final_state=Ready`, `engine_ready=true` |
| 44 | Model not ready (MODEL_NOT_READY bucket — download never started and model not present) |
| 45 | Preflight crashed (unexpected error) |

## Evidence JSON structure (`--json`)

```json
{
  "device_serial": "R5CR605B71K",
  "required_model": "Gemma 4 E-2B",
  "model_file": "gemma-4-E2B-it.litertlm",
  "initial_state": "<state>",
  "hf_signin_shown": false,
  "hf_signin_clicked": false,
  "download_triggered": true,
  "readiness_wait_seconds": 245.3,
  "final_state": "Ready",
  "failure_bucket": null,
  "logcat_markers": {}
}
```

| Field | Type | Description |
|-------|------|-------------|
| `device_serial` | string | Target device serial number |
| `required_model` | string | Expected model name (`Gemma 4 E-2B`) |
| `model_file` | string | Expected model filename on device |
| `initial_state` | string \| null | State detected before preflight began |
| `hf_signin_shown` | bool | Whether a Hugging Face sign-in dialog was observed |
| `hf_signin_clicked` | bool | Whether HF sign-in was clicked |
| `download_triggered` | bool | Whether download was initiated |
| `readiness_wait_seconds` | float | Time spent waiting for engine readiness |
| `final_state` | string \| null | Final state of the preflight |
| `failure_bucket` | string \| null | Failure classification, or null on success |
| `logcat_markers` | object | Detected logcat markers during preflight |

## Typical workflow

1. Reinstall the APK on the S21.
2. Run the preflight with `--json` to capture evidence.
3. Check `final_state` is `Ready` and `failure_bucket` is `null`.
4. Proceed with ADB test runs.

## Troubleshooting

- **`hf_signin_shown=true`** — The app is prompting for Hugging Face credentials. Enter them on the device before running the preflight, or dismiss the dialog manually.
- **`timeout-download` too short** — The model download was interrupted. Increase the timeout for slow connections.
- **`failure_bucket=MODEL_NOT_READY`** — The model file was never downloaded and the download path was not triggered. Verify the app is installed and the device has network access.
