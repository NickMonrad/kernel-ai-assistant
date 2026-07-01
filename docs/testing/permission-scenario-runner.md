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
- deterministic scenarios:
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

## Scenario schema

Each scenario in `scripts/permission_scenario_defs.py` follows a validated schema.

### Required scenario fields

| Field        | Type            | Description |
|-------------|-----------------|-------------|
| `id`         | string          | Unique scenario identifier (kebab-case) |
| `title`      | string          | Human-readable scenario title |
| `capability` | string          | Feature/capability under test (e.g. `wake_word`, `weather_current_location`) |
| `tags`       | list of strings | Categorisation tags: `voice`, `weather`, `clock`, `special_access`, `dashboard`, `stale_state`, `permissions`, etc. |
| `steps`      | list of dicts   | Ordered step definitions (see below) |

### Scenario steps

Each step is a dict with the following contract:

| Field               | Required for          | Description |
|--------------------|-----------------------|-------------|
| `id`               | All steps             | Unique step ID within the scenario |
| `action`           | All steps             | One of: `set_permission_state`, `launch_main`, `launch_quick_action`, `tap_visible`, `tap_toggle_for_text`, `set_toggle_state`, `check_default_assistant_ready`, `press_home`, `press_back` |
| `expected`         | All steps             | Human-readable description of what should happen |
| `permission`       | `set_permission_state`| Android permission name (e.g. `android.permission.RECORD_AUDIO`) |
| `state`            | `set_permission_state`| One of: `granted`, `revoked`, `prompt`, `blocked` |
| `also_apply`       | `set_permission_state`| Additional permissions to apply the same state to |
| `target`           | `tap_visible`         | Target descriptor with `text`, `content_desc`, `resource_id`, or `any_text` |
| `anchor_text`      | `tap_toggle_for_text`, `set_toggle_state` | Text label associated with the toggle |
| `checked`          | `set_toggle_state`    | Boolean target state for the toggle |
| `query`            | `launch_quick_action` | Quick action query string |
| `expected_visible` | Any step              | List of exact texts that must be visible |
| `expected_any_visible` | Any step           | List of texts where at least one must be visible |
| `expected_not_visible` | Any step           | List of texts that must NOT be visible |
| `expected_toggle_state` | Any step         | Dict with `anchor_text` and `checked` to verify a toggle state |
| `blocked_if_visible` | Any step           | Dict with `texts` and `reason`; if texts are visible, scenario reports as blocked |
| `screenshot`       | Any step              | Boolean, capture screenshot at this step |

### Preconditions, cleanup, and fixtures

Scenarios may include optional `preconditions` and `cleanup` blocks. These are lists of step dicts, using the same action types as main `steps`:

```python
{
    "id": "example_scenario",
    "title": "Example with preconditions and cleanup",
    "capability": "wake_word",
    "tags": ["voice"],
    "preconditions": [
        {"id": "ensure_mic_granted", "action": "set_permission_state", "permission": "android.permission.RECORD_AUDIO", "state": "granted", "expected": "Mic granted before scenario"},
    ],
    "steps": [ ... ],
    "cleanup": [
        {"id": "reset_mic", "action": "set_permission_state", "permission": "android.permission.RECORD_AUDIO", "state": "prompt", "expected": "Mic reset after scenario"},
    ],
}
```

| Section | Type | Purpose |
|---------|------|---------|
| `preconditions` | list of step dicts | Run before main steps. If a precondition step fails, the scenario reports as **blocked** (not a product failure). |
| `cleanup` | list of step dicts | Run after all steps complete (even on failure). Best-effort — cleanup failure does **not** change the scenario's functional result. |

Fixtures are deterministic values shared across scenarios, defined at file level in `permission_scenario_defs.py`:

```python
FIXTURES: dict[str, object] = {
    "weather_named_location": "Tokyo",
    "short_timer_seconds": 10,
    "short_alarm_minutes": 1,
}
```

Scenarios reference fixtures via their `fixtures` field, which merges global fixtures with per-scenario overrides. The runner makes fixture values available to step logic. Use `--dry-run` to preview which fixtures a scenario uses.

## Running locally

```bash
ANDROID_SERIAL=<S21_SERIAL> python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --serial "$ANDROID_SERIAL" \
  --scenarios hey_jandal_preflight,hey_jandal_enable_mic_granted,hey_jandal_enable_mic_denied,hey_jandal_mic_revoked_reopen_voice \
  --out-dir scripts/test-reports/permissions
```

List available scenarios:

```bash
python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --scenarios hey_jandal_preflight \
  --list-scenarios
```

Preview a scenario plan without a device:

```bash
python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --scenarios hey_jandal_preflight,hey_jandal_enable_mic_granted \
  --dry-run
```

## Weather/location scenario group

The weather/location group covers location permission interactions for the
`weather_current_location` capability. All scenarios use `permissions`,
`location`, and `weather` tags and include cleanup to restore location
permissions to `prompt` state after each run.

| Scenario ID | Steps | Permission states | Cleanup | Screenshots |
|------------|-------|-------------------|---------|-------------|
| `weather_location_denied` | 3 | prompt → prompt + fallback | reset to prompt | 2 |
| `weather_location_granted` | 2 | granted → query | reset to prompt | 1 |
| `weather_location_prompt_denied` | 4 | prompt → deny system prompt | reset to prompt | 3 |
| `weather_location_blocked_or_permanently_denied` | 2 | blocked → assert repair copy | reset to prompt | 1 |
| `weather_typed_city_without_location` | 2 | prompt → typed city query | reset to prompt | 1 |

### Scenario details

- **`weather_location_denied`** — Existing baseline. Resets location to promptable
  denied, launches a generic weather query, taps "Use a named location" on the
  permission dialog, asserts fallback guidance.
- **`weather_location_granted`** — Grants coarse and fine location, launches a
  generic weather query, asserts that no current-location permission prompt or
  blocked copy appears.
- **`weather_location_prompt_denied`** — Resets location to promptable denied,
  launches a generic weather query, taps "Use my location" to trigger the Android
  system permission prompt, then denies the system prompt. Asserts the app does
  not crash and returns to chat gracefully.
- **`weather_location_blocked_or_permanently_denied`** — Sets location to
  `blocked` (user-set, user-fixed flags), launches a generic weather query,
  asserts that a permission-related dialog appears (either the blocked repair
  copy or the standard permission request dialog depending on Samsung One UI
  behavior).
- **`weather_typed_city_without_location`** — Resets location to promptable
  denied, launches a weather query with a named city (dynamically referencing
  `weather_named_location` from `FIXTURES`), asserts that no current-location
  permission prompt or blocked copy appears.

### Running the weather group on S21

```bash
ANDROID_SERIAL=R5CR605B71K python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --serial "$ANDROID_SERIAL" \
  --scenarios weather_location_denied,weather_location_granted,weather_location_prompt_denied,weather_location_blocked_or_permanently_denied,weather_typed_city_without_location \
  --out-dir scripts/test-reports/permissions
```

### Known limitations
- **Blocked/permanently denied state**: The `blocked` state uses
  `pm set-permission-flags user-set user-fixed` which Android treats as
  "don't ask again". On Samsung One UI 15 (and likely other Samsung builds),
  this flag does NOT cause `shouldShowRequestPermissionRationale` to return
  `false`, so the app shows the standard permission request dialog rather than
  the permanently-blocked repair dialog. The scenario asserts that SOME dialog
  appears (using `expected_any_visible` for both the blocked repair copy and
  the standard dialog texts). If a future Samsung or Android version respects
  the `user-fixed` flag differently, this scenario can be tightened to assert
  only the blocked repair copy. The app correctly detects `ACCESS_COARSE_LOCATION`
  as unavailable and returns `CapabilityRequired` in either case.
- **System permission prompt**: The `weather_location_prompt_denied` scenario
  taps "Deny" or "Don't allow" on the Android system permission dialog.
  Samsung One UI may show different button labels; the runner uses
  `any_text` matching to handle both.
- **Named city weather**: The `weather_typed_city_without_location` scenario
  sends a weather query for `weather_named_location` (from `FIXTURES`, currently
  `Tokyo`) as the quick action input. If the app routes this through the GPS-based
  weather skill rather than the named-city JS skill, the permission dialog may still
  appear. This is a product behavior finding to document, not a harness issue.

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
# optionally publish an existing local report later (explicit step only)
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
