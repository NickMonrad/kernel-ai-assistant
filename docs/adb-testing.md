# ADB Testing Guide — Samsung Galaxy S23 Ultra

> **Target device:** Samsung Galaxy S23 Ultra, Android 16 / One UI 8.0, Snapdragon 8 Gen 2, 12 GB RAM  
> **Expected hardware tier:** `FLAGSHIP` → NPU backend → E-4B model → 8192 token context

---

## 1. One-Time Setup

### 1.1 Enable Developer Options (One UI 8.0)

1. Open **Settings → About phone → Software information**
2. Tap **Build number** 7 times until "Developer mode enabled" toast appears
3. Enter your lock screen PIN/password if prompted

### 1.2 Enable USB Debugging

1. **Settings → Developer options** (now visible near the bottom of Settings)
2. Enable **USB debugging**
3. Enable **Install via USB** (required for sideloading)
4. *Optional but recommended:* Enable **Wireless debugging** for cable-free sessions

### 1.3 One UI-specific gotchas

| Issue | Fix |
|-------|-----|
| `adb: error: failed to get feature set` | Make sure USB mode is **MTP** (not charging only) — pull down notification shade and tap the USB mode banner |
| Authorisation dialog doesn't appear | Lock/unlock device; dialog appears on the lock screen on One UI 8 |
| `INSTALL_FAILED_USER_RESTRICTED` | Settings → Developer options → **Allow ADB installs** ON |
| NPU init fails / fallback to GPU | Expected on first run — the Hexagon delegate sometimes needs a warm cache; retry once |

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

# After E-4B + FunctionGemma + E-2B downloads complete (~6 GB total):

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

---

## 5. TTFT Benchmarks

### 5.1 Capture a baseline

Send the same prompt 3 times and record `TTFT` from logcat:

```bash
adb logcat | grep "TTFT\|Generation complete"
```

**Standard benchmark prompts:**

| # | Prompt | Expected TTFT target |
|---|--------|---------------------|
| 1 | `"Hello"` | < 500 ms (NPU) / < 1500 ms (GPU) |
| 2 | `"What is the capital of France?"` | < 800 ms (NPU) / < 2000 ms (GPU) |
| 3 | `"Write a haiku about rain"` | < 800 ms (NPU) / < 2000 ms (GPU) |
| 4 | `"Summarise the plot of Hamlet in 3 sentences"` | < 1200 ms |

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

---

## 8. Useful ADB Commands

```bash
# Clear app data (simulates fresh install / first launch)
adb shell pm clear com.kernel.ai.debug

# View downloaded model files
adb shell ls -lh /data/data/com.kernel.ai.debug/files/models/

# Check available storage
adb shell df /data/data/com.kernel.ai.debug/

# Force-stop and restart app
adb shell am force-stop com.kernel.ai.debug
adb shell monkey -p com.kernel.ai.debug 1

# Pull a bugreport for CI/issue reporting
adb bugreport ~/Desktop/kernel-ai-bugreport.zip
```
