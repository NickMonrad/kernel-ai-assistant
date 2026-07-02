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

| Field               | Required for          | Description |
|--------------------|-----------------------|-------------|
| `id`               | All steps             | Unique step ID within the scenario |
| `action`           | All steps             | One of: `set_permission_state`, `set_appops`, `launch_main`, `launch_quick_action`, `tap_visible`, `swipe`, `wait_for_package`, `tap_toggle_for_text`, `set_toggle_state`, `check_default_assistant_ready`, `press_home`, `press_back` |
| `expected`         | All steps             | Human-readable description of what should happen |
| `permission`       | `set_permission_state`, `set_appops` | Android permission name (e.g. `android.permission.RECORD_AUDIO`) |
| `state`            | `set_permission_state`| Permission state: `granted`, `revoked`, `prompt`, `blocked` |
| `mode`             | `set_appops`          | Appops mode: `allow`, `deny`, `default` |
| `target`           | `tap_visible`         | Target descriptor with `text`, `content_desc`, `resource_id`, or `any_text` |
| `query`            | `launch_quick_action` | Quick action query string |
| `start_x`, `start_y`, `end_x`, `end_y` | `swipe` | Pixel coordinates (required) |
| `duration_ms`      | `swipe`               | Swipe duration in ms (default 300) |
| `package`          | `wait_for_package`    | Android package name (e.g. `com.android.settings`) |
| `anchor_text`      | `tap_toggle_for_text`, `set_toggle_state` | Text label associated with the toggle |
| `checked`          | `set_toggle_state`    | Boolean target state for the toggle |
| `also_apply`       | `set_permission_state`| List of additional permission names to apply the same state to (e.g. `["android.permission.ACCESS_FINE_LOCATION"]`) |
| `screenshot`       | Any step              | Boolean, capture screenshot at this step |
| `expected_visible` | Any step              | List of texts where each must be visible via exact match (checks `text` and `contentDescription`) |
| `expected_not_visible` | Any step           | List of texts where none must be visible via exact match (polls for 1s) |
| `expected_any_visible` | Any step           | List of texts where at least one must be an exact match |
| `expected_visible_contains` | Any step      | List of texts where each must appear as a substring |
| `expected_any_visible_contains` | Any step   | List of texts where at least one must appear as a substring |
| `expected_toggle_state` | Any step         | Dict with `anchor_text` and `checked` to verify a toggle state |
| `blocked_if_visible` | Any step           | Dict with `texts` and `reason`; if texts are visible, scenario reports as blocked |

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


## Notifications/exact alarms/clock scenario group

The notifications/exact alarms/clock group covers permission interactions for the
`jandal_alarms_timers` capability. All scenarios use `permissions`, `clock` tags
and include cleanup to restore `POST_NOTIFICATIONS` permission state.

| Scenario ID | Steps | Capabilities tested | Cleanup | Screenshots |
|------------|-------|-------------------|---------|-------------|
| `clock_timer_notifications_allowed` | 2 | POST_NOTIFICATIONS granted → timer success | reset POST_NOTIFICATIONS to prompt | 1 |
| `clock_timer_notifications_denied` | 2 | POST_NOTIFICATIONS revoked → timer blocked/degraded | restore POST_NOTIFICATIONS to granted | 1 |
| `clock_alarm_exact_alarm_allowed` | 3 | POST_NOTIFICATIONS granted → alarm success | cancel alarm + reset POST_NOTIFICATIONS to prompt | 1 |
| `clock_alarm_schedule_exact_alarm_appop_denied` | 3 | Documents that SCHEDULE_EXACT_ALARM appop denial does not block alarm scheduling when USE_EXACT_ALARM is declared | cancel alarm + restore SCHEDULE_EXACT_ALARM to allow + reset POST_NOTIFICATIONS | 1 |

### Scenario details

- **`clock_timer_notifications_allowed`** — Grants `POST_NOTIFICATIONS`, sends a
  short deterministic timer command (`set timer for 10 seconds` via the
  `short_timer_seconds` fixture), asserts `"Timer set for"` success text appears
  in the chat response.
- **`clock_timer_notifications_denied`** — Revokes `POST_NOTIFICATIONS`, sends the
  same short timer command, asserts that the app shows either a success message
  (if the timer works without notification permission) or a notification-blocked
  error message (if capability enforcement blocks it).
- **`clock_alarm_exact_alarm_allowed`** — Grants `POST_NOTIFICATIONS`, sends
  `"set alarm for 9:00 AM"`, asserts `"Alarm set for"` success text appears in the
  chat response. Cleans up by cancelling the alarm to avoid leaving persistent
  alarms on the device.
- **`clock_alarm_schedule_exact_alarm_appop_denied`** — A **platform finding scenario**:
  grants `POST_NOTIFICATIONS`, then uses `set_appops` to deny `SCHEDULE_EXACT_ALARM`
  via `appops`. Sends an alarm command and asserts that the app schedules the alarm
  successfully despite the appop denial — because the app declares `USE_EXACT_ALARM`
  (manifest permission, API 33+), not `SCHEDULE_EXACT_ALARM` (runtime appop).
  Cleans up by cancelling the alarm and restoring both `SCHEDULE_EXACT_ALARM` and
  `POST_NOTIFICATIONS`.
### Running the clock group on S21

```bash
ANDROID_SERIAL=R5CR605B71K python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --serial "$ANDROID_SERIAL" \
  --scenarios clock_timer_notifications_allowed,clock_timer_notifications_denied,clock_alarm_exact_alarm_allowed,clock_alarm_schedule_exact_alarm_appop_denied \
  --out-dir scripts/test-reports/permissions
```

## Stale/external-state scenario group

These scenarios validate that the app correctly reflects permission state changes
applied externally (via ADB) rather than through the app's own UI. Two dashboard
refresh scenarios follow a grant → dashboard → assert granted → background →
revoke → re-navigate → assert not-granted pattern. The weather scenario validates
that the weather skill re-checks permission at query time (not cached).

All stale-state scenarios use `permissions`, `stale_state`, and `external_state` tags
plus the capability-specific tag (e.g. `microphone`, `location`, `weather`). Each
scenario includes cleanup to restore changed permissions to `prompt` state.

| Scenario ID | Steps | Permission | Cleanup | Screenshots |
|------------|-------|------------|---------|-------------|
| `stale_state_dashboard_reflects_external_mic_revoke` | 10 | RECORD_AUDIO | restore to prompt | 2 |
| `stale_state_dashboard_reflects_external_location_revoke` | 10 | ACCESS_COARSE_LOCATION + ACCESS_FINE_LOCATION | restore to prompt | 2 |
| `stale_state_weather_location_revoked_after_prior_grant` | 7 | ACCESS_COARSE_LOCATION + ACCESS_FINE_LOCATION | restore to prompt | 2 |

### Scenario details

- **`stale_state_dashboard_reflects_external_mic_revoke`** — Grants `RECORD_AUDIO`,
  opens the dashboard via Settings, asserts "Microphone granted" is visible. Then
  revokes `RECORD_AUDIO` via ADB, re-navigates to the dashboard, and asserts
  "Microphone not granted" is visible. Validates that the dashboard refreshes
  permission state from the external permission source.

- **`stale_state_dashboard_reflects_external_location_revoke`** — Same pattern as
  the mic scenario but for `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`.
  Asserts "Location granted" / "Location not granted" row texts.

- **`stale_state_weather_location_revoked_after_prior_grant`** — Tests weather's
  runtime permission re-check (not cached). Grants both coarse and fine location
  permissions, sends a current-location weather query, asserts it succeeds
  without a permission prompt. Then backgrounds the app, revokes both location
  permissions via ADB, relaunches, sends the same weather query again, and
  asserts the permission dialog (\"Use your location for local weather?\") is now
  shown. Validates that the weather skill re-checks permission at query time
  rather than caching the prior grant state. Coverage distinct from #1360's
  basic location-denied cases.

### Running the stale-state group on S21

```bash
ANDROID_SERIAL=R5CR605B71K python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --serial "$ANDROID_SERIAL" \
  --scenarios stale_state_dashboard_reflects_external_mic_revoke,stale_state_dashboard_reflects_external_location_revoke,stale_state_weather_location_revoked_after_prior_grant \
  --out-dir scripts/test-reports/permissions
```

### Limitations

- **Dashboard refresh timing**: The dashboard auto-refreshes on `ON_RESUME`, but
  the scenarios explicitly re-navigate via `tap_visible "App Permissions"` to
  ensure the refresh is triggered and the assertion is stable. Without the
  explicit re-navigation, the assertion could race against a pending refresh.
- **Permission state persistence**: Android `pm` commands (`grant`/`revoke`)
  persist across app restarts but may be affected by device reboots. Scenarios
  assume the device is in a known permission state before the run.
- **One permission per scenario**: Each scenario tests a single permission.
  Composite scenarios (e.g., revoking multiple permissions simultaneously)
  are not covered in this slice.


### Known limitations

- **POST_NOTIFICATIONS**: On Android 13+ (API 33+), `POST_NOTIFICATIONS` is a
  runtime permission. The runner uses `pm grant` / `pm revoke` to control its
  state. `minSdk=35` means this permission is always relevant.
- **SCHEDULE_EXACT_ALARM vs USE_EXACT_ALARM**: The app declares `USE_EXACT_ALARM`
  (manifest permission, API 33+) rather than requesting `SCHEDULE_EXACT_ALARM` at
  runtime. The runner's `set_appops` action can deny `SCHEDULE_EXACT_ALARM` via
  `appops`, but this does NOT prevent the app from scheduling alarms on builds
  where `USE_EXACT_ALARM` is satisfied. The `clock_alarm_schedule_exact_alarm_appop_denied`
  scenario documents this behavior as a platform finding, using
  `expected_any_visible_contains` to accept either success or blocked copy.
  If future Android versions change the relationship, the scenario can be tightened.
- **Alarm cleanup**: Both alarm scenarios run a `"cancel my alarm"` quick action
  during cleanup to avoid leaving persistent alarms on the device. The cancel
  fires `cancelNextAlarm()` in the app's clock repository. Timer cleanup focuses
  on permission state (10-second timers expire on their own).
- **Chat response text**: Timer/alarm results appear as chat messages, not system
  dialogs. The runner waits for the expected text with an extended
  `timeout_seconds: 15` to account for app processing time.
- **Reminders**: Reminder flows (`add_reminder` intent) are not covered in this
  slice. They require time/date slot resolution which adds complexity. Documented
  as a follow-up under #1353.

### New runner action: `set_appops`

The `set_appops` action controls appops-managed permissions (like
`SCHEDULE_EXACT_ALARM`). It takes `permission` (the appops name) and `mode`
(`allow`, `deny`, or `default`) and runs `adb shell appops set $PACKAGE $permission $mode`.
Useful for testing permissions that are not standard Android runtime permissions.

## App Permissions dashboard scenario group

This group validates that the App Permissions dashboard opens, displays expected
launch-critical permission rows, correctly reflects grant state from ADB-managed
permission changes, and that repair CTAs open the system App Info settings page.
The dashboard auto-refreshes on `ON_RESUME` and has a manual "Refresh" button.

No capability gating — the dashboard is always available.

| Scenario ID | Steps | Capabilities tested | Cleanup | Screenshots |
|------------|-------|-------------------|---------|-------------|
| `permissions_dashboard_opens` | 3 | Dashboard opens via Settings → App Permissions, title and rows visible | none | 1 |
| `permissions_dashboard_location_state_refresh` | 7 | Location revoked→"Not granted" visible, granted→"Granted" visible after Refresh | restore ACCESS_COARSE_LOCATION to prompt | 1 |
| `permissions_dashboard_microphone_state_refresh` | 7 | Microphone revoked→"Not granted" visible, granted→"Granted" visible after Refresh | restore RECORD_AUDIO to prompt | 1 |
| `permissions_dashboard_notification_state` | 5 | POST_NOTIFICATIONS revoked→"Not granted" visible on dashboard, state surfaced | restore POST_NOTIFICATIONS to prompt | 1 |
| `permissions_dashboard_repair_cta_opens_settings` | 13 | Tap denied row→system settings opens→launch_main→re-navigate to dashboard | restore ACCESS_COARSE_LOCATION to prompt | 2 |

### Scenario details

- **`permissions_dashboard_opens`** — Taps the "Settings" icon in the Chat screen
  top bar, scrolls, taps "App Permissions" row, asserts "App Permissions" title
  and key rows (e.g. Microphone, Location, Notifications) are visible.
- **`permissions_dashboard_location_state_refresh`** — Revokes `ACCESS_COARSE_LOCATION`,
  opens dashboard via Settings, asserts "Location" row shows "Not granted" icon,
  grants via ADB, taps "Refresh" button, asserts "Granted" icon appears.
- **`permissions_dashboard_microphone_state_refresh`** — Same pattern as location
  refresh but for `RECORD_AUDIO` / Microphone row.
- **`permissions_dashboard_notification_state`** — Revokes `POST_NOTIFICATIONS`,
  opens dashboard, asserts "Notifications" row shows "Not granted" icon.
  Notification state IS surfaced on the dashboard (not a product gap). Cleanup
  restores the permission.
- **`permissions_dashboard_repair_cta_opens_settings`** — Revokes
  `ACCESS_COARSE_LOCATION`, opens dashboard, taps denied Location row, asserts
  system App Info settings opens via `wait_for_package`, relaunches the app
  (`launch_main`), re-navigates to dashboard via Settings to confirm the
  dashboard survives the round-trip.

### New runner actions

#### `wait_for_package`

The `wait_for_package` action waits for a specific Android package to be in the
foreground. Useful for detecting navigation to system settings surfaces and
waiting for the app to return. It takes a `package` field (the package name,
e.g. `com.android.settings`) and optional `timeout_seconds`.

```python
{
    "action": "wait_for_package",
    "package": "com.android.settings",
    "timeout_seconds": 10,
}
```

#### `swipe`

The `swipe` action performs a touch swipe gesture at specified pixel coordinates.
Takes `start_x`, `start_y`, `end_x`, `end_y` (required) and `duration_ms` (optional,
default 300). Useful for scrolling scrollable lists when navigating deep UI paths.

```python
{
    "action": "swipe",
    "start_x": 540,
    "start_y": 1200,
    "end_x": 540,
    "end_y": 400,
    "duration_ms": 200,
}
```

### Known limitations
- **"Not granted" / "Granted" assertions are row-specific content descriptions**: Each
  permission row's trailing icon has `contentDescription = "<RowLabel> granted"` or
  `"<RowLabel> not granted"` (e.g. `"Location granted"`, `"Microphone not granted"`).
  Assertions use these row-qualified content descriptions, which uniquely identify
  the state of a specific row. This prevents false-pass when another row has the same
  state. Update the product `PermissionRow` composable if new rows are added.
- **Refresh via button, not auto-ON_RESUME**: The dashboard has an automatic
  `ON_RESUME` refresh observer, but permission changes made via ADB while the
  dashboard is already open do not trigger `ON_RESUME`. Scenarios use the manual
  "Refresh" button instead. If the activity is re-created (e.g. via `am start`),
  `init {}` calls `refresh()` automatically.
- **Samsung settings package**: On Samsung One UI, the settings package is
  `com.android.settings` (same as AOSP). No known divergence for the App Info
  entry point.
- **Dashboard navigation requires Settings screen scroll**: The "App Permissions"
  row is below the visible area on the Settings screen. The scenarios use the
  `swipe` action to scroll down before tapping it. These swipe coordinates are
  calibrated for S21 (1080×2340). Non-S21 devices may need different coordinates.
- **State refresh timing**: After granting a permission via ADB and tapping
  "Refresh", the view model refresh coroutine may take up to a few seconds.
  The Refresh step uses `timeout_seconds: 15` to accommodate this.
- **No special access or stale-state coverage**: The current scenarios only cover
  runtime permission state on the dashboard. Special access (DND, write settings)
  and stale/external-state scenarios are not included in this slice. Documented
  as follow-up under #1353.

### Running the dashboard group on S21

```bash
ANDROID_SERIAL=R5CR605B71K python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --serial "$ANDROID_SERIAL" \
  --scenarios permissions_dashboard_opens,permissions_dashboard_location_state_refresh,permissions_dashboard_microphone_state_refresh,permissions_dashboard_notification_state,permissions_dashboard_repair_cta_opens_settings \
  --out-dir scripts/test-reports/permissions
```

## Special access permission scenario group

This group validates that the App Permissions dashboard lists the Do Not Disturb and
Modify system settings special-access rows, correctly reflects their grant state,
and that repair CTAs open the appropriate system settings panels.

Special-access permissions are NOT standard Android runtime permissions. They are
managed through system settings panels and checked via platform APIs
(`NotificationManager.isNotificationPolicyAccessGranted()` for DND,
`Settings.System.canWrite()` for write settings). State toggling via `set_appops`
may not be deterministic on all devices — scenarios that require a denied state
use `blocked_if_visible` to report setup-limited when the permission is already
granted and cannot be reliably revoked.

No capability gating — the dashboard is always available.

| Scenario ID | Steps | Capabilities tested | Cleanup | Screenshots |
|------------|-------|-------------------|---------|-------------|
| `special_access_dashboard_state` | 5 | Both special access rows visible on dashboard, row labels show regardless of grant state | none | 1 |
| `special_access_write_settings_repair_opens_settings` | 14 | Deny WRITE_SETTINGS via appops → tap row → system Manage Write Settings opens → return → re-navigate | restore WRITE_SETTINGS to allow | 1 |
| `special_access_dnd_repair_opens_settings` | 14 | Observe DND state (no toggle) → tap row if denied → system Notification Policy Access settings opens → return → re-navigate | none (read-only) | 1 |

### Scenario details

- **`special_access_dashboard_state`** — Opens dashboard via Settings, scrolls, taps
  "App Permissions", asserts "Do Not Disturb" and "Modify system settings" rows are
  visible on the dashboard. Does not assert specific grant state — this is a
  read-only row presence verification. No cleanup needed.
- **`special_access_write_settings_repair_opens_settings`** — Denies `WRITE_SETTINGS`
  via `appops`, opens dashboard via Settings, asserts "Modify system settings" row
  shows "Modify system settings not granted" icon. If the row already shows
  "Modify system settings granted" (common on Samsung where WRITE_SETTINGS is
  auto-granted), the scenario reports as blocked (setup-limited). Otherwise, taps
  the denied row, asserts the system Manage Write Settings panel opens via
  `wait_for_package`, relaunches the app, re-navigates to dashboard to confirm
  the round-trip. Cleanup restores WRITE_SETTINGS to allow.
- **`special_access_dnd_repair_opens_settings`** — Opens dashboard via Settings,
  asserts "Do Not Disturb" row is visible. If the row already shows
  "Do Not Disturb granted", the scenario reports as blocked (setup-limited)
  because `ACCESS_NOTIFICATION_POLICY` is not an appops-managed permission and
  cannot be toggled deterministically via ADB. Otherwise, taps the denied row,
  asserts the system Notification Policy Access settings opens via
  `wait_for_package`, relaunches, re-navigates to dashboard.
  No cleanup needed — no device state was changed.

### Samsung / Android limitations

- **DND access is a notification policy access setting**, not a standard runtime
  permission. The `appops` command does not support `ACCESS_NOTIFICATION_POLICY`
  on Android 14/15. DND state toggling via `settings put secure` is possible
  but not exposed through the runner's action set.
- **WRITE_SETTINGS is often auto-granted on Samsung devices** and may not be
  revocable via `appops` on all Samsung builds. The comment in
  `AppPermissionsViewModel.kt` explicitly notes this: *"usually auto-granted on
  Samsung devices, but included for completeness on devices that deny it."*
  On the S21 test device, `appops set WRITE_SETTINGS deny` DOES work.
- **Repair CTA navigation is the stable assertion**: Both scenarios assert that
  tapping a denied special-access row opens `com.android.settings`. Asserting
  the exact settings panel content is fragile across OEMs.
- **State toggling may not be deterministic**: The `set_appops` action for
  `WRITE_SETTINGS` is verified to work on S21. For DND, the scenario reads the
  current state and only proceeds if already denied, producing a blocked
  (setup-limited) result when granted.
- **Return-to-dashboard confirmation**: Both repair scenarios return to the
  dashboard after the settings round-trip to confirm the app is still navigable.

### Running the special access group on S21

```bash
python3 scripts/run_permission_scenarios.py \
  --device-id s21-exynos \
  --serial R5CR605B71K \
  --scenarios special_access_dashboard_state,special_access_write_settings_repair_opens_settings,special_access_dnd_repair_opens_settings \
  --out-dir scripts/test-reports/permissions
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
