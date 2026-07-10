# Wake-word battery test procedure

This procedure captures a controlled, **unplugged** real-device idle measurement for the Hey Jandal foreground service. It is designed for a normal, non-root Android device and intentionally uses start/end snapshots rather than continuous ADB polling.

> **Privacy:** Never put an ADB serial number, Wi-Fi address, pairing code, account name, or raw unfiltered logcat into a committed report, issue, or PR. Identify the device by sanitized model, chipset, and Android version only.

## Paired S21/S23U harness

Issue #1393 adds `scripts/battery_telemetry_harness.py` for the paired evidence required by #1391. It collects two later four-hour comparisons:

1. **Disabled baseline** — Listen for Hey Jandal disabled on both devices.
2. **Enabled treatment** — the unchanged current implementation enabled on both devices.

The primary analysis is within each device: **S21 enabled − S21 disabled** and **S23U enabled − S23U disabled**. Do not treat equal percentage-point drops across the S21 and S23U as equal energy use: battery age, capacity, chipset, firmware, and percentage granularity differ.

The harness uses sanitized aliases (`s21`, `s23u`) in public summaries. ADB selectors are accepted only as local arguments or through `JANDAL_S21_ADB` and `JANDAL_S23U_ADB`; they are redacted before any commit-safe output is generated.

### Commands

Fixture dry run — no ADB command or device state change:

```bash
python3 scripts/battery_telemetry_harness.py smoke \
  --fixture scripts/testdata/fixtures/battery_telemetry_paired_smoke.json \
  --duration 2m
```

Future physical disabled baseline, run only with the operator present:

```bash
python3 scripts/battery_telemetry_harness.py baseline-disabled \
  --s21 "$JANDAL_S21_ADB" --s23u "$JANDAL_S23U_ADB" \
  --duration 4h --interactive
```

Future physical enabled treatment, after the disabled baseline has been reviewed:

```bash
python3 scripts/battery_telemetry_harness.py enabled \
  --s21 "$JANDAL_S21_ADB" --s23u "$JANDAL_S23U_ADB" \
  --duration 4h --interactive
```

`smoke` is always labelled `NON_EVIDENTIARY_SMOKE`; fixture output is labelled `NON_EVIDENTIARY_FIXTURE_DRY_RUN`. Neither supports a battery, causal, or release recommendation.

### Private artifact flow

Physical runs write only to the gitignored `scripts/private-battery-runs/<run-id>/` tree:

```text
<run-id>/
  s21/start/  s21/end/  s21/bugreport/
  s23u/start/ s23u/end/ s23u/bugreport/
  sanitized/run-summary.json
  sanitized/run-summary.md
```

The private tree contains complete Batterystats (`--charged` and `--checkin`), power, package, procstats, meminfo, service, aggregate WakeWordDiag, and end-only bugreport artifacts. Inspect it locally, never with `git add`:

```bash
git check-ignore -v scripts/private-battery-runs/example/s21/end/batterystats-charged.txt
```

The public JSON/Markdown summary is sanitised and validates that it contains no ADB selector, IP address, pairing code, account/email identifier, home path, raw artifact name, or unfiltered log.

### Manual operator gates

The physical harness stops at each gate; typing `START` is required to proceed.

**Disabled baseline**

1. The harness validates both Samsung identities, Android metadata, package/UID/version, and wireless ADB.
2. The operator manually disables Listen for Hey Jandal on both devices and confirms.
3. The harness requires both wake-word services to be inactive and `WakeWordDiag` not DEBUG.
4. The operator manually unplugs charger and USB, turns both displays off, and locks both devices; then confirms.
5. The harness rejects the pair unless both devices report AC/USB/wireless false, discharging, screen off, and service inactive. It resets Batterystats only after this accepted boundary.

**Enabled treatment**

1. The operator manually enables Listen for Hey Jandal, verifies the intended build/model assets, completes one pre-test spoken wake smoke per device, and confirms.
2. The harness requires both services to be active, then sets only `WakeWordDiag` DEBUG.
3. The operator restarts/re-arms each detector and confirms a summary can be observed before proceeding. The harness never changes the toggle, clears data, removes models, or uninstalls.
4. The same manual unplug/screen-lock gate and both-device validation applies before Batterystats reset.

If either device fails any gate, the attempt is aborted for **both** devices. It is not a valid single-device result. The harness uses boundary snapshots rather than continuous polling; end-state loss, charging, service state, or missing evidence invalidates the pair.

### Boundary evidence and parsing

Accepted starts are timestamped with wall clock and monotonic time; start skew is reported. End capture records `dumpsys battery`, `batteryproperties`, complete `batterystats --charged`, complete `batterystats --checkin`, power, device idle, package/service, procstats, and meminfo. The harness resolves package UID from complete package output, then parses available app CPU user/kernel time, partial wakelocks, foreground-service duration, estimated app power, and check-in CPU evidence.

Every parsed value carries an explicit state: `available`, `not_reported`, `unsupported`, `parse_failed`, or `not_applicable`. Missing OEM fields never become zero. Battery charge counter, voltage, temperature, health, full-charge capacity, and cycle count are reported only when exposed. Enabled runs parse only aggregate `WakeWordDiag` summaries; disabled runs mark wake metrics not applicable. NNAPI assignment remains inconclusive unless native provider evidence proves assignment.

After enabled end collection, the harness restores:

```bash
adb shell setprop log.tag.WakeWordDiag INFO
```

The operator restarts/re-arms the detector if it will continue running. The harness never enables shared `KernelAI` DEBUG logging.

## Preconditions

- Use the approved physical device and an in-place debug install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Do **not** uninstall the application, clear app data, or remove model storage.
- Confirm the wake-word ONNX assets and the selected STT model are still present after installation.
- Confirm **Listen for Hey Jandal** is visibly enabled in Jandal's Voice settings, then capture a local screenshot. Do not commit the screenshot unless it has been reviewed for private content.
- Confirm the wake-word foreground service is running with `adb shell dumpsys activity services com.kernel.ai.debug`.
- Set the dedicated diagnostic log tag before starting the detector. The detector emits a low-frequency summary every 15 minutes and when it stops only while this tag is DEBUG:

```bash
adb shell setprop log.tag.WakeWordDiag DEBUG
```

  `Log.isLoggable` is evaluated when a detector run begins. Stop and re-arm or otherwise restart the detector after changing this property; changing it does not enable diagnostics for an already-running detector.

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
adb logcat -d -v threadtime -s WakeWordDiag:D '*:S'
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
adb logcat -d -v threadtime -s WakeWordDiag:D '*:S'
```

`WakeWordDiag` is reserved for aggregate `WakeWordDetector: diagnostics` summaries. Retain only the summary fields needed by the report; do not attach raw device logs.

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

## Cleanup

After the final capture, restore the dedicated diagnostic tag:

```bash
adb shell setprop log.tag.WakeWordDiag INFO
```

Stop and re-arm or restart the detector after restoring the property if it will continue running. Its current run retains the DEBUG decision made when it started.
