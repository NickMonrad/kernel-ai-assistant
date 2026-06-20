# ADB Testing Guide

| Device | Chip | RAM | Backend | Tier | Role |
|--------|------|-----|---------|------|------|
| Samsung Galaxy S23 Ultra | Snapdragon 8 Gen 2 (SM8550) | 12 GB | NPU | FLAGSHIP | Reference device — primary target |
| Google Pixel 10 | Tensor G5 | 12 GB | GPU | FLAGSHIP | Reference device — GPU-only |
| Samsung Galaxy S21 (Exynos) | Exynos 2100 | 8 GB | GPU | FLAGSHIP | Tracked reliability signal — see #1089 / #684 |
| Honor Magic 8 Pro | Snapdragon 8 Elite | 12 GB | NPU | FLAGSHIP | Future tracked / reference candidate |

---

## 1. One-Time Setup

### 1.1 Enable Developer Options — Pixel 10 (stock Android 16)

1. Open **Settings → About phone**
2. Tap **Build number** 7 times until "Developer mode enabled" toast appears
3. Enter your lock screen PIN/password if prompted
4. **Settings → System → Developer options → USB debugging** ON

### 1.2 Enable Developer Options — Samsung S23 Ultra (One UI 8.0)

1. Open **Settings → About phone → Software information**
2. Tap **Build number** 7 times until "Developer mode enabled" toast appears
3. Enter your lock screen PIN/password if prompted
4. **Settings → Developer options** (now visible near the bottom of Settings)
5. Enable **USB debugging**
6. Enable **Install via USB** (required for sideloading)
7. *Optional but recommended:* Enable **Wireless debugging** for cable-free sessions

### 1.3 Device-specific gotchas

| Device | Issue | Fix |
|--------|-------|-----|
| S23 Ultra | `adb: error: failed to get feature set` | Make sure USB mode is **MTP** (not charging only) — pull down notification shade and tap the USB mode banner |
| S23 Ultra | Authorisation dialog doesn't appear | Lock/unlock device; dialog appears on the lock screen on One UI 8 |
| S23 Ultra | `INSTALL_FAILED_USER_RESTRICTED` | Settings → Developer options → **Allow ADB installs** ON |
| S23 Ultra | NPU init fails / fallback to GPU | Expected on first run — the Hexagon delegate sometimes needs a warm cache; retry once |
| Pixel 10 | `adb: no permissions` | Disconnect and reconnect the USB cable, then accept the RSA key dialog on screen |
| Pixel 10 | GPU delegate init slow on first run | Expected — Pixel 10 JIT-compiles GPU shaders on first inference (~5–10 s); subsequent launches are fast |

### 1.4 Verify connection

```bash
adb devices
# Expected: <serial>  device
```

---

## 2. Build & Install

```bash
# From repo root — ensure JDK 21 and Android SDK are on PATH
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=~/Library/Android/sdk

./gradlew assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 3. Logcat Filters

Kernel AI emits structured log tags. Use these filters for focused output:

### 3.1 All Kernel AI logs

```bash
adb logcat -s \
  HardwareProfileDetector:I \
  LiteRtInferenceEngine:I \
  ModelDownloadManager:I \
  ModelDownloadWorker:I \
  KernelAI:V
```

### 3.2 Benchmark-only (TTFT + generation timing)

```bash
adb logcat | grep -E "TTFT|Generation complete|Engine ready|Hardware profile"
```

### 3.3 Full verbose (includes Hilt, Room, WorkManager)

```bash
adb logcat -v time | grep -E "com\.kernel\.ai|LiteRt|Hilt"
```

---

## 4. Expected Log Output — First Launch

```
I HardwareProfileDetector: Hardware profile: tier=FLAGSHIP, ram=12 GB,
    soc=Qualcomm SM8550, npu=true, backend=NPU, maxTokens=8192

I ModelDownloadManager: Auto-queuing Gemma 4 E-4B for tier FLAGSHIP
I ModelDownloadManager: Enqueuing download for Gemma 4 E-4B

# After E-4B + E-2B downloads complete (~5 GB total):

I LiteRtInferenceEngine: Initializing engine — model: .../gemma-4-E4B-it.litertlm,
    backend: NPU, tier: FLAGSHIP
I QuantizationVerifier: Model size check OK for gemma-4-E4B-it.litertlm: 3486 MB
I LiteRtInferenceEngine: Backend NPU initialized successfully
I LiteRtInferenceEngine: Engine ready — backend: NPU, maxTokens: 8192
```

### NPU fallback (expected on first run):

```
W LiteRtInferenceEngine: Backend NPU failed: <Hexagon delegate error>
I LiteRtInferenceEngine: Backend GPU initialized successfully
I LiteRtInferenceEngine: Engine ready — backend: GPU, maxTokens: 8192
```

> The NPU delegate may require a warm cache on the first run. Subsequent launches
> should succeed on NPU after the Hexagon driver has cached the compiled model.

### Pixel 10

```
I HardwareProfileDetector: Hardware profile: tier=FLAGSHIP, ram=12 GB,
    soc=Google Tensor G5, npu=false, backend=GPU, maxTokens=8192

I LiteRtInferenceEngine: Initializing engine — model: .../gemma-4-E4B-it.litertlm,
    backend: GPU, tier: FLAGSHIP
I LiteRtInferenceEngine: Backend GPU initialized successfully
I LiteRtInferenceEngine: Engine ready — backend: GPU, maxTokens: 8192
```

### GPU shader warm-up (Pixel 10, first run only):

```
# First run only — GPU shader compilation (Pixel 10 Immortalis-G925):
W LiteRtInferenceEngine: GPU shader compilation in progress (~5–10s first run)
I LiteRtInferenceEngine: Backend GPU initialized successfully
```

---

## 5. TTFT Benchmarks

### 5.1 Capture a baseline

Send the same prompt 3 times and record `TTFT` from logcat:

```bash
adb logcat | grep "TTFT\|Generation complete"
```

**Standard benchmark prompts:**

| # | Prompt | S23 Ultra NPU | S23 Ultra GPU (fallback) | Pixel 10 GPU |
|---|--------|--------------|--------------------------|--------------|
| 1 | `"Hello"` | < 500 ms | < 1500 ms | < 2000 ms |
| 2 | `"What is the capital of France?"` | < 800 ms | < 2000 ms | < 2500 ms |
| 3 | `"Write a haiku about rain"` | < 800 ms | < 2000 ms | < 2500 ms |
| 4 | `"Summarise the plot of Hamlet in 3 sentences"` | < 1200 ms | — | < 3500 ms |

### 5.2 Reading the log output

```
I LiteRtInferenceEngine: TTFT (Time to First Token): 423ms [backend=NPU]
I LiteRtInferenceEngine: Generation complete: total=4821ms, TTFT=423ms [backend=NPU]
```

### 5.3 Recording results

Add results to this table as you benchmark:

| Date | Build | Backend | Model | Prompt | TTFT (ms) | Total (ms) |
|------|-------|---------|-------|--------|-----------|------------|
| — | — | — | — | — | — | — |

---

## 6. Memory Monitoring

### 6.1 Check RAM usage during inference

```bash
# While a generation is in flight:
adb shell dumpsys meminfo com.kernel.ai.debug | grep -E "TOTAL|Native|Java"
```

**Expected on S23 Ultra (E-4B + 8192 tokens):**
- Native heap: ~4–5 GB (model weights + KV-cache)
- Java heap: < 100 MB
- Total PSS: < 5.5 GB (comfortable on 12 GB)

**Expected on Pixel 10 (E-4B + 8192 tokens, GPU):**
- Native heap: ~4–5 GB (similar to S23 Ultra — GPU driver manages VRAM separately)
- Java heap: < 100 MB
- Total PSS: < 5.5 GB

### 6.2 Watch for Low Memory Killer

```bash
adb logcat | grep -i "lmk\|lowmemory\|killing"
```

If LMK events appear, the KV-cache or model size is too large — reduce `maxTokens` or
switch to the E-2B model in `HardwareProfileDetector`.

### 6.3 LeakCanary

LeakCanary is included in debug builds. After closing and reopening the app several times:
- Check for a **"LeakCanary"** notification
- If a leak is reported, tap it for a full heap dump and stack trace

---

## 7. Wireless Debugging (Optional)

For cable-free testing during longer sessions:

```bash
# 1. On device: Settings → Developer options → Wireless debugging → Enable
# 2. Tap "Pair device with pairing code" — note the IP:port and 6-digit code
adb pair <device-ip>:<pairing-port>  # enter 6-digit code when prompted
adb connect <device-ip>:<debugging-port>
adb devices  # confirm connected
```

> **Pixel 10:** Uses the same stock Android wireless debugging flow as described above — no device-specific changes needed.

---

## 8. Useful ADB Commands

```bash
# Clear app data (simulates fresh install / first launch)
adb shell pm clear com.kernel.ai.debug

# View downloaded model files (primary path — external storage)
adb shell ls -lh /sdcard/Android/data/com.kernel.ai.debug/files/models/

# Fallback (internal storage, if external unavailable):
adb shell run-as com.kernel.ai.debug ls -lh files/models/

# Check available external storage
adb shell df /sdcard/Android/data/com.kernel.ai.debug/

# Force-stop and restart app
adb shell am force-stop com.kernel.ai.debug
adb shell monkey -p com.kernel.ai.debug 1

# Pull a bugreport for CI/issue reporting
adb bugreport ~/Desktop/kernel-ai-bugreport.zip
```

---

## 9. Running the `llm_tools` Harness

The `llm_tools` harness phase validates E2E model tool-call generation (Tier 2 → FallThrough → Tier 3 / Gemma).

```bash
# Run just the llm_tools phase
ANDROID_SERIAL=R5CR605B71K python3 scripts/adb_skill_test.py --phases=llm_tools

# Wireless device
ANDROID_SERIAL=100.76.134.49:44599 python3 scripts/adb_skill_test.py --phases=llm_tools

# Dry run (no device needed)
python3 scripts/adb_skill_test.py --dry-run --phases=llm_tools
```

See [`docs/automated-testing.md`](./automated-testing.md) for detailed output format and
[`docs/testing/llm-tools-harness.md`](./testing/llm-tools-harness.md) for deep reference.

---

## 10. Running the `false_positives` Harness

The `false_positives` harness phase (phase 11) validates negative routing — queries that
resemble intent-driven commands but should NOT trigger a native intent.

```bash
# Run just the false_positives phase
ANDROID_SERIAL=R5CR605B71K python3 scripts/adb_skill_test.py --phases false_positives

# Dry run (no device needed)
python3 scripts/adb_skill_test.py --dry-run --phases false_positives
```

### Timer/alarm cleanup

The `false_positives` phase includes a `Set a 5 minute egg timer` case (`id:
set_a_5_minute_egg_timer`) with `allowed_intents=["set_timer"]` — the model may
legitimately create a real timer. The harness automatically stops Jandal ClockAlertService
alerts:

- **Before the run:** Pre-run cleanup cancels any active alerts. Uses **checked ADB**
  commands — if cleanup fails the run aborts immediately (exit code 46).
- **After the timer case:** The harness immediately cleans up if a timer/alarm intent fired.
  A cleanup failure here is tracked and causes non-zero exit at the end.
- **After the run:** Final cleanup stops all alerts, dismisses notifications, and
  force-stops the app as a last resort. Failure returns exit code 46.
- **On cleanup failure:** Exit code 46 (`EXIT_CLEANUP_FAILED`) is returned, and the
  harness prints which ADB command failed and why. Manually stop the app if buzzing
  persists:

  ```bash
  adb shell am force-stop com.kernel.ai.debug
  ```

See [`docs/automated-testing.md`](./automated-testing.md) for the full oracle semantics
and failure interpretation.

### S21/S23U runs

The harness must not leave active timers/alarms on the device after any run. If you hear
buzzing after a test completes:

1. The post-run cleanup should have stopped it — check the exit code.
2. If exit code was 46, run `adb shell am force-stop com.kernel.ai.debug` manually.
3. File a bug for any persistent cleanup gaps (tracked in #1275).

---

## 11. Running the `permission_flows` connected suite

The `permission_flows` suite validates contextual permission UX surfaces and
Settings round-trips for #1140 permission orchestration. It is a UI Automator /
connected-test suite, not a model quality suite: it drives Actions with the
existing `quick_action_input` test extra and does not require LLM inference,
Gemma downloads, or S23 Ultra-only behaviour.

### Device requirement

- **Default device:** Samsung Galaxy S21 (`s21-exynos`, physical). Use this for
  PR evidence unless the issue explicitly asks for another device.
- **Optional secondary device:** S23 Ultra only for OEM/API-specific validation.
- **If the S21 is unavailable:** stop and ask Nick for ADB access. Do not mark
  device validation complete without S21 evidence.

### Permission/appops reset

The harness resets runtime permission state where Android allows shell control:

```bash
adb shell pm clear-permission-flags com.kernel.ai.debug android.permission.CALL_PHONE user-set user-fixed
adb shell pm revoke com.kernel.ai.debug android.permission.CALL_PHONE
```

DND notification-policy access is special access, not a normal runtime
permission. The connected test makes a best-effort call:

```bash
adb shell cmd notification disallow_dnd com.kernel.ai.debug
```

If Samsung One UI keeps DND access granted, revoke it manually in Android
Settings before running the suite. The harness verifies Settings launch/return
and blocked repair state; it does **not** claim fully stable automated toggling
of the DND Settings switch.

### Run locally on S21

```bash
ANDROID_SERIAL=R5CR605B71K ./gradlew :app:installDebug :feature:settings:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.kernel.ai.feature.settings.PermissionFlowContextualSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.unlock_pin=<PIN-if-locked>
```

Omit `unlock_pin` when the S21 is already unlocked. If the device remains on the
lock screen and no PIN is available, stop and ask Nick for ADB/unlock access.

Covered smoke cases:

- `handsFreeCalling_revokedShowsContextualSurface`
- `handsFreeCalling_permanentDenialNavigatesToAppPermissions`
- `dndSpecialAccess_settingsRoundTripShowsBlockedRepair`

Future #1140 child slices should add cases to this class (or shared helpers)
instead of creating ad hoc permission UI Automator tests.

### Generate normalized #1113 evidence

After the connected test finishes:

```bash
PR_NUMBER="$(gh pr view --json number --jq .number)"
PR_HEAD_SHA="$(git rev-parse HEAD)"

python3 scripts/generate_permission_flow_evidence.py \
  --source on_device \
  --suite permission_flows \
  --pr "$PR_NUMBER" \
  --commit "$PR_HEAD_SHA" \
  --branch "$(git branch --show-current)" \
  --device-id s21-exynos \
  --results-dir feature/settings/build/outputs/androidTest-results/connected/ \
  --out-dir "scripts/test-reports/normalised/pr-$PR_NUMBER/"
```

The JSON output uses the existing #1113 schema:

```json
{
  "source": "on_device",
  "suite": "permission_flows",
  "device": {
    "id": "s21-exynos",
    "execution": "physical"
  },
  "summary": {
    "total": 3,
    "passed": 3,
    "failed": 0,
    "pass_rate": 1.0
  },
  "cases": []
}
```

Publish or attach evidence using the standard #1113 workflow:

```bash
python3 scripts/publish_test_evidence.py \
  --input "scripts/test-reports/normalised/pr-$PR_NUMBER/" \
  --pr "$PR_NUMBER" \
  --commit "$PR_HEAD_SHA" \
  --dry-run
```

For PR summaries, include the generated JSON/Markdown path, device ID
(`s21-exynos`), command output, pass/fail summary, and any skipped
OS/OEM-boundary reason.
