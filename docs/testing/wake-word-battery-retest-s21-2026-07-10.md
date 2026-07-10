# Wake-word battery retest — S21 — 2026-07-10

**Issue:** #1142  
**Result:** **Clearly excessive observed whole-device drain; comparative follow-up required.**  
**Test source:** physical device, controlled idle window; no raw device logs are committed.

## Scope and build

| Field | Value |
| --- | --- |
| Device | Samsung SM-G991B (S21), Exynos 2100 |
| Android | 15 / API 35 |
| App | In-place debug install, version 0.1.0 (version code 1) |
| Diagnostic source commit | `34e3ea1a40dd513b955baefdcbb4d58690441d5b` |
| Wake pipeline | Bundled openWakeWord ONNX mel, embedding, and classifier models |
| Verification model | Local Vosk storage was present; no verifier invocation occurred during the idle window |
| Connectivity | Normal network connectivity retained; wireless ADB stayed reachable without disclosing the endpoint |

The test followed [the repeatable battery procedure](wake-word-battery-test.md). The app was installed with `adb install -r`; no uninstall, data clearing, model removal, forced downgrade, or charging was used.

## Preconditions

- The operator visibly enabled **Listen for Hey Jandal** and a local-only screenshot confirmed the control state before the run.
- `WakeWordService` was present before the official start.
- A pre-test locked-screen spoken wake plus command passed.
- Debug-gated, 15-minute `WakeWordDetector: diagnostics` logging was enabled before the detector started. This completed run used the pre-fix diagnostic-tag configuration; #1392 changes future runs to the dedicated `WakeWordDiag` tag and no raw log output is retained here.
- Wireless ADB was tested while USB was attached, then the charger and USB cable were physically removed.
- The official start was rejected once while the screen was still on. The accepted run began only after the display reported off.

## Controlled window

| Field | Start | End |
| --- | --- | --- |
| UTC timestamp | 2026-07-10 10:00:31 | 2026-07-10 14:01:19 |
| Elapsed | — | 14,448 s / 4 h 00 m 48 s |
| Battery | 95% | 71% |
| Battery delta | — | -24 percentage points / **5.98 percentage points per hour** |
| Battery temperature | 31.1 °C | 25.4 °C |
| External power | AC=false, USB=false, wireless=false | AC=false, USB=false, wireless=false |
| Battery status | Discharging | Discharging |
| Wake-word service | Active | Active |
| Wakefulness | Dozing | Dozing |
| Device idle state | `INACTIVE` | `IDLE` |

The operator turned the display off and left the device untouched. `deviceidle` reported `mScreenOn=false` at accepted start and end. It reported `mScreenLocked=false`; therefore the automated system field is not treated as proof of lock state. There were **zero intentional voice interactions** during the measured window and no continuous log stream or ADB polling.

## Supplemental unplugged continuation

At 2026-07-10 20:35:03 UTC, after the official end and the post-test smoke interaction, one additional S21 snapshot recorded 32% battery, still discharging with all external-power fields false. `WakeWordService` was active; the device was Dozing with `mScreenOn=false` and `mLightState=IDLE`.

This is **not** a second controlled measurement: it includes the post-test interaction and its intervening conditions were not controlled. It nevertheless records a further 39-point decrease over 23,623 s / 6 h 33 m 43 s (5.94 percentage points/hour). It is reported only as corroborating context, not combined with the official four-hour result or used for an S23U comparison.

## Detector cadence and gating

The detector remained continuous across setup and the test; counters are detector-lifetime counters, not resettable per test window. The latest 15-minute diagnostic summary at the end reported:

| Metric | Value |
| --- | ---: |
| Detector uptime represented by summary | 15,319,116 ms / 4.26 h |
| Audio frames / Stage 1 executions | 191,488 / 191,488 |
| Stage 2 executions | 27,803 (6,533.7/h over detector uptime) |
| Stage 3 executions | 26,213 (6,160.1/h over detector uptime) |
| Silence-gate skips | 163,669 (85.48% of audio frames) |
| Verifier invocations / passes / rejects during idle | 0 / 0 / 0 |
| High-confidence / verified activations during idle | 0 / 0 |

To reduce setup-period contamination, the delta between the first diagnostic summary emitted after the official start and the final summary covers 11,714,562 ms / 3.254 h:

| Metric | Delta / observed cadence |
| --- | ---: |
| Audio frames / Stage 1 executions | 146,432 / 146,432 |
| Stage 2 executions | 14,161 / **4,351.8 per hour** |
| Stage 3 executions | 13,801 / **4,241.2 per hour** |
| Silence-gate skips | 132,271 / **90.33%** of audio frames |
| Verifier invocations / activations | 0 / 0 |

The first summary was emitted after detector startup but after the official test began, so this delta is a conservative partial-window cadence measurement; it is not substituted for the exact four-hour battery duration.

## Accelerator, attribution, and resource evidence

- The diagnostic provider status was `session_created_nnapi_requested_assignment_unverified`.
- **NNAPI classification: inconclusive.** This proves only that NNAPI with `CPU_DISABLED` was requested and a session was created. It does not establish node assignment or accelerator execution. No native ONNX Runtime provider-assignment evidence was captured.
- Mel and classifier execution are CPU by design. No CPU-utilisation sample was collected; detector cadence is the available workload proxy.
- `batterystats --checkin` provided no package-attributed record for the debug app in this run. Per-app battery attribution and app-specific wakelock attribution are therefore **unavailable**, not zero. No same-device wake-word-disabled control was performed.
- `dumpsys power` showed the device dozing at both boundaries. `dumpsys meminfo` did not return a parsed package-PSS row, so no memory comparison is claimed.
- No raw logcat, ADB serial, wireless endpoint, pairing code, account name, or screenshot is included in this repository evidence.

## Post-test wake smoke

After end-state capture, the operator attempted a separate locked-screen **Hey Jandal** plus **what time is it** interaction. The first attempt appeared to fail. After waking and re-locking the device, a retry passed.

No audible cue was heard on the retry, so the operator had to guess when to speak the command. Current volume or audio-routing state may explain the missing cue, but that cause is unverified. The filtered logs for the initial attempt showed one high-confidence detector event and one verifier rejection, with no detector error and `WakeWordService` still active. This records a cue/reliability observation, not a proven detector failure, and it is separate from the idle-window counters.

## Outcome and next step

A 24-point loss in 4.013 hours while the tested configuration was discharging, screen-off, dozing/idle, and without intentional interactions is **clearly unacceptable for retaining that configuration as a default-on launch experience without further comparative evidence**. The enabled wake path, continuous detector workload, and excessive whole-device drain are strongly correlated in this run. The run does **not** isolate Jandal's exact battery share or prove that `AudioRecord` plus ONNX caused all observed drain: package attribution, app-specific wakelock attribution, and a same-device wake-word-disabled baseline are absent.

No speculative performance tuning was added in this retest. [Issue #1391](https://github.com/NickMonrad/kernel-ai-assistant/issues/1391) requires a matched wake-word-disabled control before a firm causal or release-default conclusion, then investigates the measured idle Stage 2/3 cadence, reproducible CPU-only versus NNAPI-requested behavior, provider-assignment evidence, and the post-idle cue/reliability observation. Android Sound Trigger/DSP integration remains unproven for a normal third-party application; this result does not establish a usable OEM or privileged DSP path.
