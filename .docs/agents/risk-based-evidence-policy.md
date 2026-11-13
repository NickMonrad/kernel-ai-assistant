# Risk-Based Evidence Policy

Every PR should carry evidence proportional to its risk level. This document defines the
three-tier risk classification and the minimum evidence expected for each tier.

## Guiding Principles

1. **Low-risk changes stay lightweight.** Docs, copy changes, and refactors with no
   behaviour change do not require device testing, screenshots, or broad regression runs.
2. **PR evidence defaults to the S23U.** The S23U (Snapdragon 8 Gen 2) is the primary
   on-device test device for broad/regular acceptance evidence when it is connected and
   available; if a required device is unavailable, report the blocker. The S21 (Exynos)
   is a secondary device used only when more test capacity is needed — for example
   parallel runs, or a second-SoC comparison when a change is device-sensitive. Explicit
   issue/device requirements win. (Owner decision, 2026-11-14: "S23 should be primary
   test device, but we can use S21 if we need more test capacity.")
   Suite-specific S21 gates still apply where tooling requires them: only a complete S21 `regression` run sets `release_gate_success` (`docs/automated-testing.md`); the S21 model-readiness preflight (`docs/testing/s21-model-readiness.md`) and the S21 `permission_flows` connected suite (`docs/adb-testing.md`) are S21-bound; and the wake-word acoustic harness names S21 as its launch-blocking target (`docs/testing/wake-word-acoustic-reliability-harness.md`). Those runs follow the Device Handling Rules below: explicit `adb -s <serial>`, no `./gradlew installDebug`, and no changes to the household S21's settings, permissions, or personal data.
3. **Manual on-device testing is reserved for high-risk or hard-to-automate changes.**
   Do not require manual testing for every low-risk or CI-safe change.
4. **Targeted validation is better than broad.** A focused unit test for the changed
   function provides more signal than a full `connectedDebugAndroidTest` run.
5. **CI evidence artifacts record results, not proof of passing.** The build, lint and
   unit-test steps currently use `continue-on-error: true`; inspect their individual
   outcomes before claiming validation passed. Durable publishing to `test-results`
   is reviewer/user-controlled unless explicitly requested.


## Device Handling Rules

- **Target devices explicitly.** Always use `adb -s <serial>`
  (S23U = `RFCW11265XH`, S21 = `R5CR605B71K`). Never use `./gradlew installDebug`,
  which installs on every connected phone.
- **S23 Ultra is a dedicated test device** (owner decision, 2026-11-14). Full suites are allowed on it (`RFCW11265XH`) under these house rules:
  - keep media and alarm volume low;
  - timers/alarms of 10 seconds or less only, and cancel everything started;
  - never uninstall or clear `ai.jandal.assistant` (Play build), `com.kernel.ai`, or `com.kernel.ai.debug`;
  - no full-suite or alarm-producing runs between 21:00 and 06:00 `Australia/Brisbane`.
- **Household devices.** The S21 (`R5CR605B71K`) is extra capacity with targeted runs only and belongs to another member of the household: agents must not change its settings, permissions, or personal data. Never touch the owner's personal Honor phone.

Other documents should reference this section rather than duplicating these rules.

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
- Device test evidence — S23U is default (single device minimum)
- Screenshots for UI changes _(if applicable)_
- ROADMAP / SPEC updated _(if user-visible behaviour changes)_

**When CI-only evidence is enough:** When the change has no device-specific behaviour
and automated unit/instrumentation tests cover the changed paths.

**When screenshots are enough:** For moderate UI changes where the visual result is
the primary concern and interaction logic is unchanged.

**When S23U evidence is expected:** Default — medium-risk changes that touch
device-specific behaviour or where automated tests don't cover the full path.

**When additional S21 use is justified:** Only when more test capacity is needed —
parallel runs, a second-SoC comparison when the change is device-sensitive
(for example inference or GPU code), or lower-memory/degraded-device checks. Suite-specific S21 gates (see Guiding Principle 2) still apply where tooling requires them.

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
- Device test evidence — S23U required; S21 only as extra capacity or for a second-SoC comparison
- Pre-flight oracle result in evidence _(for harness changes)_
- Session-isolation smoke test evidence _(for LiteRT changes)_
- Permission path checklist completed _(for permission changes)_
- Device-config matrix consulted _(for device-compatibility changes)_
- `connectedDebugAndroidTest` passes _(where applicable)_
- Known limitations and deferred work documented

**When S23U evidence is required:** Always — S23U is the primary device for high-risk
evidence.

**When additional S21 capacity is justified:**
- More test capacity is needed (parallel runs)
- Lower-memory or degraded-device checks
- Change is model/device-sensitive and a second-SoC (Exynos/Mali) comparison adds signal
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
| S23U (SD 8 Gen 2) | Primary test device | Broad suites, baseline evidence, default for all tiers |
| S21 (Exynos) | Secondary — extra capacity only | Parallel runs; second-SoC comparison for device-sensitive changes; lower-memory/degraded-device checks |

---

## Evidence Type Reference

| Evidence Type | When to Use |
|---------------|-------------|
| CI-only | Low-risk changes; medium-risk with full automated coverage |
| Screenshots | UI changes where visual result is the primary concern |
| S23U device evidence | Default for medium and high risk |
| S21 device evidence | Extra capacity: parallel runs or second-SoC comparison |
| Both devices | Cross-device compatibility or where behaviour differs by SoC |
| Manual on-device testing | Voice, audio, permissions, UI alignment, ambiguous evidence |
| Review-only | Documentation, config, build, and test-only changes |
