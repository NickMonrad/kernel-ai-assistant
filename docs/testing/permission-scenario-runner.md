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
- focused per-scenario logcat capture (started from the current device timestamp, not the full ring buffer)
- rich local `result.json`
- PR/issue-comment-ready `summary.md`
- schema-shaped derived `evidence.json` for downstream tooling experiments
- initial scenarios:
  - `mic_denied_enable_hey_jandal` — currently reports **blocked** on S21 when Jandal is not already the default assistant
  - `mic_revoke_while_hey_jandal_enabled` — currently reports **blocked** on S21 when Jandal is not already the default assistant
  - `weather_location_denied`

Intentionally **not** implemented yet:

- sticky GitHub comment posting
- copying results into `test-results/`
- dashboard publishing
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
- `evidence.json` — narrower schema-shaped projection derived from the raw run
- `evidence.json` intentionally omits scenarios that ended `blocked` / `skipped`, because the
  current shared schema can represent pass/fail but not environment-prerequisite blockers without
  misreporting them as product failures
- `summary.md` — concise Markdown suitable for PR or issue comments
- `logcat.txt` — focused app/crash logcat grouped by scenario, filtered to the active app process
- `screenshots/` — explicit checkpoint screenshots only

## Running the first slice

```bash
ANDROID_SERIAL=<S21_SERIAL> python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --serial "$ANDROID_SERIAL" \
  --scenarios mic_denied_enable_hey_jandal,mic_revoke_while_hey_jandal_enabled,weather_location_denied \
  --out-dir scripts/test-reports/permissions
```

List available scenarios:

```bash
python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --scenarios mic_denied_enable_hey_jandal \
  --list-scenarios
```

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
