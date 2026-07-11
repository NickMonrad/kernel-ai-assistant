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

Fixture dry run — no ADB command or device state change (uses hierarchical UID block format):

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
  s21/start/    s21/end/  s21/bugreport/
  s23u/start/   s23u/end/ s23u/bugreport/
  sanitized/run-summary.json
  sanitized/run-summary.md
```

The private tree contains complete Batterystats (`--charged` and `--checkin`), power, package, procstats, meminfo, service, batteryproperties, device-idle, aggregate WakeWordDiag, and end-only bugreport artifacts. Start-boundary raw evidence (battery, batteryproperties, power, deviceidle, services, package/UID mapping) is persisted separately for auditability. Inspect it locally, never with `git add`:

```bash
git check-ignore -v scripts/private-battery-runs/example/s21/end/batterystats-charged.txt
```

The public JSON/Markdown summary is sanitised and validates that it contains no ADB selector, IP address, pairing code, account/email identifier, home path, raw artifact name, or unfiltered log.

On abort (precondition failure, ADB loss, KeyboardInterrupt, parser error), partial evidence is preserved and a non-evidentiary abort summary is written. The abort summary marks the run as `ABORTED_NON_EVIDENTIARY` and records which precondition failed.

### Manual operator gates

The physical harness stops at each gate; typing `START` is required to proceed.

**Disabled baseline**

1. The harness validates both Samsung identities, Android metadata, package/UID/version, and wireless ADB.
2. The operator manually disables Listen for Hey Jandal on both devices and confirms.
3. The harness requires both wake-word services to be inactive and `WakeWordDiag` not DEBUG.
4. The operator manually unplugs charger and USB, turns both displays off, and locks both devices; then confirms.
5. The harness rejects the pair unless both devices report AC/USB/wireless false, discharging/not-charging status, screen off (confirmed via Wakefulness, mScreenOn, or Display Power), and service inactive. It resets Batterystats only after this accepted boundary.

**Enabled treatment**

1. The operator manually enables Listen for Hey Jandal, verifies the intended build/model assets, completes one pre-test spoken wake smoke per device, and confirms.
2. The harness requires both services to be active, then sets only `WakeWordDiag` DEBUG.
3. The operator restarts/re-arms each detector and confirms a summary can be observed before proceeding. The harness never changes the toggle, clears data, removes models, or uninstalls.
4. The same manual unplug/screen-lock gate and both-device validation applies before Batterystats reset.

If either device fails any gate, the attempt is aborted for **both** devices. It is not a valid single-device result. The harness uses boundary snapshots rather than continuous polling; end-state loss, charging, service state, or missing evidence invalidates the pair.

## Real-device formats now supported

### Android UID mapping

The harness converts decimal UIDs to Android's `u0aNNN` textual form:

| Decimal UID | Textual UID | User |
|---|---|---|
| 10123 | u0a10123 | Primary (user 0) |
| 1010123 | u10a10123 | Secondary (user 10) |

The conversion supports any user ID, not just user 0. Parsing handles both forms.

### Batterystats human-readable (`--charged`)

Hierarchical `Uid u0aNNN:` blocks are extracted by finding the block for the resolved UID, then parsing nested fields within it:

- `cpu:` block for user/system CPU time in ms
- `Wake lock:` entries for named partial wakelocks with human-readable durations (e.g. `+4s200ms`)
- `Foreground:` activity duration
- `Service:` started-service uptime
- `Audio:` duration
- `power:` estimated mAh

If the UID block is not found, all fields return `not_reported`. If no UID was resolved, all fields return `unsupported`.

### Batterystats check-in (`--checkin`)

Comma-separated records with tag-based positioning:

| Tag | Columns | Parsed fields |
|---|---|---|
| `uid` | `,<decimal_uid>,cpu,<user_ms>,<system_ms>` | CPU user, kernel |
| `wl` | `,<decimal_uid>,<name>,<duration_ms>,<count>` | Wakelock name + duration |
| `sf` | `,<decimal_uid>,<duration_ms>,<count>` | Foreground service duration |
| `pr` | `,<decimal_uid>,<proc>,<user_ms>,<system_ms>,...` | Process CPU user, kernel |
| `au` | `,<decimal_uid>,<duration_ms>,<count>` | Audio duration |

Header/metadata rows (`i`, `l`, `f` tags) are skipped. Comment lines starting with `#` are ignored.

### Screen-off detection

The harness checks multiple indicators in order:

1. `dumpsys deviceidle`: `mScreenOn=false`
2. `dumpsys power`: `mScreenOn=false`
3. `dumpsys power`: `Wakefulness=Asleep` or `Wakefulness=Doze`
4. `dumpsys power`: `Display Power: state=OFF`

If none of these are found, the precondition raises `HarnessError` — an assumed screen-off result is never accepted.

### Charging/discharging status

Samsung Android 15 uses `status: 3` (DISCHARGING) or `status: 4` (NOT_CHARGING) when unplugged. Both are accepted. If the powered flags (AC/USB/Wireless) or status field are absent, the precondition raises `HarnessError`.

## Derived metrics

### Whole-device battery

| Metric | Source | Unit |
|---|---|---|
| Battery delta | Start/end `level` | Percentage points |
| Rate | Delta / duration | pp/h |
| Charge-counter delta | Start/end `Charge counter` | µAh |
| mAh consumed | Charge delta / 1000 | mAh |
| mAh per hour | mAh / duration | mAh/h |

Charge-counter convention: `delta = start_counter − end_counter`. Positive = consumption. Negative values indicate charging occurred during the window.

### Battery health and capacity

| Metric | Source | Notes |
|---|---|---|
| Capacity | `dumpsys batteryproperties` | µAh |
| Charge counter | `dumpsys batteryproperties` | µAh |
| Current now | `dumpsys batteryproperties` | µA (negative = discharging) |
| Voltage | `dumpsys battery` | mV |
| Temperature | `dumpsys battery` | tenths °C |
| Health | `dumpsys battery` | numeric (2 = good) |
| Cycle count | `dumpsys battery` | where exposed |
| Full-charge capacity | `dumpsys battery` | µAh |
| Design capacity | `dumpsys batteryproperties` | µAh (where exposed) |

### App attribution

Fields are sourced from Batterystats human-readable (primary), check-in records (secondary), and procstats (tertiary):

| Field | Primary source | Check-in source |
|---|---|---|
| CPU user | `cpu:` block | `uid,cpu` / `pr,` record |
| CPU kernel/system | `cpu:` block | `uid,cpu` / `pr,` record |
| Partial wakelocks | `Wake lock:` entries | `wl,` record |
| Foreground service | `Foreground:` block | `sf,` record |
| Service uptime | `Service:` block | — |
| Audio duration | `Audio:` block | `au,` record |
| Estimated power | `power:` line | — |

All fields carry explicit `available`, `not_reported`, `unsupported`, `parse_failed`, or `not_applicable` states. Genuine zero is never confused with missing data.

### Device-idle and unrelated drain

| Metric | Source |
|---|---|
| Start/end Doze state | `dumpsys deviceidle` `mState=` |
| Screen-on state | `dumpsys power` and `deviceidle` |
| Wakefulness | `dumpsys power` `Wakefulness=` |
| Charging resumed | `mCharging` at both boundaries |
| Reboot detection | Monotonic clock comparisons |
| Top other consumers | Other UIDs sorted by estimated power (sanitised as `system`, `other_uid_N`) |

Unrelated-app identities are replaced with generic labels (`system`, `radio`, `wifi`, `display`, `other_uid_1`, `other_uid_2`) to avoid publishing the user's installed-app inventory.

## Failure-safe diagnostic cleanup

When running in **enabled** mode, the harness sets `WakeWordDiag` to `DEBUG`. Cleanup is guaranteed through a `try/finally` block:

- Cleanup runs independently on both devices
- Catches `HarnessError`, `OSError`, `TimeoutExpired`, and `KeyboardInterrupt`
- Each cleanup failure is recorded as a warning but does not mask the original error
- After cleanup, the property is verified to no longer be `DEBUG`
- If cleanup cannot be confirmed, a sanitised warning is printed identifying only the device alias

The operator may still need to restart or re-arm the detector because the logging decision is latched for the current detector run.

## Abort behaviour

When a paired run aborts:

1. Private evidence already captured is preserved (start boundary, partial end data)
2. A sanitised abort summary is written in both JSON and Markdown
3. The classification is set to `ABORTED_NON_EVIDENTIARY`
4. The failing device alias and precondition are recorded
5. No valid comparison result is produced
6. The run never continues with only one phone

## Bugreport sequencing

The official end boundary (battery, power, deviceidle, services, batterystats) is always captured **before** either bugreport begins. This ensures the long bugreport capture does not alter the official elapsed battery window. Timestamps for end boundary, bugreport start, and bugreport end are all recorded.

## Boundary evidence and parsing

Accepted starts are timestamped with wall clock and monotonic time; start skew is reported. Start raw evidence (battery, batteryproperties, power, deviceidle, services, and package/UID mapping) is persisted to the private run directory for auditability.

End capture records `dumpsys battery`, `batteryproperties`, complete `batterystats --charged`, complete `batterystats --checkin`, power, device idle, package/service, procstats, and meminfo. The harness resolves package UID from complete package output, converting it to the Android textual UID form (`u0aNNN`) for UID block extraction. Check-in records are parsed by record tag and positional column.

Every parsed value carries an explicit state: `available`, `not_reported`, `unsupported`, `parse_failed`, or `not_applicable`. Missing OEM fields never become zero. Battery charge counter, voltage, temperature, health, full-charge capacity, and cycle count are reported only when exposed. Enabled runs parse only aggregate `WakeWordDiag` summaries; disabled runs mark wake metrics not applicable. NNAPI assignment remains inconclusive unless native provider evidence proves assignment.

After enabled end collection, the harness restores the diagnostic tag through `try/finally` on both devices independently. The operator restarts/re-arms the detector if it will continue running. The harness never enables shared `KernelAI` DEBUG logging.

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

After the final capture, restore the dedicated diagnostic tag through the harness `try/finally` block. On each device:

```bash
adb shell setprop log.tag.WakeWordDiag INFO
```

Stop and re-arm or restart the detector after restoring the property if it will continue running. Its current run retains the DEBUG decision made when it started. If the harness exited abnormally, cleanup is still attempted on both devices independently; warnings are printed but the original failure is preserved.

## Known unsupported fields per device

### S21 (SM-G991B, Android 15)
- `Display Power: state=` is not exposed in `dumpsys power`
- Cycle count may not be exposed depending on kernel/battery firmware
- Design capacity may not be exposed in `dumpsys batteryproperties`

### S23 Ultra (SM-S918B, Android 15)
- `Display Power: state=OFF` is exposed
- Cycle count and design capacity availability depends on firmware version

Both devices report the standard `dumpsys battery`, `batteryproperties`, `batterystats`, power, and deviceidle fields documented above.
