# Risk-Based Evidence Policy

Every PR should carry evidence proportional to its risk level. This document defines the
three-tier risk classification and the minimum evidence expected for each tier.

## Guiding Principles

1. **Low-risk changes stay lightweight.** Docs, copy changes, and refactors with no
   behaviour change do not require device testing, screenshots, or broad regression runs.
2. **PR evidence defaults to the S23U.** The S23U (`RFCW11265XH`, Snapdragon 8 Gen 2) is the primary on-device test device for everything except the named S21-bound gates below; if it is unavailable, report the blocker. The S21 (`R5CR605B71K`, Exynos) is the second dedicated test device, used for:
   - the named S21-bound gates listed below, which keep their existing S21 scope;
   - parallel runs, overflow when the S23U is busy, and second-SoC comparison when a change is device-sensitive.
   Both phones follow the same house rules in Device Handling Rules below. Explicit issue/device requirements win. (Owner decisions: 2026-11-14, "S23 should be primary test device, but we can use S21 if we need more test capacity."; 2026-11-15, the S21 is a dedicated test device, not a household device.)
   Named S21-bound gates (existing scope, unchanged):
   - only a complete S21 `regression` run sets `release_gate_success` (`docs/automated-testing.md`);
   - the S21 model-readiness preflight (`docs/testing/s21-model-readiness.md`) is S21-bound;
   - the S21 `permission_flows` connected suite (`docs/adb-testing.md`) and the permission scenario runner (`scripts/run_permission_scenarios.py --device-id s21-exynos`, `docs/testing/permission-scenario-runner.md`) are S21-bound;
   - the wake-word acoustic harness names S21 as its launch-blocking target (`docs/testing/wake-word-acoustic-reliability-harness.md`).
   Those runs follow the Device Handling Rules below: explicit `adb -s <serial>` installs, no `./gradlew installDebug`, and the same house rules for both phones.
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
  (S23U = `RFCW11265XH`, S21 = `R5CR605B71K`). Install the debug APK with `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`; never use `./gradlew installDebug`, which installs on every connected phone. Gradle connected-test tasks take `ANDROID_SERIAL=<serial>` as a prefix.
- **Dedicated test devices.** Both phones are owned by the project, connected to the dev machine 24/7, and eligible for testing at any time: S23 Ultra (`RFCW11265XH`, owner decision 2026-11-14) and S21 (`R5CR605B71K`, owner decision 2026-11-15). The same house rules apply to both:
  - keep media and alarm volume low;
  - timers/alarms of 10 seconds or less only, and cancel everything started;
  - never uninstall or clear `ai.jandal.assistant` (Play build) or legacy `com.kernel.ai` / `com.kernel.ai.debug`. Agents may uninstall, clear and reinstall the debug build `ai.jandal.assistant.debug` on these phones for from-scratch testing (fresh-install and first-run paths), per owner decision 2026-11-15. A cleared debug app needs its models downloaded again (about 3 GB), so prefer Wi-Fi and say so in the report;
  - between 21:00 and 06:00 `Australia/Brisbane`, full suites (including alarm-producing ones) are allowed only after the silencing protocol below; check it with `adb -s <serial> shell dumpsys audio | grep -A3 STREAM_ALARM` shows `Muted: true` before starting;
  - do not change device-wide settings, other apps' permissions, or personal data, with one exception: test-only settings may be changed for a run (`font_scale`, `accelerometer_rotation`/`user_rotation`, stay-awake via `svc power stayon`, `animator_duration_scale`/`transition_animation_scale`/`window_animation_scale`, ringer mode, and media, alarm, ring and notification volume). Record each value before the run and restore it afterwards. Jandal's own app permissions may be changed only during permission suites that need it, and must be restored to their pre-run state afterwards.
  - silencing protocol (verified on S23U, 2026-11-15): record `adb -s <serial> shell settings get global mode_ringer` (0 silent, 1 vibrate, 2 normal), `adb -s <serial> shell settings get global zen_mode`, and `adb -s <serial> shell cmd media_session volume --stream N --get` for N=3 (media), 4 (alarm), 2 (ring), 5 (notification). Then `adb -s <serial> shell cmd audio set-ringer-mode SILENT` (uppercase; lowercase is rejected), `adb -s <serial> shell cmd media_session volume --stream N --set 0` for N=3 and N=5, and `adb -s <serial> shell cmd notification set_dnd on` (total silence; it overrides the S23U `Sleeping` DND schedule). Before starting, confirm `adb -s <serial> shell dumpsys audio | grep -A3 STREAM_ALARM` shows `Muted: true`. Afterwards run `adb -s <serial> shell cmd notification set_dnd off`, restore `zen_mode` to the recorded value, restore the ringer with `cmd audio set-ringer-mode NORMAL|VIBRATE|SILENT` to the recorded mode, and `--set <recorded>` for each stream.
  - known limits on S23U: alarm stream (4) cannot go below 1 (`--set 0` is rejected with "invalid volume index 0 ... [1..15]"; `cmd audio set-volume 4 0` is clamped back to 1). Under total-silence DND the alarm stream reports `Muted: true`; that mute flag is the evidence used. No alarm audio was observed playing during the 2026-11-15 test, so the mute flag, not audible silence, is the basis. Ring stream (2) `--set 0` fails with a Do Not Disturb SecurityException; ring is silenced by the SILENT ringer mode instead.
- **Never touch the owner's personal Honor phone.**
- **Device lock protocol.** One lock per phone: `/tmp/jandal-s23u.lock` (RFCW11265XH) and `/tmp/jandal-s21.lock` (R5CR605B71K). Hold a phone's lock for the whole device run.
  1. Take it atomically with `mkdir /tmp/jandal-s21.lock`. A non-zero exit means the phone is held; do not use it.
  2. On success, write the owner file: `printf '%s\n%s\n' '<agent-id>' "$(date +%s)" > /tmp/jandal-s21.lock/owner`.
  3. Release only your own lock, after confirming the owner file names your agent id: `rm -rf /tmp/jandal-s21.lock`.
  4. A lock whose owner timestamp is more than 25 minutes old is stale. Report it to the operator; agents must not remove it. Use the other phone if it is free, otherwise report the blocker.
  5. Never remove another agent's lock. Never `pkill` by broad pattern; stop only processes you started, by PID.
- **Wake and unlock over adb** (same for both phones; shown for S21):
  ```
  adb -s R5CR605B71K shell input keyevent KEYCODE_WAKEUP
  adb -s R5CR605B71K shell wm dismiss-keyguard
  ```
- **Instrumented runs** keep the screen on: `adb -s <serial> shell svc power stayon usb`. First record `adb -s <serial> shell settings get global stay_on_while_plugged_in`; restore it afterwards (`svc power stayon false` if the recorded value was `0`).
- **Debug install check:** after `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`, confirm with `adb -s <serial> shell dumpsys package ai.jandal.assistant.debug | grep lastUpdateTime`, then launch with `adb -s <serial> shell am start -n ai.jandal.assistant.debug/com.kernel.ai.MainActivity`.
- **Committed screenshots must never show real names, contacts, calendar entries, birthdays, or messages.** The repo is public; use test data or redact before committing.

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

**When S21 use is justified:** the S21 is a second dedicated test device. Use it freely for parallel runs, overflow when the S23U is busy, and a second-SoC (Exynos) comparison when the change is device-sensitive (for example inference or GPU code). Suite-specific S21 gates (see Guiding Principle 2) still apply where tooling requires them.

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
- Device test evidence — S23U required; the S21 may be used as a second test device or for a second-SoC comparison
- Pre-flight oracle result in evidence _(for harness changes)_
- Session-isolation smoke test evidence _(for LiteRT changes)_
- Permission path checklist completed _(for permission changes)_
- Device-config matrix consulted _(for device-compatibility changes)_
- `connectedDebugAndroidTest` passes _(where applicable)_
- Known limitations and deferred work documented

**When S23U evidence is required:** Always — S23U is the primary device for high-risk
evidence.

**When S21 use is justified** (second test device):
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
| S21 (Exynos) | Second test device (dedicated) | Parallel runs, overflow when the S23U is busy, second-SoC comparison for device-sensitive changes; named S21-bound gates |

---

## Evidence Type Reference

| Evidence Type | When to Use |
|---------------|-------------|
| CI-only | Low-risk changes; medium-risk with full automated coverage |
| Screenshots | UI changes where visual result is the primary concern |
| S23U device evidence | Default for medium and high risk |
| S21 device evidence | Second test device: parallel runs, overflow, or second-SoC comparison |
| Both devices | Cross-device compatibility or where behaviour differs by SoC |
| Manual on-device testing | Voice, audio, permissions, UI alignment, ambiguous evidence |
| Review-only | Documentation, config, build, and test-only changes |
