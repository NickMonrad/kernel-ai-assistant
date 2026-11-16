# Validation Policy

Load this when deciding how to verify a change.

## Validation hierarchy (narrowest first)

1. **Targeted unit tests** — `./gradlew :module:test` or `./gradlew :module:testDebugUnitTest`
2. **Module-level build** — `./gradlew :module:assembleDebug`
3. **Feature-specific instrumentation tests** — `./gradlew connectedDebugAndroidTest` (requires device)
4. **Full app build** — `./gradlew assembleDebug` (only for cross-cutting changes)

## When to use each level

| Change scope | Validation |
|-------------|------------|
| Single file, no callers | Unit test for the changed function |
| ViewModel logic | Unit test with mocked dependencies |
| UI changes | Compose UI test; manual device test for visual alignment, permission flows, or navigation interaction |
| Inference engine | Unit test with mocked `InferenceEngine` |
| Skill logic | Unit test; manual device test for voice-driven skill interaction |
| Build config / Gradle | Full `assembleDebug` |
| Cross-module refactor | Full `assembleDebug` + `lint` |
| Regex or text patterns (lexicons, normalisers, routers) | Unit tests **plus** an androidTest that compiles and runs the pattern, executed with `connectedDebugAndroidTest` on a device (CI only compiles it). Android's regex engine is ICU-backed: it rejects some constructs the desktop JVM accepts (for example `(?U:…)`) and gives `\s`/`\w`/`\d`/`\b` Unicode meanings, so JVM unit tests can pass while the app crashes or matches differently (#1968). |

## CI constraints

- CI runs: lint, unit tests, debug build, and an androidTest Kotlin compile (instrumented tests are not executed)
- CI unit tests run on the desktop JVM, so they cannot catch Android-only runtime differences such as ICU regex syntax (#1968)
- CI **cannot** run real model inference (no GPU/NPU, models too large)
- Inference tests use mocked `InferenceEngine` — never download models in CI
- Compose UI / instrumented tests run only locally via `connectedDebugAndroidTest` on a device or an API 35 emulator; CI compiles them but does not run them

## Connected Compose/UI tests on a physical device

Before running connected Compose/UI instrumentation tests on a physical device,
verify the device is:

- visible and authorised in `adb devices`
- awake
- unlocked / keyguard dismissed

For longer runs, keep the device awake:

```bash
adb shell svc power stayon usb
```

If you temporarily change the device keep-awake setting for validation, restore
the prior/default state after the run unless the operator explicitly asks to
leave it enabled.

**Troubleshooting:** `IllegalStateException: No compose hierarchies found in the app`
can occur when the physical device is dozing or the screen is off — the Compose
test host cannot stay resumed long enough to register a test root. Check the
device's wake/unlock state before classifying this error as a product or
Compose test-harness defect. This failure mode was observed during #1482
validation.

## Before opening a PR

1. Classify the change using `.docs/agents/risk-based-evidence-policy.md` and any applicable subsystem review gate.
2. Run the **smallest relevant local checks** described above and in that policy. In particular, docs-only changes need `git diff --check` (and configured Markdown lint), not unconditional Android builds, lint, or unit tests.
3. For changed shared paths, cover relevant existing consumers, not only the new acceptance example.
4. For UI, permissions, inference, and other device-sensitive work, collect the device evidence required by the risk policy and issue. S23U is the default where the policy permits it; a specified device should not trigger a redundant confirmation request. Report anything not run rather than treating an instrumentation APK build as an executed test.
5. After opening the PR, inspect the individual CI **Build (debug)**, **Lint**, and **Unit tests** step outcomes, not just the overall workflow result. These steps currently use `continue-on-error: true`, so a green workflow can conceal failed checks. Report each failure as a failure; do not claim validation passed based only on the workflow conclusion. CI does not replace required physical-device evidence; evidence publication remains separately controlled by the requester/reviewer.

A broader local `./gradlew test`, `lint`, or `assembleDebug` run is appropriate when the scope warrants it, but it is not a universal pre-PR prerequisite.
