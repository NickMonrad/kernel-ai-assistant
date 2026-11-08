# Permission capability registry audit

Issue #1144 adds the foundation for contextual permission UX. This slice is intentionally read-only and registry-first: it defines the shared capability language future slices can use, but it does not change startup prompts, contextual sheets, App Permissions UI, native skill execution, or alarm/timer scheduling behaviour.

## Why capability-first

Jandal should explain access in terms of the thing the user is trying to do, not the screen or Android API that happens to need access. A single capability can involve several requirement types:

- runtime permissions granted through Android permission dialogs;
- special-access settings that Android exposes through Settings panels;
- Android roles such as the assistant role;
- platform readiness checks such as notification delivery, exact-alarm scheduling, full-screen alerts, boot restore, or foreground microphone service support.

Keeping these categories separate lets future UX ask at the moment of use, offer safe fallbacks, and keep Settings → App Permissions as a repair/status dashboard instead of the primary permission experience.

## Requirement categories

| Category | Examples | Notes |
|---|---|---|
| Runtime permission | `RECORD_AUDIO`, `ACCESS_COARSE_LOCATION`, `READ_CONTACTS`, `CALL_PHONE`, `READ_CALENDAR`, `POST_NOTIFICATIONS` | Can be requested with Android runtime permission APIs when the feature is used. |
| Special access | Notification policy access, write-settings access | Must be explained in-app, then opened via the appropriate Android Settings panel. These are not runtime permissions. |
| Role | Assistant role | Must use Android role/default-app setup and OEM fallbacks where needed. |
| Platform readiness | Notifications enabled, exact alarms, full-screen intent, boot restore, foreground microphone service | Read-only checks that describe whether Android will let Jandal deliver the capability reliably. |

## Capability audit table

| Capability | Runtime permission | Special access | Role | Platform capability | Safe fallback / degraded path |
|---|---|---|---|---|---|
| Voice input | `RECORD_AUDIO` | — | — | Foreground microphone service where relevant | Typed input. |
| Weather current location | `ACCESS_COARSE_LOCATION` | — | — | — | Ask for a named place, use saved profile location, or use saved home location. |
| Contact lookup | `READ_CONTACTS` | — | — | — | Manual phone number or email entry. |
| Hands-free calling | `CALL_PHONE` | — | — | — | Open the dialer for this call with `ACTION_DIAL`; this is the app-level “this time” path. |
| Calendar lookup | `READ_CALENDAR` | — | — | — | Manually saved birthdays and important dates. |
| Do Not Disturb control | — | Notification policy access | — | — | Explain that Android requires DND access and offer settings later. |
| Modify system settings | — | Write-settings access | — | — | Explain that Android requires special access for settings such as brightness. |
| Default assistant | — | — | Assistant role | — | Manual app launch or existing voice overlay paths where available. |
| Hey Jandal | `RECORD_AUDIO` | — | Assistant role | Foreground microphone service | Keep disabled until setup is complete, unless a future slice defines an explicit degraded mode. |
| Jandal alarms, timers, and reminders | `POST_NOTIFICATIONS` | — | — | Notifications enabled, exact alarm scheduling, full-screen intent readiness, boot restore | Create only with explicit degraded behaviour when a reliable alert path is limited. |

## First-run vs on-demand boundary

The desired direction from #1140 is to keep first-run prompts minimal and ask for feature-specific access when the user tries to use that feature. Future slices should avoid asking for these at startup:

- contacts;
- current location;
- calendar;
- phone calling;
- Do Not Disturb special access;
- write-settings special access;
- assistant role.

This PR does not change the current startup permission bundle or any existing runtime behaviour. It only documents the target boundary and adds the registry that future slices can consume.

## Internal Jandal alarms, timers, and reminders

Jandal alarms, timers, reminders, and due-date alerts are app-owned capabilities. The `set_alarm` and `set_timer` native paths schedule through Jandal's internal clock repository and scheduler. Permission UX must therefore describe readiness for Jandal's own alert delivery, not handoff to an external Clock app.

The registry models this capability around:

- notification permission and notification delivery readiness;
- exact alarm scheduling capability;
- full-screen alert presentation where needed;
- boot restore readiness for scheduled alerts after reboot;
- explicit degraded behaviour when Android restrictions prevent reliable alerts.

## Foundation-only scope

This audit and registry do not add contextual permission sheets, pending-action retry, App Permissions redesign, or structured skill permission results. Those belong to later #1140 slices. The registry is a stable source of truth for those future migrations.
