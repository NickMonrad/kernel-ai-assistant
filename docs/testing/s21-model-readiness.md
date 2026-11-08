# S21 Model Readiness Preflight

After a clean reinstall, the required conversation model (Gemma 4 E-2B) must be downloaded and the inference engine initialised before ADB test evidence can be trusted. This preflight automates that bootstrap.

## Target

- **Device:** S21 over USB ADB (configured selector; do not commit the hardware serial)
- **Model tier:** Gemma 4 E-2B (S21 path — not S23U E4B)
- **Model file:** `gemma-4-E2B-it.litertlm`

## Selecting the device

Two equivalent ways:

1. `ANDROID_SERIAL=$JANDAL_S21_TARGET` environment variable (preferred — works with any tool)
2. `--serial "$JANDAL_S21_TARGET"` where supported

## Integrated harness command (recommended)

For full runs including model readiness preflight + ADB skill tests:

```bash
ANDROID_SERIAL="$JANDAL_S21_TARGET" python3 scripts/adb_skill_test.py \
  --model-readiness \
  --serial "$JANDAL_S21_TARGET" \
  --unlock-pin <PIN> \
  --timeout-download 480 \
  --timeout-engine 120 \
  --case <MODEL_BACKED_SMOKE_CASE>
```

The `--model-readiness` flag runs the preflight before any tests. If it fails
(exit 44), no tests execute — the report clearly distinguishes setup failure
from product regression.

## Standalone preflight (diagnostic only)

```bash
PYTHONPATH=scripts ANDROID_SERIAL="$JANDAL_S21_TARGET" python3 -m adb_harness.model_readiness \
  --serial "$JANDAL_S21_TARGET" \
  --unlock-pin <PIN> \
  --timeout-download 480 \
  --timeout-engine 120 \
  --json
```

> `PYTHONPATH=scripts` is required when running the module from repo root.
> Alternatively, `cd scripts && python3 -m adb_harness.model_readiness ...`.

### Flags

| Flag | Description |
|------|-------------|
| `--unlock-pin <PIN>` | Device unlock PIN to dismiss keyguard (required if device has PIN lock) |
| `--timeout-download 480` | Max seconds to wait for model download (default 360; 480 is enough for S21 on USB) |
| `--timeout-engine 120` | Max seconds to wait for engine initialisation after download |
| `--json` | Prints structured evidence record to stdout (use with `> evidence.json` to capture) |
| `--serial` | Explicit device serial if `ANDROID_SERIAL` isn't set |

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | Model ready — `final_state=Ready`, `engine_ready=true` |
| 44 | Model not ready — see `failure_bucket` for detail |
| 45 | Preflight crashed (unexpected error) |

Bootstrapping failures (44-45) indicate a **setup/environment issue**, not a
product regression. Treat them as environment health checks and resolve the
underlying device or network state before retrying.

## Required acceptance evidence

Before merging PRs that touch model readiness logic, an agent must validate on
a physical S21 over USB ADB using the locally configured selector and attach
evidence showing:

- Device is S21 (not S23U default — gated-model path differs)
- Selected model is Gemma 4 E-2B (not E4B)
- `final_state = Ready`
- `failure_bucket = null`
- `logcat_markers.engine_ready = true`
- Model readiness evidence is present in the generated JSON report
- At least one model-backed smoke test ran after readiness

## Evidence JSON structure (`--json`)

```json
{
  "device_serial": "<redacted-local-selector>",
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

## Failure bucket reference

Model readiness bootstrap failure **is not a product regression**. These
buckets indicate environment, network, or device state problems:

| Bucket | Meaning |
|--------|---------|
| `MODEL_NOT_READY` | Download never started and model not present — verify app installed and network accessible |
| `MODEL_DOWNLOAD_TIMEOUT` | Download started but didn't finish within timeout — check network speed and screen-on |
| `MODEL_DOWNLOAD_FAILED` | Download worker errored — check logcat for error detail |
| `ENGINE_NOT_READY` | Engine didn't init after download — try longer timeout |
| `ENGINE_BLOCKED_BY_KEYGUARD` | Lock screen preventing engine init — use `--unlock-pin` |

## Typical workflow

1. Reinstall the APK on the S21.
2. Run the integrated harness with `--model-readiness` or standalone with `--json`.
3. Check `final_state` is `Ready` and `failure_bucket` is `null`.
4. Confirm model readiness evidence in generated report JSON.
5. Confirm at least one model-backed smoke test ran after readiness.
6. Proceed with ADB test runs.

## Troubleshooting

- **`timeout-download` too short** — The model download was interrupted. Increase the timeout for slow connections.
- **`failure_bucket=MODEL_NOT_READY`** — The model file was never downloaded and the download path was not triggered. Verify the app is installed and the device has network access.
- **`failure_bucket=MODEL_DOWNLOAD_TIMEOUT`** — Download started but didn't finish. Check device network connectivity and power (screen-on recommended).
- **`failure_bucket=ENGINE_NOT_READY` or `ENGINE_BLOCKED_BY_KEYGUARD`** — Engine init failed; try with `--unlock-pin` to keep device unlocked.

### HF sign-in automation

The preflight automatically taps "Sign in to Hugging Face" when the dialog appears,
then dismisses the dialog with Back so the device is left in a clean state.
The OAuth flow proceeds in the background browser; if the device is already
signed into HuggingFace (cached browser session), the gated models will
auto-queue on the next app check. See
[model_readiness.py](../../scripts/adb_harness/model_readiness.py) Phase 2 for details.

This is a **best-effort** interaction. If no sign-in dialog is detected, the
preflight proceeds directly to the download/engine polling loop — the main
loop's timeout mechanisms (`MODEL_NOT_READY`, `MODEL_DOWNLOAD_TIMEOUT`,
`ENGINE_NOT_READY`) catch stalled preflights.
