# Clock Settings & Alert Lifecycle — Architecture Decisions

**PR #1285 (merged `e1349a89`) — `feature/1283-clock-settings-overflow`**
**Scope:** Configurable alert durations + Clock settings screen + settings cog UX

## Key decisions

### Settings cog replaces overflow dropdown
- Single action → direct gear icon (`Icons.Default.Settings`), no `DropdownMenu`/`MoreVert`.
- Shared `ClockScreenTopBar` composable in `feature/settings` package, used by both `SidePanelScreen` and UI tests.
- Test tag: `clock_settings_button` (was `clock_overflow_button`).

### Alarm ring duration policy
- `lifecycleTimeoutDurationMs` selects timeout **by event type** (`ClockEventType`), not by action.
- TIMER → `timerDurationMs`, ALARM → `alarmDurationMs`, PRE_ALARM → 0L.
- Alarm auto-stop at `maxAutoSnoozes` limit uses `alarmRingDurationMs` — same as alarm snooze re-trigger.
- Timer always uses `timerAutoStopDurationMs`.

### Captured config — cold-start race fix
- `ClockRepository.getClockAlertConfig()` — one-shot suspend via `first()` on DataStore, not the async flow.
- `ClockAlertService.handleTriggerAlert()` captures config into `activeAlertConfigs: MutableMap<String, ClockAlertConfig>` keyed by `ownerId`.
- All snooze paths resolve duration via `snoozeDurationFor(alert)`:
  1. `activeAlertConfigs[ownerId]?.snoozeDurationMs` (captured per-alert)
  2. `snoozeDurationMs` (mutable field, async)
  3. `configuredSnoozeDurationMs()` → `ALARM_SNOOZE_MS` (hardcoded 10 min)
- Config cleaned up on dismissal, session stop, and `onDestroy`.

### Explicit snooze paths
- Notification Snooze button (`ACTION_SNOOZE_ALERT`): `performSnooze(alert, snoozeDurationFor(alert))`
- Voice Snooze (`ClockAlertVoiceCommand.SNOOZE`): `performSnooze(alert, snoozeDurationFor(alert))`
- Auto-snooze (`performAutoSnooze`): already used captured config.

### Auto-snooze count durability
- `autoSnoozeCount: Int` replaces old `isSnoozeRetrigger: Boolean`.
- Flows through `ClockScheduledEvent` → `AlarmManager` `PendingIntent` extras → `AlarmBroadcastReceiver` → `TriggeredClockAlert` → `ClockAlertService`.
- Durable across process death (carried in `PendingIntent`, not process memory).
- Count resets to 0 per primary occurrence (repeating alarms).
- `resolveAlertLifecycleAction()` compares `autoSnoozeCount < maxAutoSnoozes` → `AUTO_SNOOZE`, else `AUTO_STOP`.
- Labels use plain English: "0 — Don't auto-snooze", "1 — Snooze once, then stop", "2 — Snooze twice, then stop", "3 — Snooze 3 times, then stop".

### Test patterns
- `configuredSnoozeDurationMs()` extracted as `internal` top-level function for direct unit testing.
- Real route UI test uses production `ClockSettingsContent`, `DurationSetting`, `MaxAutoSnoozeSetting`, `SoundSetting` composables (no fake harness).
- `ClockSettingsActionUiTest.kt` (was `ClockOverflowSettingsUiTest.kt`).

### Restored `onDestroy()` snapshot sync
- `activeAlerts.clear()` + `activeAlertConfigs.clear()` + `syncActiveAlertSnapshot()` — prevents stale `activeAlertSnapshot` after service teardown.

### Files referenced
- `app/src/main/java/com/kernel/ai/alarm/ClockAlertService.kt`
- `app/src/main/java/com/kernel/ai/alarm/ClockAlertLifecyclePolicy.kt`
- `core/memory/src/main/java/com/kernel/ai/core/memory/clock/ClockAlertConfig.kt`
- `core/memory/src/main/java/com/kernel/ai/core/memory/clock/ClockAlertPreferences.kt`
- `core/memory/src/main/java/com/kernel/ai/core/memory/clock/ClockRepository.kt`
- `core/memory/src/main/java/com/kernel/ai/core/memory/clock/ClockRepositoryImpl.kt`
- `feature/settings/src/main/java/com/kernel/ai/feature/settings/ClockTopBar.kt`
- `app/src/main/java/com/kernel/ai/alarm/ClockAlertContract.kt`
- `app/src/main/java/com/kernel/ai/alarm/AlarmManagerClockScheduler.kt`
- `app/src/main/java/com/kernel/ai/alarm/AlarmBroadcastReceiver.kt`
- `docs/research/clock-system-spec.md`
