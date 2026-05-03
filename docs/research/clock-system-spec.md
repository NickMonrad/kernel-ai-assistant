# Clock system specification
> **Primary issue:** [#527](https://github.com/NickMonrad/kernel-ai-assistant/issues/527)
> **Related:** [#677](https://github.com/NickMonrad/kernel-ai-assistant/issues/677)
> **Status:** proposed architecture and UX direction
> **Last updated:** 2026-05-02

---

## 1. Purpose

Define a full internal **Clock** feature for Kernel AI so alarms and timers are fully app-owned instead of split across Room state, in-app UI, and OEM clock-app intents.

This specification intentionally expands the work from "fix alarm/timer tech debt" into a complete Clock product surface with four tabs:

1. **Alarms**
2. **Timers**
3. **World Clock**
4. **Stopwatch**

Quick Actions, chat, and future voice flows should create and control the same underlying clock objects, but **the Clock screen becomes the primary management surface**.

---

## 2. Current repo state

### 2.1 What already exists

The repo already has partial internal groundwork:

- `ScheduledAlarmEntity` / `ScheduledAlarmDao`
- `AlarmManager` scheduling for date-specific alarms and timers
- boot rescheduling via `BootCompletedReceiver`
- basic in-app scheduled alarms UI
- basic alarm/timer trigger notification via `AlarmBroadcastReceiver`

Current implementation anchors:

- `core/memory/src/main/java/com/kernel/ai/core/memory/entity/ScheduledAlarmEntity.kt`
- `core/memory/src/main/java/com/kernel/ai/core/memory/dao/ScheduledAlarmDao.kt`
- `core/skills/src/main/java/com/kernel/ai/core/skills/natives/NativeIntentHandler.kt`
- `app/src/main/java/com/kernel/ai/alarm/AlarmBroadcastReceiver.kt`
- `app/src/main/java/com/kernel/ai/alarm/BootCompletedReceiver.kt`
- `feature/settings/src/main/java/com/kernel/ai/feature/settings/ScheduledAlarmsViewModel.kt`
- `feature/settings/src/main/java/com/kernel/ai/feature/settings/SidePanelViewModel.kt`

### 2.2 Why the current behavior is insufficient

The current repo has completed the core alarm/timer ownership cutover, but alert-path gaps remain:

- alarm and timer creation/cancellation now stay inside the app-owned clock backend
- `AlarmBroadcastReceiver` still posts a notification but does not own a long-lived ringing session
- active timer countdown notifications are still missing
- full-screen alarm/timer parity still needs a dedicated alert service

This explains the current user-visible problem for the internal alert path:

- for app-owned scheduled alarms and timers, vibrate/silent currently results in only a short buzz instead of real clock-style ringing

This repo-state note applies to the current internal alert path. OEM clock-app fallback alarms still use OEM behavior.

That is not a notification-copy problem. It is an **architecture problem**: the app does not yet own the alert lifecycle.

---

## 3. Product goals

### 3.1 Functional goals

The Clock feature should provide:

- fully internal alarms and timers
- app-owned ringing/alert lifecycle
- app-owned alarm/timer management UI
- reliable background behavior when the app is not open
- reboot restore for future alarms and active timers
- alarm-volume-based sound behavior for both alarms and timers
- proper ongoing countdown notifications for active timers
- world clock support in the same surface
- stopwatch support in the same surface

### 3.2 Non-goals for the first cut

The first cut does **not** need to include:

- sleep tracking
- bedtime routines
- calendar-linked smart alarm predictions
- wake-word style always-listening while an alert is active
- cloud sync of clock data

---

## 4. Locked UX decisions from review

These are treated as the reviewed defaults for the first full Clock implementation.

### 4.1 Alert presentation

- **Alarms** should attempt a full-screen ringing UI.
- If modern Android blocks or gates full-screen launch, alarms must fall back to a high-priority, lock-screen-visible ringing notification that still exposes `Dismiss` and `Snooze 10 min` and opens the alert activity when tapped.
- **Timers** are notification-first and use full-screen only when the device is locked.

### 4.2 Alarm repeat behavior

Support:

- one-off alarms
- daily repeat
- selected weekdays repeat

### 4.3 Active timer visibility

- every active timer gets its own ongoing countdown notification
- when a timer is cleared, it leaves the active list immediately
- cleared timers remain visible in a small **Recent / Completed** section

### 4.4 Default ringing actions

- **Alarm:** `Dismiss` and `Snooze 10 min`
- **Timer:** `Stop` and `Add 1 minute`

### 4.5 Pre-alarm notification

For repeating alarms, show a **pre-alarm notification 30 minutes before trigger time** with an action to cancel **today only**.

### 4.6 Audio behavior

For both alarms and timers:

- audio follows the **system alarm volume**
- alerts should still ring when the phone is on silent or vibrate
- alerts should still respect Android's DND policy and system-level alarm allowances

### 4.7 Voice interaction while ringing

Desired UX direction:

- when an alarm or timer is actively ringing, the app should support voice commands such as:
  - `stop`
  - `cancel`
  - `snooze`

Recommended delivery note:

- ship the core alert service and button-based controls first
- then add alert-time voice listening on top of the same alert UI/service
- do **not** block the base Clock cutover on always-on voice behavior

### 4.8 Clock surface scope

Target Clock surface:

1. Alarms
2. Timers
3. World Clock
4. Stopwatch

Recommended sequencing:

- alarms and timers are the base functionality and should land first
- world clock and stopwatch should live in the same Clock surface, but can follow once the base alarm/timer backend is stable
---

## 5. User-facing behavior

### 5.1 Will alarms and timers still work if the app is closed?

Yes — if implemented with:

- Room as source of truth
- `AlarmManager` exact scheduling
- broadcast receivers for trigger delivery
- a reboot receiver for re-registration
- an alert service for ringing/audio ownership

The app does **not** need to be visibly open for alarms and timers to fire.

### 5.2 Will they still work after reboot?

Yes.

On `BOOT_COMPLETED`, the app should:

- reload future alarms
- restore active timers that were still valid at shutdown/reboot time
- re-register their next trigger with `AlarmManager`
- reconcile any expired timers/alarms that elapsed while the device was unavailable

### 5.3 Can a repeating alarm be cancelled for today only?

Yes.

The recommended behavior:

- 30 minutes before a repeating alarm, show a notification
- action: `Skip today`
- this suppresses only the next occurrence
- the underlying repeat rule remains unchanged for future days

### 5.4 Should cleared timers remain visible?

Yes, but **not** in the active list.

Recommended behavior:

- active timers disappear from the running list immediately when stopped/dismissed
- recently completed/stopped timers move into a small `Recent / Completed` section
- that section can be user-cleared later

### 5.5 How hard is voice `stop` / `cancel` while an alert is ringing?

Moderate.

It is feasible once the alert lifecycle is centralized because the app then has:

- a dedicated alert service
- a known foreground/full-screen alert UI
- a narrow command grammar (`stop`, `dismiss`, `snooze`, `add one minute`)

The hard part is not intent parsing. The hard part is:

- microphone ownership during active alert playback
- interaction with DND/audio focus
- deciding whether listening is explicit or automatic
- avoiding false positives from ambient audio or the alert sound itself

Recommendation:

- architecture should support it from day one
- but implementation should land **after** the base ringing service and UI are stable

---

## 6. Proposed architecture

### 6.1 Design principle

The system should have:

- one source of truth
- one scheduling seam
- one alert/ringing seam
- one UI management surface

Quick Actions, chat, settings, side panel, notifications, and future voice flows should all call the **same clock domain**, not implement separate scheduling logic.

### 6.2 Proposed modules and responsibilities

#### `ClockRepository`

Owns all read/write operations for:

- alarms
- timers
- world clocks
- stopwatch state

This becomes the single source of truth for the whole clock domain.

#### `ClockScheduler`

Owns all `AlarmManager` interactions:

- schedule exact alarm triggers
- schedule pre-alarm notification events (for example the 30-minute `Skip today` reminder)
- schedule timer completion triggers
- cancel scheduled work
- restore scheduled work after reboot
- compute next trigger for repeating alarms and their paired pre-alarm reminder events

No UI layer or skill handler should build raw `PendingIntent`s directly once this exists.

#### `ClockAlertService`

Owns the live alert lifecycle for ringing alarms and timers:

- plays alarm-style audio using alarm volume
- vibrates using alarm-style behavior
- posts/updates ringing notifications
- responds to dismiss / snooze / add-minute actions
- launches full-screen UI where appropriate
- becomes the missing owner that replaces the current "short buzz only" notification behavior

#### `ClockTriggerReceiver`

Receives scheduled trigger broadcasts from `AlarmManager` and:

- resolves the triggered item identity and hands control to `ClockAlertService`
- starts `ClockAlertService`
- performs no independent alert UX decisions beyond handing control to the service

#### `ClockActionReceiver`

Receives notification/full-screen actions such as:

- dismiss alarm
- snooze alarm
- stop timer
- add one minute to timer
- skip today's repeating alarm

It should forward those actions into `ClockAlertService` / `ClockRepository` rather than owning alert-state transitions itself.

#### `ActiveTimerNotificationService`

Foreground service for active timer countdowns:

- posts one ongoing notification per active timer
- updates remaining time on a predictable cadence
- stops when no timers remain active

#### `ClockAlertActivity`

Full-screen UI for alarm ringing and locked-device timer completion.

---

## 7. Data model

The current `ScheduledAlarmEntity` is a useful bridge but not the right long-term shape for a full clock product.

### 7.1 Alarm

```kotlin
data class AlarmEntity(
    val id: String,
    val label: String?,
    val hour: Int,
    val minute: Int,
    val repeatRule: AlarmRepeatRule,
    val timezoneId: String,
    val enabled: Boolean,
    val nextTriggerAtMillis: Long,
    val snoozedUntilMillis: Long?,
    val skipNextOccurrence: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
```

`AlarmRepeatRule`:

- `OneOff(dateMillis?)`
- `Daily`
- `SelectedWeekdays(bitmask or set)`

### 7.2 Timer

```kotlin
data class TimerEntity(
    val id: String,
    val label: String?,
    val durationMillis: Long,
    val startedAtMillis: Long?,
    val endsAtMillis: Long?,
    val remainingAtPauseMillis: Long?,
    val state: TimerState,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
```

`TimerState`:

- `RUNNING`
- `PAUSED`
- `RINGING`
- `COMPLETED`
- `CANCELLED`

### 7.3 World clock

```kotlin
data class WorldClockEntity(
    val id: String,
    val zoneId: String,
    val displayName: String,
    val sortOrder: Int,
)
```

### 7.4 Stopwatch

```kotlin
data class StopwatchStateEntity(
    val id: String = "default",
    val isRunning: Boolean,
    val startedAtMillis: Long?,
    val accumulatedElapsedMillis: Long,
    val updatedAtMillis: Long,
)
```

Laps should live in a separate lap table.

---

## 8. Audio and alert policy

### 8.1 Alarm volume

Both alarms and timers should play through the **system alarm volume**.

Implementation intent:

- use alarm-style audio attributes (`USAGE_ALARM`)
- use system alarm ringtone URI by default
- do not rely on notification-channel-only sound as the primary playback mechanism

### 8.2 Silent/vibrate behavior

Clock alerts should still ring on silent/vibrate because they are alarm-class alerts, not standard notifications.

### 8.3 DND behavior

Respect Android/system DND policy.

Interpretation:

- if the platform allows alarms through DND, alarms/timers should use that path
- the app should not try to fake around DND using media streams
- the UI should be honest if system policy is suppressing alerts

### 8.4 Vibration

Provide vibration alongside alarm audio where supported.

### 8.5 Escalation and timeout

Default alert lifecycle:

- ring until user action or a defined timeout
- after timeout, notification remains visible as missed alarm/timer
- exact timeout can be finalized during implementation

---

## 9. UX specification

## 9.1 Clock home

A dedicated Clock screen with four tabs:

```text
┌──────────────────────────────────────┐
│ Clock                                │
│ [Alarms] [Timers] [World Clock] [SW] │
├──────────────────────────────────────┤
│ tab content                          │
└──────────────────────────────────────┘
```

### Navigation expectations

- reachable from app navigation directly
- notification taps deep-link into the relevant tab/item
- Quick Actions/chat-created items appear here immediately

---

## 9.2 Alarms tab

```text
┌──────────────────────────────────────┐
│ Alarms                         [+]    │
├──────────────────────────────────────┤
│ 6:30 AM  Weekdays         [on/off]    │
│ Morning run                           │
│                                      │
│ 8:00 AM  Tomorrow         [on/off]    │
│ Dentist                               │
└──────────────────────────────────────┘
```

### Row behavior

- main time
- repeat summary
- optional label
- enable/disable switch
- tap row to edit
- overflow or swipe to delete

### Create / edit alarm

```text
┌──────────────────────────────────────┐
│ Edit alarm                            │
├──────────────────────────────────────┤
│ Time: 06:30 AM                        │
│ Label: Morning run                    │
│ Repeat: Weekdays                      │
│ Alarm sound: System default           │
│ Preview: Next rings Mon 6:30 AM       │
│                         [Delete] [Save]│
└──────────────────────────────────────┘
```

### Pre-alarm notification

```text
┌ Notification ────────────────────────┐
│ Alarm in 30 minutes: Morning run      │
│ [Skip today]                          │
└──────────────────────────────────────┘
```

### Ringing alarm UI

```text
┌──────────────────────────────────────┐
│ 6:30                                 │
│ Morning run                           │
│                                      │
│ [Dismiss]        [Snooze 10 min]      │
│                                      │
│ optional voice affordance / status    │
└──────────────────────────────────────┘
```

---

## 9.3 Timers tab

```text
┌──────────────────────────────────────┐
│ Timers                         [+]    │
├──────────────────────────────────────┤
│ Pasta                08:12 remaining  │
│ Tea                  02:04 remaining  │
│                                      │
│ Recent / Completed                    │
│ Eggs                 completed        │
└──────────────────────────────────────┘
```

### Timer create / edit

```text
┌──────────────────────────────────────┐
│ New timer                             │
├──────────────────────────────────────┤
│ Duration: 10:00                       │
│ Label: Pasta                          │
│                       [Cancel] [Start]│
└──────────────────────────────────────┘
```

### Running timer notification

Each active timer gets an ongoing countdown notification.

```text
┌ Notification ────────────────────────┐
│ Pasta timer                           │
│ 08:12 remaining                       │
│ [Stop] [Open]                         │
└──────────────────────────────────────┘
```

### Ringing timer behavior

- notification-first by default
- full-screen only when locked

```text
┌ Notification / Locked full screen ───┐
│ Pasta timer finished                  │
│ [Stop] [Add 1 minute]                 │
└──────────────────────────────────────┘
```

---

## 9.4 World Clock tab

This is the natural UI home for `#677`.

```text
┌──────────────────────────────────────┐
│ World Clock                    [+]    │
├──────────────────────────────────────┤
│ Auckland         10:15 AM             │
│ London           11:15 PM (-1 day)    │
│ Tokyo             7:15 AM             │
└──────────────────────────────────────┘
```

MVP behavior:

- add city/timezone
- reorder favorites
- delete favorites
- show day offset and timezone-aware local time

---

## 9.5 Stopwatch tab

```text
┌──────────────────────────────────────┐
│ Stopwatch                             │
├──────────────────────────────────────┤
│ 00:12:48.31                           │
│ [Lap] [Pause] [Reset]                 │
│                                      │
│ Laps                                  │
│ 1  00:03:11.22                        │
│ 2  00:06:42.18                        │
└──────────────────────────────────────┘
```

MVP behavior:

- start
- pause
- reset
- laps
- ongoing notification while running

---

## 10. Command and assistant integration

Quick Actions, QIR, chat, and future voice should all call the same backend.

### Required principle

These are **entry points**, not separate systems:

- Clock screen UI
- Quick Actions
- chat tool execution
- notification actions
- future voice stop/snooze commands

### Product consequence

If a user says:

- `set an alarm for 6:30 every weekday`
- `set a timer for 10 minutes called pasta`
- `what timers do I have running`

The resulting alarm/timer must appear immediately inside the Clock UI and use the same scheduler/alert behavior as if created manually.

---

## 11. Permissions and platform handling

The full implementation needs to explicitly own:

- exact alarm scheduling permission/capability handling
- notification permission flow
- boot restore permission (`RECEIVE_BOOT_COMPLETED`)
- battery optimization / reliability notes where relevant
- full-screen alert behavior policy on modern Android

This must be productized before OEM `AlarmClock.*` fallback removal for core alarm/timer behavior.

---

## 12. Migration and cleanup

### 12.1 Required cleanup

When the new alert domain is ready, delete the remaining lightweight fallback behavior as part of the next wave:

- notification-only trigger handling in `AlarmBroadcastReceiver`
- duplicated alert UX responsibilities that should move into a dedicated alert service
- any remaining gaps between alarms and timers for ringing/full-screen/countdown UX
UI waves should build on the fully internal backend rather than extending the current lightweight trigger path.

### 12.2 Migration note

Existing OEM-clock alarms cannot always be reconciled silently.

Acceptable migration rule:

- new alarms/timers become fully internal
- old external alarms may require one-time user acknowledgement if they need to be recreated internally

---

## 13. Proposed issue split and implementation order

This issue should stay the parent architecture/product issue.

### Wave 1 — hard cutover to the internal clock backend

#### `#527` parent outcome

Use `#527` to track the full cutover and close it only when the hybrid model is gone.

#### Proposed child issue A — clock domain and scheduler foundation

Scope:

- introduce ClockRepository / ClockScheduler
- create domain-specific Alarm/Timer models
- model pre-alarm notifications as first-class scheduled events
- migrate callers off duplicated scheduling helpers
- own exact-alarm, notification, and full-screen platform gating before cutover
- keep boot restore working

Acceptance:

- one scheduling seam
- one source of truth
- no direct scheduling in view models or skill handlers
- exact-alarm, notification, boot-restore, and full-screen platform gating are explicitly handled before cutover
- battery-optimization / reliability constraints are documented before cutover
- boot restore for future alarms, active timers, and pre-alarm reminder events is verified before cutover
- alarms and timers that elapsed while the device was unavailable are reconciled correctly during boot restore before cutover

#### Proposed child issue B — internal alert parity and hard cutover

Scope:

- introduce ClockAlertService plus trigger and action receivers
- define one owner for ringing-state transitions and full-screen launch: `ClockAlertService`
- alarm-volume playback for alarms and timers
- silent/vibrate behavior fixed for app-owned alerts
- ringing notifications and full-screen alarm/timer-completion UX
- per-timer ongoing countdown notifications for active timers
- replace core `AlarmClock.*` alarm create/cancel paths with the internal backend

Acceptance:

- one alert seam
- alarms and timers ring like a real clock app
- no short-buzz-only behavior on app-owned silent/vibrate alerts
- alarms have a safe non-full-screen fallback path when platform full-screen launch is unavailable
- no `AlarmClock.ACTION_SET_ALARM` / `ACTION_DISMISS_ALARM` for core alarm/timer behavior

### Wave 2 — first-class alarms UI

#### Proposed child issue C — Alarms tab and editor

Scope:

- Alarms tab
- create/edit/delete/toggle alarm UI
- repeat rules
- pre-alarm skip-today notification

Acceptance:

- users can manage alarms without QIR/chat
- pre-alarm `Skip today` is scheduled, cancelable, and reboot-restorable through the shared scheduler

### Wave 3 — first-class timers UI

#### Proposed child issue D — Timers tab and recent/completed timer management

Scope:

- Timers tab
- multi-timer support
- Recent / Completed timer section

Acceptance:

- users can manage timers without QIR/chat
- timer history is separated into active vs `Recent / Completed` sections without losing the ongoing notification behavior delivered in the cutover wave

### Wave 4 — world clock

#### Proposed child issue E — World Clock tab (`#677` fold-in)

Scope:

- favorites list
- add/remove/reorder cities
- timezone-aware display

Acceptance:

- `#677` is satisfied within the Clock surface instead of a detached one-off feature

### Wave 5 — stopwatch

#### Proposed child issue F — Stopwatch tab

Scope:

- stopwatch state
- laps
- ongoing stopwatch notification

### Wave 6 — voice while ringing

#### Proposed child issue G — alert-time voice actions

Scope:

- limited grammar while alarm/timer is ringing
- `stop`, `dismiss`, `snooze`, `add one minute`
- explicit interaction with active alert UI/service

Acceptance:

- the feature works without confusing normal voice-command routing

---

## 14. Recommended delivery order

1. **Clock domain + scheduler foundation**
2. **Internal alert parity + hard cutover (alarm volume, ringing UX, and `AlarmClock.*` removal)**
3. **Alarms tab + editor + skip-today**
4. **Timers tab + recent/completed timer management**
5. **World Clock tab (`#677`)**
6. **Stopwatch tab**
7. **Voice commands while ringing**

The main rule is:

- do not ship another partial hybrid

---

## 15. Definition of success

This Clock feature is complete when all of the following are true:

- alarms and timers work when the app is not visibly open
- alarms and timers survive reboot correctly
- alarms and timers both use system alarm volume
- alerts ring properly on silent/vibrate instead of only buzzing briefly
- timers show ongoing countdown notifications
- users can fully create/edit/manage alarms and timers from dedicated UI
- repeating alarms support skip-today from a pre-alarm notification
- world clock and stopwatch live in the same Clock surface
- Quick Actions/chat use the same underlying clock domain as manual UI
- the old hybrid `AlarmClock.*` dependence is removed for core alarm/timer behavior
