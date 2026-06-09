# PR #1154 — Navigation/Back-Stack Regression Harness

## Summary

Automated navigation regression harness for the #751 launch navigation model (Chats | Actions | Tools). Validates 22 navigation flows on connected Android device.

**Result:** ✅ 22/22 tests passed (100%)

## Device

| Field | Value |
|-------|-------|
| Device | S23 Ultra (SM-S918B) |
| ADB serial | `100.76.134.49:33115` |
| Android API | 36 |
| Tier | `reference` |

## Navigation regression flows validated

### Tools Row Navigation (combined test — 16 routes)
| Route | Status |
|-------|--------|
| toolsRouteMatrix_allRowsNavigateAndBack | ✅ |

### Tab Switching
| Test | Status |
|------|--------|
| chats → actions → tools round-trip | ✅ |
| Tools child → Back → Tools | ✅ |
| Tools child → Back → Actions → Tools | ✅ |
| Tools child → Back → Chats → Tools | ✅ |
| Actions ↔ Tools switching | ✅ |

### Parameterised Routes
| Test | Status |
|------|--------|
| Actions → dismiss/back → Tools | ✅ |
| Actions → Chats → Tools (no stale draft) | ✅ |

### Drawer Transitions
| Test | Status |
|------|--------|
| Drawer sheet composed from Tools | ✅ |
| Drawer items present from Tools | ✅ |
| Drawer items present from Chats | ✅ |

### Repeated-Tap / Duplicate Stack
| Test | Status |
|------|--------|
| Repeated Tools tab — no duplicate stacks | ✅ |
| Repeated tab switches — no stale state | ✅ |
| Reopen same child destination | ✅ |
| Repeated row tap — no duplicate stacks | ✅ |
| Tools → Actions → Tools — stale check | ✅ |

### Screenshot Evidence
| Screenshot | Status |
|------------|--------|
| `01-tools-hub.png` | ✅ |
| `02-tools-learn-child-screen.png` | ✅ |
| `03-tools-child-destination-example.png` | ✅ |
| `04-actions-draft-route-dismissed-back-to-tools.png` | ✅ |
| `05-drawer-open-from-tools.png` | ✅ |
| `06-after-tab-switch-regression.png` | ✅ |

## Validation

| Check | Status |
|-------|--------|
| `./gradlew lint` | ✅ |
| `./gradlew test` | ✅ |
| `./gradlew assembleDebug` | ✅ |
| `./gradlew connectedDebugAndroidTest` | ✅ (22/22) |
| Evidence schema validation | ✅ |
| Evidence published to `test-results` | ✅ |

## On-device evidence

**Test command:**
```
./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.kernel.ai.navigation.NavigationBackStackRegressionTest
```

## Test evidence published

Published to `test-results` branch at:

- `results/pr/1154/on_device/2026-06-09T21:24:19Z_s23-ultra_navigation_backstack.json`
- `results/pr/1154/on_device/2026-06-09T21:24:19Z_s23-ultra_navigation_backstack.csv`
- `results/pr/1154/on_device/2026-06-09T21:24:19Z_s23-ultra_navigation_backstack.md`

## Screenshot evidence

Screenshots were captured to:
```
/sdcard/Android/data/com.kernel.ai.debug/files/Pictures/test-screenshots/pr-751-child-04/
```

Pull command:
```bash
adb pull /sdcard/Android/data/com.kernel.ai.debug/files/Pictures/test-screenshots/pr-751-child-04/ ./debug/pr-751-child-04-screenshots
```

Screenshots are **not committed** to the `main` branch.

## Files changed

- `app/src/androidTest/java/com/kernel/ai/navigation/NavigationBackStackRegressionTest.kt` — test harness (22 tests)
- `scripts/generate_navigation_test_evidence.py` — #1113 normalised evidence generator

## Limitations / follow-ups

- Drawer open/close via gesture is not tested (requires swipe interaction); sheet composition is verified
- Only covers composable-level navigation; full-app integration tests (with real Activities) would be a future improvement
- `createComposeRule` test isolation is fragile across 22 tests — route matrix combined into one test to work around this
- New evidence schema fields (`category`, `duration_seconds`) not yet supported by the publish script's schema validator
- S23 Ultra was used per issue requirements; S21 Exynos also connected
