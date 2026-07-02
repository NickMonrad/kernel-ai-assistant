# Permission scenario evidence

**Source:** `on_device`
**Suite:** `permission_scenarios`
**Device:** S21 (Samsung, `s21-exynos`)
**Commit:** `b311bf8`
**Branch:** `feature/1353-stale-external-state-scenarios`
**Run ID:** `on_device-2026-07-02T02-31-58Z-s21-exynos`

| Scenario | Functional | UX | Steps | Taps | Settings hops | Back | Duration |
|---|---|---|---:|---:|---:|---:|---:|
| Dashboard reflects microphone permission revoked externally via ADB | fail | fail | 11 | 2 | 0 | 0 | 34.0s |
| Dashboard reflects location permission revoked externally via ADB | fail | fail | 11 | 2 | 0 | 0 | 34.5s |
| Weather location permission revoked externally after prior grant — fallback UX shown on re-query | pass | warning | 8 | 0 | 0 | 0 | 38.4s |

## Artifacts

- Raw report: `result.json`
- Schema-compatible evidence: `evidence.json`
- Logcat: `logcat.txt`
- Screenshots: `screenshots/`

## Dashboard reflects microphone permission revoked externally via ADB

- Scenario ID: `stale_state_dashboard_reflects_external_mic_revoke`
- Functional result: `fail`
- UX result: `fail`
- UX warnings: steps 10 > 8, duration 34.0s > 30s
- Steps:
  - 01. `grant_mic` — pass: Permissions set to granted: android.permission.RECORD_AUDIO
  - 02. `launch_app` — pass: MainActivity launched
  - 03. `open_settings` — pass: Tapped target content_desc='Settings'
  - 04. `scroll_settings` — pass: Swiped (540,1200)→(540,400)
  - 05. `tap_app_permissions` — pass: Tapped target text='App Permissions' (`screenshots/05-stale-state-dashboard-reflects-external-mic-revoke-tap-app-permissions.png`)
  - 06. `assert_mic_granted` — pass: Waited for package com.kernel.ai.debug in foreground
  - 07. `background_app_before_revoke` — pass: Pressed HOME
  - 08. `revoke_mic_externally` — pass: Permissions set to revoked: android.permission.RECORD_AUDIO
  - 09. `relaunch_after_revoke` — pass: MainActivity launched
  - 10. `open_settings_post_revoke` — fail: Target not visible within 8s: content_desc='Settings'; visible sample=['Review the permissions and Android access Jandal uses for specific features. Tap an item to repair access in Android settings.', 'Phone', 'Hands-free calling', 'Phone not granted', 'Microphone', 'Voice input for Quick Actions and Hey Jandal', 'Microphone not granted', 'Notifications']
  - 14. `reset_mic_after_stale_test` — pass: Permissions set to prompt: android.permission.RECORD_AUDIO

## Dashboard reflects location permission revoked externally via ADB

- Scenario ID: `stale_state_dashboard_reflects_external_location_revoke`
- Functional result: `fail`
- UX result: `fail`
- UX warnings: steps 10 > 8, duration 34.5s > 30s
- Steps:
  - 01. `grant_location` — pass: Permissions set to granted: android.permission.ACCESS_COARSE_LOCATION, android.permission.ACCESS_FINE_LOCATION
  - 02. `launch_app` — pass: MainActivity launched
  - 03. `open_settings` — pass: Tapped target content_desc='Settings'
  - 04. `scroll_settings` — pass: Swiped (540,1200)→(540,400)
  - 05. `tap_app_permissions` — pass: Tapped target text='App Permissions' (`screenshots/05-stale-state-dashboard-reflects-external-location-revoke-tap-app-permissions.png`)
  - 06. `assert_location_granted` — pass: Waited for package com.kernel.ai.debug in foreground
  - 07. `background_app_before_revoke` — pass: Pressed HOME
  - 08. `revoke_location_externally` — pass: Permissions set to revoked: android.permission.ACCESS_COARSE_LOCATION, android.permission.ACCESS_FINE_LOCATION
  - 09. `relaunch_after_revoke` — pass: MainActivity launched
  - 10. `open_settings_post_revoke` — fail: Target not visible within 8s: content_desc='Settings'; visible sample=['Review the permissions and Android access Jandal uses for specific features. Tap an item to repair access in Android settings.', 'Phone', 'Hands-free calling', 'Phone not granted', 'Microphone', 'Voice input for Quick Actions and Hey Jandal', 'Microphone not granted', 'Notifications']
  - 14. `reset_location_after_stale_test` — pass: Permissions set to prompt: android.permission.ACCESS_COARSE_LOCATION, android.permission.ACCESS_FINE_LOCATION

## Weather location permission revoked externally after prior grant — fallback UX shown on re-query

- Scenario ID: `stale_state_weather_location_revoked_after_prior_grant`
- Functional result: `pass`
- UX result: `warning`
- UX warnings: duration 38.4s > 30s
- Steps:
  - 01. `grant_location` — pass: Permissions set to granted: android.permission.ACCESS_COARSE_LOCATION, android.permission.ACCESS_FINE_LOCATION
  - 02. `launch_app` — pass: MainActivity launched
  - 03. `weather_success_with_grant` — pass: Quick action launched: what's the weather (`screenshots/03-stale-state-weather-location-revoked-after-prior-grant-weather-success-with-grant.png`)
  - 04. `background_app_before_revoke` — pass: Pressed HOME
  - 05. `revoke_location_externally` — pass: Permissions set to revoked: android.permission.ACCESS_COARSE_LOCATION, android.permission.ACCESS_FINE_LOCATION
  - 06. `relaunch_and_weather_fallback` — pass: MainActivity launched
  - 07. `weather_fallback_with_revoked_location` — pass: Quick action launched: what's the weather (`screenshots/07-stale-state-weather-location-revoked-after-prior-grant-weather-fallback-with-revoked-location.png`)
  - 08. `reset_location_after_stale_weather` — pass: Permissions set to prompt: android.permission.ACCESS_COARSE_LOCATION, android.permission.ACCESS_FINE_LOCATION
