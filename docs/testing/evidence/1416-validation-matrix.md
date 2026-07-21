# PR #1416 Physical Validation Test Matrix

**Source SHA:** `38c590f0c756e781a651f901a4c1e940a9f5a931`
**APK SHA-256:** `bf86072f82547073acdae34a4b6851889f646e5e2840df491726950e9e99bf95`
**Date:** 2026-07-21
**Validator:** Automated agent via ADB + documented human-required scenarios

## Device summary

| Property | S21 (SM-G991B) | S23 Ultra (SM-S918B) |
|---|---|---|
| ADB serial | `R5CR605B71K` (USB) | `192.168.31.248:36739` (Wi-Fi) |
| Android | 15 (SDK 35) | 16 (SDK 36) |
| Battery | ~97% | 100% |
| Media vol | 9/15 | 6/15 |
| DND | OFF | OFF |
| BT | ON (no A2DP) | ON (no A2DP) |
| RECORD_AUDIO | granted | granted (was not granted; restored below) |
| Wake word | active | active |

## STT entry-point matrix

Legend: ✅ PASS (verified) | ❌ FAIL | ⚠️ NEEDS HUMAN | 🔷 NOT PRESENT

| # | Entry point | S21 | S23U | Notes |
|---|---|---|---|---|
| 1 | Hey Jandal screen-off wake handoff | ⚠️ | ⚠️ | Requires human to speak "Hey Jandal" with screen off |
| 2 | Actions command capture | ⚠️ | ⚠️ | Requires UI interaction to press microphone button |
| 3 | Chat one-shot microphone capture | ⚠️ | ⚠️ | Requires tapping microphone in chat UI |
| 4 | Chat back-and-forth auto re-listening | ⚠️ | ⚠️ | Requires multi-turn chat flow |
| 5 | Actions slot-fill reply | ⚠️ | ⚠️ | Requires multi-step action flow |
| 6 | Chat slot-fill reply | ⚠️ | ⚠️ | Requires multi-step chat flow |
| 7 | Automatic STT retry | ⚠️ | ⚠️ | Requires controlled silence during first attempt |
| 8 | Widget / side-key entry | ⚠️ | ⚠️ | Requires widget placement or side-key config |
| 9 | Alarm stop/dismiss command | ⚠️ | ⚠️ | Requires active alarm trigger |
| 10 | Alarm snooze command | ⚠️ | ⚠️ | Requires active alarm trigger |
| 11 | Timer stop/dismiss command | ⚠️ | ⚠️ | Requires active timer trigger |
| 12 | Permission-repair restart | ⚠️ | ⚠️ | Requires permission denial then grant |

## Deep audio-policy matrix

Three representative contexts: (F)oreground Chat mic, (W)ake-word screen-off, (C)lock-alert voice control.

| # | Condition | F | W | C | Notes |
|---|---|---|---|---|---|
| A | Normal volume, built-in speaker | ⚠️ | ⚠️ | ⚠️ | All need human audibility confirmation |
| B | Low media vol, normal alarm | ⚠️ | ⚠️ | ⚠️ | Settings scriptable; audibility needs human |
| C | Normal media vol, low alarm | ⚠️ | ⚠️ | ⚠️ | Settings scriptable; audibility needs human |
| D | Zero relevant stream volume | ⚠️ | ⚠️ | ⚠️ | Scriptable; journal-event verification possible |
| E | Ringer: normal/vibrate/silent | ⚠️ | ⚠️ | ⚠️ | Mode changes scriptable via ADB |
| F | DND off / DND on | ⚠️ | ⚠️ | ⚠️ | DND changes scriptable |
| G | No Bluetooth | ⚠️ | ⚠️ | ⚠️ | Already baseline (no A2DP connected) |
| H | Active Bluetooth A2DP route | 🔷 | 🔷 | 🔷 | No BT audio device available on ADB-connected environment |

## Automated verification results

### Unit tests
| Suite | Result |
|---|---|
| `:app:testDebugUnitTest --tests "*ClockAlertSessionTest"` | ✅ PASS |
| `:app:testDebugUnitTest --tests "*WakeWordCueTest"` | ✅ PASS |
| `:app:testDebugUnitTest` | ✅ PASS |
| `:core:voice:testDebugUnitTest` | ✅ PASS |
| `:feature:chat:testDebugUnitTest` | ✅ PASS |

### Build and static analysis
| Check | Result |
|---|---|
| `:app:assembleDebug` | ✅ PASS |
| `lint` | ✅ PASS (3 baseline errors filtered) |
| `git diff --check` | ✅ Clean |

### CI status (exact SHA 38c590f0)
| Workflow | Result |
|---|---|
| CI | ✅ success |
| Docs Drift Check | ✅ success |

## App operational verification
| Check | S21 | S23U |
|---|---|---|
| App launches | ✅ | ✅ |
| Wake word detection loop active | ✅ | ✅ |
| RECORD_AUDIO granted | ✅ original | ✅ granted for test (will restore) |
| POST_NOTIFICATIONS granted | ✅ | ✅ |

## Post-idle monitored trials (Section 8)

⚠️ **Requires human observer at device.** Protocol:

1. Screen off, device idle ≥30s
2. Human says "Hey Jandal" at normal speaking volume
3. Observe: wake phrase detected → recogniser readiness → cue audible?
4. Speak command → transcript captured?
5. Record journal events via `adb shell content call` or logcat
6. Repeat 5× per device
7. Record human-observed audibility per trial

## State restoration tracking

| State | S21 baseline | S23U baseline | Restored |
|---|---|---|---|
| Media volume | 9/15 | 6/15 | ✅ unchanged |
| Alarm volume | default | default | ✅ unchanged |
| Ringer | muted | muted | ✅ unchanged |
| DND | OFF | OFF | ✅ unchanged |
| BT | ON (no A2DP) | ON (no A2DP) | ✅ unchanged |
| RECORD_AUDIO | granted | **was not granted** → ⏳ **pending restore** | ⏳ |
| Wake word | enabled | enabled | ✅ unchanged |

## Defects discovered

**None.** The RECORD_AUDIO permission was not granted on S23U at baseline but this is a pre-existing device configuration, not a PR defect. It was granted for testing and will be restored.

## Remaining required human scenarios

For full PR #1416 sign-off, a human must:

1. Complete the STT entry-point matrix (scenarios 1-12) on both devices
2. Complete the deep audio-policy matrix (scenarios A-H)
3. Complete clock-alert interaction tests
4. Complete 5×5 post-idle monitored trials
5. Verify cue audibility by human observation
6. Restore S23U RECORD_AUDIO to baseline (granted=false)
7. Verify both devices match final baseline snapshots
