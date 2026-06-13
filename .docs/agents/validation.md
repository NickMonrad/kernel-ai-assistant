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

## CI constraints

- CI runs: lint, unit tests, debug build
- CI **cannot** run real model inference (no GPU/NPU, models too large)
- Inference tests use mocked `InferenceEngine` — never download models in CI
- Compose UI tests run via Android Emulator (API 35 system image)

## Pre-PR checklist

1. `./gradlew test` passes
2. `./gradlew lint` passes (no new warnings)
3. `./gradlew assembleDebug` succeeds
4. If UI changes with navigation or permission interaction: `./gradlew connectedDebugAndroidTest` passes
5. Device test evidence on S21 for medium/high risk changes (see `.docs/agents/risk-based-evidence-policy.md`)

> **Note:** Not all changes require device testing. See the risk-based evidence policy for
> when CI-only, screenshot, or device evidence applies.
