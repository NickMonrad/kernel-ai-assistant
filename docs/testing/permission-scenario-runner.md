# Permission scenario runner

Status: **First slice / local-only**  
Issue: **#1330**

## Purpose

`scripts/run_permission_scenarios.py` is a deterministic physical-device runner for
permission-sensitive user journeys that cross the app / Android boundary.

It complements existing test layers:

- **Compose / JVM tests** — rendering, state transitions, copy, callbacks
- **Connected Android tests** — app + UiAutomator smoke coverage for known flows
- **ADB skill harness** (`scripts/adb_skill_test.py`) — end-to-end routing and action flows
- **Permission scenario runner** — reviewer-facing on-device permission journeys with
  screenshots, step tracing, UX-friction counters, and focused logcat

This runner is **not CI evidence**. It is **local physical-device evidence** only.

## Device policy

- **Default target:** S21 (`s21-exynos`)
- **Do not use the S23U by default.** It is a targeted/reference device only when a
  device-specific follow-up explicitly requires it.
- If no S21 / ADB device is available, do not fake coverage. Capture the blocker and
  stop the on-device validation step.

## Scope in the first slice

Implemented now:

- deterministic scenario selection by ID
- ADB-driven permission-state setup
- explicit step trace with expected / actual / duration
- local screenshots at declared checkpoints
- focused per-scenario logcat capture from the latest PID-filtered app/crash lines, not the full ring buffer
- rich local `result.json`
- PR/issue-comment-ready `summary.md`
- schema-compatible derived `evidence.json` for downstream tooling experiments, marked with explicit non-inference model metadata (`not_applicable` / `permission_scenario_runner` / `adb`)
    - `hey_jandal_preflight`
    - `hey_jandal_enable_mic_granted`
    - `hey_jandal_enable_mic_denied`
    - `hey_jandal_mic_revoked_reopen_voice` — deterministic re-entry, not exact task resume
    - `weather_location_denied`

Intentionally **not** implemented yet:

- automatic publish side effects during local scenario execution
- CI merge-gate enforcement for permission scenarios
- S23U automation mode
- special-access flows like DND / write-settings / exact alarms
- video capture, screenshot diffing, or runtime-planned steps

## Output layout

Each run creates a timestamped directory under `scripts/test-reports/permissions/`:

```text
scripts/test-reports/permissions/<timestamp>/
  result.json
  evidence.json
  summary.md
  logcat.txt
  screenshots/
    01-...
    02-...
```

### Output files

- `result.json` — rich local run report with per-step trace, UX metrics, and artifact paths
- `evidence.json` — narrower schema-compatible projection derived from the raw run, with explicit
  non-inference model metadata because permission scenarios do not execute LiteRT/model inference
- `evidence.json` intentionally omits scenarios that ended `blocked` / `skipped`, because the
  current shared schema can represent pass/fail but not environment-prerequisite blockers without
  misreporting them as product failures
- `summary.md` — concise Markdown suitable for PR or issue comments
- `logcat.txt` — focused app/crash logcat grouped by scenario, filtered to the active app process
- `screenshots/` — explicit checkpoint screenshots only

## Running locally

```bash
ANDROID_SERIAL=<S21_SERIAL> python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --serial "$ANDROID_SERIAL" \
  --scenarios hey_jandal_preflight,hey_jandal_enable_mic_granted,hey_jandal_enable_mic_denied \
  --out-dir scripts/test-reports/permissions
```

List available scenarios:

```bash
python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --scenarios hey_jandal_preflight \
  --list-scenarios
```

## Publishing evidence explicitly

Publishing is a **separate explicit step**. The runner stays local-only unless you invoke
[`scripts/publish_permission_scenario_report.py`](../../scripts/publish_permission_scenario_report.py).

```bash
python3 scripts/publish_permission_scenario_report.py \
  --report-dir scripts/test-reports/permissions/<timestamp> \
  --pr <PR_NUMBER> \
  --commit <EXPECTED_HEAD_SHA> \
  --device-id s21-exynos
```

What the publisher does:

- validates `result.json`, `evidence.json`, `summary.md`, and available artifacts
- requires `source == on_device` and `device.execution == physical`
- refuses stale publication by default if:
  - the report commit does not match `--commit`, or
  - the live PR head SHA does not match `--commit`
- updates one sticky PR comment in place via marker
  `<!-- jandal-permission-scenario-evidence -->`
- publishes schema-valid evidence and reviewer artifacts separately so the dashboard only ingests
  `evidence.json`

Override only for recovery cases:

```bash
python3 scripts/publish_permission_scenario_report.py \
  --report-dir scripts/test-reports/permissions/<timestamp> \
  --pr <PR_NUMBER> \
  --commit <EXPECTED_HEAD_SHA> \
  --device-id s21-exynos \
  --allow-stale-report
```

### Published layout

```text
results/pr/<PR>/on_device/permissions/<device>/<timestamp>/
  evidence.json

artifacts/pr/<PR>/permissions/<device>/<timestamp>/
  result.json
  summary.md
  logcat-redacted.txt
  screenshots/
```

This split is deliberate: the dashboard recursively ingests `results/**/*.json`, so only the
schema-compatible `evidence.json` lives under `results/`. The richer reviewer bundle lives under
`artifacts/` on the same `test-results` branch.

## Hey Jandal voice scenarios

The Hey Jandal scenarios now treat the Android default-assistant role as an explicit manual
precondition instead of a silent product failure. The runner detects the current assistant holder
using non-mutating device checks and reports setup blockers as `functional_result = blocked`.

The grouped voice scenarios are order-independent. Each enablement scenario now re-establishes a
known local state before asserting permission behavior:

- confirm the default-assistant prerequisite
- reset the Hey Jandal toggle to off
- only then grant/reset microphone permission for the specific case under test

Current voice scenarios:

- `hey_jandal_preflight` — verifies whether the device is ready to run the wake-word scenarios
- `hey_jandal_enable_mic_granted` — microphone already granted, reset the toggle off, then enable the wake word toggle
- `hey_jandal_enable_mic_denied` — reset the toggle off, return microphone permission to a promptable denied state, then confirm the permission prompt path
- `hey_jandal_mic_revoked_reopen_voice` — enable the wake word toggle with mic granted, externally revoke microphone, then deterministically re-enter Voice settings and verify the durability repair UX dialog (not exact task resume)

When the assistant role is not configured, the blocked reason is explicit:

> `Jandal is not configured as the Android default assistant; configure it manually before running Hey Jandal voice scenarios.`

If the wake-word model is unavailable on the build, that setup blocker also reports as
`functional_result = blocked` rather than a product failure, even when the toggle label is not
rendered yet.

This blocked/setup-required state is preserved in `result.json`, `summary.md`, and any later
published sticky PR comment. It is **not** converted into schema pass/fail evidence.

Example commands:

```bash
# list scenarios
python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --scenarios hey_jandal_preflight \
  --list-scenarios

# run only the Hey Jandal voice group
ANDROID_SERIAL=<S21_SERIAL> python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --serial "$ANDROID_SERIAL" \
  --scenarios hey_jandal_preflight,hey_jandal_enable_mic_granted,hey_jandal_enable_mic_denied,hey_jandal_mic_revoked_reopen_voice \
  --out-dir scripts/test-reports/permissions
python3 scripts/publish_permission_scenario_report.py \
  --report-dir scripts/test-reports/permissions/<timestamp> \
  --pr <PR_NUMBER> \
  --commit <EXPECTED_HEAD_SHA> \
  --device-id s21-exynos
```

The runner does **not** change the Android default-assistant state automatically. If the device is
not configured, stop there, capture the blocker, and fix the role manually before re-running the
Hey Jandal scenarios.

## When to use this runner

Use this runner when the regression depends on:

- Android runtime permission dialogs
- Android Settings repair hops
- app resume after external permission changes
- screenshot evidence for reviewer confidence
- UX-friction counting on a real device

Prefer other test layers when you only need:

- composable rendering / copy verification
- callback wiring
- ViewModel state transitions
- deterministic app-internal logic with no OS boundary
- CI-safe checks

## Privacy / evidence guardrails

- Capture screenshots **only** at declared checkpoints tied to the scenario.
- Keep runs on fresh, deterministic test state where practical.
- Capture focused logcat only; do not collect or publish large raw device logs by default.
- The runner writes **local** artifacts only. It does **not** auto-post GitHub comments and
  does **not** publish durable evidence unless a separate workflow is invoked later.
