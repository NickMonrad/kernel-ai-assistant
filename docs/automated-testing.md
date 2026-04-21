# Automated Testing

This document is the current automation overview for Kernel AI. It complements the deeper
planning/spec work in [`docs/testing/automated-test-specification.md`](./testing/automated-test-specification.md)
and the device setup guide in [`docs/adb-testing.md`](./adb-testing.md).

## Current automated coverage

| Layer | Tooling | What it covers | Entry point |
|-------|---------|----------------|-------------|
| Unit tests | Gradle + JUnit 5 + MockK | Core Kotlin logic, routing, parsing, repositories, presenters | `./gradlew testDebugUnitTest` |
| Instrumented UI tests | Gradle + Compose/AndroidX test | Compose UI and connected-device Android tests | `./gradlew connectedDebugAndroidTest` |
| Device regression harness | `adb` + Python | End-to-end intent routing, profile extraction, and on-device chat/action flows | `python3 scripts/adb_skill_test.py` |

## ADB regression harness

The main device automation entry point is [`scripts/adb_skill_test.py`](../scripts/adb_skill_test.py).

Useful commands:

```bash
# Preview the run plan without touching a device
python3 scripts/adb_skill_test.py --dry-run

# Run the full skill-routing suite
python3 scripts/adb_skill_test.py

# Run only selected phases
python3 scripts/adb_skill_test.py --phases weather,lists

# Run profile-extraction checks
python3 scripts/adb_skill_test.py --profile

# Post a summary comment back to the open PR for the current branch
python3 scripts/adb_skill_test.py --post-pr
```

Supported harness phases today:

1. `alarm_timer`
2. `weather`
3. `media`
4. `lists`
5. `smart_home`
6. `memory`
7. `navigation`
8. `system`
9. `misc`
10. `slot_fill`

Reports are written to [`scripts/test-reports/`](../scripts/test-reports/) as JSON artifacts.
See [`scripts/test-reports/README.md`](../scripts/test-reports/README.md) for the report format.

## What is still planned

The repository already contains a much larger long-form test specification in
[`docs/testing/automated-test-specification.md`](./testing/automated-test-specification.md),
including proposed UI Automator coverage and future suite expansion. Not every item in that
document is wired into a single runnable repo command yet.

Treat this file as the "what exists today" index, and the detailed testing specification as
the "where we want to grow next" design document.
