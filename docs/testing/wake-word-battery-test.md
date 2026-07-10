# Wake-word battery test procedure

This procedure captures a controlled, **unplugged** real-device idle measurement for the Hey Jandal foreground service. It is designed for a normal, non-root Android device and intentionally uses start/end snapshots rather than continuous ADB polling.

> **Privacy:** Never put an ADB serial number, Wi-Fi address, pairing code, account name, or raw unfiltered logcat into a committed report, issue, or PR. Identify the device by sanitized model, chipset, and Android version only.

## Preconditions

- Use the approved physical device and an in-place debug install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Do **not** uninstall the application, clear app data, or remove model storage.
- Confirm the wake-word ONNX assets and the selected STT model are still present after installation.
- Confirm **Listen for Hey Jandal** is visibly enabled in Jandal's Voice settings, then capture a local screenshot. Do not commit the screenshot unless it has been reviewed for private content.
- Confirm the wake-word foreground service is running with `adb shell dumpsys activity services com.kernel.ai.debug`.
- Set the diagnostic log tag before starting the detector. The detector emits a low-frequency summary every 15 minutes and when it stops only while this tag is DEBUG:

```bash
adb shell setprop log.tag.KernelAI DEBUG
```

- Perform one locked-screen spoken `Hey Jandal` activation before the measured window. Confirm activation and no crash. Record it as a **pre-test smoke activation**, not as idle-window activity.

## Build and in-place install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package com.kernel.ai.debug | grep versionName
adb shell find /sdcard/Android/data/com.kernel.ai.debug/files -maxdepth 4 -type f
```

Record the Git commit and app version. The model-file listing is inspection-only; do not include account files or private paths in the report.

## Wireless ADB before unplugging

Establish and test a wireless connection while USB is still attached. Either use Android's **Wireless debugging** pairing UI or the established USB-to-TCP handoff below. Do not publish the IP address, port, or pairing code.

```bash
adb tcpip 5555
adb shell ip -f inet addr show wlan0
adb connect <private-device-ip>:5555
adb devices
```

Run one harmless command through the wireless transport before unplugging:

```bash
adb shell dumpsys battery
```

Do not leave `adb logcat` streaming or poll the device during the idle window.

## Controlled start

After the operator physically unplugs both charger and USB cable, wait several minutes, then collect the official start state. Do not start the timer until all charging fields are false and the battery is discharging.

```bash
adb shell dumpsys battery
adb shell dumpsys power
adb shell dumpsys deviceidle
adb shell dumpsys activity services com.kernel.ai.debug
adb shell dumpsys batterystats --reset
adb shell dumpsys batterystats com.kernel.ai.debug
adb logcat -d -v threadtime -s KernelAI:D '*:S' | grep -F "WakeWordDetector: diagnostics"
```

Record locally:

- local start timestamp and battery percentage;
- `AC powered`, `USB powered`, and `Wireless powered` values; battery status; charging state;
- screen-off / locked confirmation;
- Wi-Fi or cellular network state and whether wireless ADB remained connected;
- device-idle / doze state and app wake-word service state;
- app version and Git commit;
- sanitized device model, chipset, Android version;
- selected wake-word and STT models;
- the pre-test smoke activation count (separate from measured activity);
- that batterystats was reset immediately before the controlled run.

The start battery level must be captured **after** install, settings verification, smoke activation, wireless setup, physical unplugging, and service re-verification.

## Four-hour idle window

For at least four continuous hours:

- keep the device physically unplugged and discharging;
- leave Hey Jandal enabled, the service active, screen off, and device locked;
- retain normal network connectivity;
- make no intentional voice interaction and do not actively use Jandal;
- do not run high-frequency ADB polling or a continuous log stream.

Invalidate and restart the run if charging resumes, the service stops, the device reboots, Jandal crashes, the wake-word model becomes unavailable, or a material interaction occurs. Record a minor unavoidable interaction with timestamp, reason, and duration; assess conservatively whether idle conditions remain valid.

## Controlled end and analysis

At the four-hour mark, record the end timestamp before unnecessary interaction, then collect:

```bash
adb shell dumpsys battery
adb shell dumpsys batterystats com.kernel.ai.debug
adb shell dumpsys power
adb shell dumpsys deviceidle
adb shell dumpsys activity services com.kernel.ai.debug
adb shell dumpsys meminfo com.kernel.ai.debug
adb logcat -d -v threadtime -s KernelAI:D '*:S' | grep -F "WakeWordDetector: diagnostics"
```

The `KernelAI` tag can include routed transcripts during spoken smoke checks. Never redirect, retain, attach, or share its unfiltered output. Extract only `WakeWordDetector: diagnostics` lines, then record their aggregate fields in the report; redact any accidental non-diagnostic line before it leaves the local terminal.

The relevant low-frequency `WakeWordDetector: diagnostics` lines contain:

- audio frames and Stage 1/2/3 execution counts;
- Stage 2/3 execution cadence per hour;
- silence-gate skips and skip ratio;
- verifier invocations, passes, and rejects;
- high-confidence and verifier-confirmed activations;
- NNAPI request/session status.

Interpret provider status carefully: `session_created_nnapi_requested_assignment_unverified` proves only that NNAPI was requested with `CPU_DISABLED` and the session was created. It does **not** prove node assignment or actual accelerator execution. Classify NNAPI as `inconclusive` unless native ONNX Runtime logs directly establish assignment/execution; classify configuration failure as CPU fallback only when the detector reports that outcome.

Calculate and report exact elapsed time, percentage points consumed, percentage points per hour, Stage 2/3 executions per hour, silence-gate skip ratio, verifier statistics, activation counts, service continuity, batterystats attribution, wakelock observations, and NNAPI classification. Note battery-level granularity, radio/network activity, battery age/calibration, temperature, and other system services as measurement limits.

After collecting end evidence, perform one final spoken locked-screen `Hey Jandal` activation. Record it as a **post-test smoke activation**, separately from idle-window activity.
