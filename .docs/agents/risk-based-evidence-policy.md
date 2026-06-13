# Risk-Based Evidence Policy

Every PR should carry evidence proportional to its risk level. This document defines the
three-tier risk classification and the minimum evidence expected for each tier.

## Guiding Principles

1. **Low-risk changes stay lightweight.** Docs, copy changes, and refactors with no
   behaviour change do not require device testing, screenshots, or broad regression runs.
2. **Device evidence defaults to S21.** The S21 (Exynos) device is always available and
   is the default for broad/regular evidence. Avoid running broad suites on the S23U
   (user's daily driver) unless explicitly needed.
3. **Manual on-device testing is reserved for high-risk or hard-to-automate changes.**
   Do not require manual testing for every low-risk or CI-safe change.
4. **Targeted validation is better than broad.** A focused unit test for the changed
   function provides more signal than a full `connectedDebugAndroidTest` run.
5. **CI evidence artifacts confirm the automated gate passed.** Durable evidence
   publishing to `test-results` is reviewer/user-controlled unless explicitly requested.

---

## Risk Tiers

### Low Risk

**Examples:** Documentation changes, copy/text changes, labels and metadata updates,
isolated visual polish (colour, spacing, typography only), refactors with no behaviour
change, build config changes that do not affect behaviour, test-only changes.

**Minimum evidence by change type:**

| Change Type | Minimum Evidence |
|---|---|
| Docs/template/copy-only | `git diff --check`; markdown lint if configured; no Android build/unit tests/device testing |
| Build config | `./gradlew assembleDebug` (or targeted module build) |
| Isolated visual polish | `./gradlew assembleDebug`; before/after screenshots; manual visual check only if alignment/touch-target judgement needed |
| Test-only changes | `./gradlew :module:testDebugUnitTest` (or relevant script-level test); no app build unless touched area warrants it |
| Refactor, no behaviour change | Targeted unit build/test based on changed area |

**When CI-only evidence is enough:** Always, for pure docs/copy/build-config changes.

**When screenshots are enough:** For isolated visual polish with no behaviour change.

**Manual on-device testing:** Not required.

---

### Medium Risk

**Examples:** Compose navigation changes, settings flow modifications, QIR mapping
changes, list/calendar/tool behaviour changes, new skills with straightforward tool
selection, test additions or harness improvements, model availability UX changes.

**Minimum evidence:**
- Applicable Low Risk evidence for the changed area (see table above)
- `./gradlew testDebugUnitTest` passes
- Targeted regression tests run
- Device test evidence — S21 is default (single device minimum)
- Screenshots for UI changes _(if applicable)_
- ROADMAP / SPEC updated _(if user-visible behaviour changes)_

**When CI-only evidence is enough:** When the change has no device-specific behaviour
and automated unit/instrumentation tests cover the changed paths.

**When screenshots are enough:** For moderate UI changes where the visual result is
the primary concern and interaction logic is unchanged.

**When S21 evidence is expected:** Default — medium-risk changes that touch
device-specific behaviour or where automated tests don't cover the full path.

**When focused S23U comparison is justified:** When S21 results are ambiguous,
the change involves inference or GPU code, or the issue specifically reproduced on S23U.

**Manual on-device testing:** Not required unless the change touches audio focus,
STT/TTS, wake-word, or permission flows (see subsystem review gates).

---

### High Risk

**Examples:** Voice pipeline changes (STT, TTS, VAD, wake-word), LiteRT/model handling
(session isolation, KV cache, warmup, context budget), permission flows (new permissions,
repair paths, Android Settings bridges), alarms/timers, test harness changes (ADB
pipeline, oracle, schema), device-compatibility changes, new inference paths, any
change touching shared subsystem lifecycle.

**Minimum evidence:**
- Applicable Medium Risk evidence for the changed area
- Device test evidence — S21 required; S23U only if device-sensitive or ambiguous
- Pre-flight oracle result in evidence _(for harness changes)_
- Session-isolation smoke test evidence _(for LiteRT changes)_
- Permission path checklist completed _(for permission changes)_
- Device-config matrix consulted _(for device-compatibility changes)_
- `connectedDebugAndroidTest` passes _(where applicable)_
- Known limitations and deferred work documented

**When S21 evidence is required:** Always — S21 is the baseline device for high-risk
evidence.

**When focused S23U comparison is justified:**
- Change is model/device-sensitive (LiteRT, GPU, inference)
- S21 results are ambiguous or inconclusive
- Issue specifically reproduced on S23U
- Release confidence requires comparison evidence

**Manual on-device testing — required for:**
- STT/TTS changes (audio pipeline, engine swaps)
- Wake-word / VAD threshold or lifecycle changes
- Audio focus management changes
- Alert-time listening behaviour
- Permission flows (first-run, denied, revoked, repair)
- Visual polish and UI alignment where automated comparison is insufficient
- Android permission flows and system dialog interactions
- Ambiguous automated evidence that needs human judgement

**Manual on-device testing — not required for:**
- Pure inference changes that can be verified via ADB harness
- Build or config changes
- Test harness changes validated by pre-flight oracle
- Changes with clear, unambiguous automated evidence

---

## Device Usage Summary

| Device | Role | Evidence Scope |
|--------|------|----------------|
| S21 (Exynos) | Default evidence device | Broad suites, baseline evidence, medium-risk default |
| S23U (SD 8 Gen 2) | Focused comparison device | Device-sensitive changes, ambiguous S21 results, release confidence |

---

## Evidence Type Reference

| Evidence Type | When to Use |
|---------------|-------------|
| CI-only | Low-risk changes; medium-risk with full automated coverage |
| Screenshots | UI changes where visual result is the primary concern |
| S21 device evidence | Default for medium and high risk |
| S23U device evidence | Device-sensitive, ambiguous, or release-confidence cases |
| Both devices | Cross-device compatibility or where behaviour differs by SoC |
| Manual on-device testing | Voice, audio, permissions, UI alignment, ambiguous evidence |
| Review-only | Documentation, config, build, and test-only changes |
