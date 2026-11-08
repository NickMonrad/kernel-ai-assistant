# Permissions UX

> **Purpose:** This is the canonical behaviour spec for Android permission UX in Jandal AI. It covers permission timing, rationale copy, microphone and wake-word behaviour, revocation handling, repair paths, and evidence expectations.
>
> **Status:** First-cut subsystem behaviour spec for issue #1333.
>
> **Related review gate:** `.docs/agents/review-gates-permissions.md` defines minimum evidence for PRs that touch permission flows.

---

## Principles

1. **Ask only when the user intent needs the permission.** Do not show permission rationale copy globally just because a permission is missing.
2. **Keep the assistant useful without optional permissions.** Typing, Tools, and Actions should continue when voice or wake-word permissions are unavailable.
3. **Repair, do not crash.** Permission loss during resume, service start, or feature use must stop the affected feature and surface a repair path.
4. **Explain before OS prompts when the feature needs trust.** Wake-word listening needs a clear in-app rationale before the Android permission prompt.
5. **Use one primary action.** Permission repair UI should show a single next step such as `Allow microphone`, `Open settings`, or `Turn off Hey Jandal`.
6. **Re-check permissions at lifecycle boundaries.** Do not rely on cached permission state after resume, service restart, process recreation, or return from Android Settings.

---

## Permission surfaces

| Surface | Permission dependency | Behaviour when unavailable |
|---|---|---|
| Push-to-talk microphone button | Microphone while the user is actively speaking | Request or repair microphone access. Text input remains available. |
| Hey Jandal wake word | Microphone while wake-word listening is enabled | Disable or pause wake-word listening until permission is available. Do not crash. |
| Voice settings | Microphone for testing voice input and wake word | Show current state and the repair action. |
| App permissions screen | Permission status and repair actions | Show status, explanation, and Android Settings bridge where needed. |

---

## Microphone permission states

| State observed by app | User-facing meaning | Required behaviour |
|---|---|---|
| Granted | Voice features can run for the current lifecycle state. | Allow push-to-talk and wake-word listener startup if the feature is enabled. |
| Denied but requestable | The user has denied or not yet granted microphone access. | Request only from a voice action or wake-word enable flow. |
| Denied permanently or not requestable | Android will not show the runtime prompt from the app. | Show a repair path to Android Settings. |
| Previously granted but now revoked | The user changed permission in Android Settings, or a one-time grant expired. | Stop affected listeners, update UI state, and show repair where relevant. |

Android may not expose every user-facing permission label directly to the app. Jandal should therefore treat the runtime permission check as the source of truth and re-check it whenever voice or wake-word work starts or resumes.

---

## Push-to-talk behaviour

When the user taps the microphone button:

1. Check microphone permission immediately.
2. If permission is granted, start the active listening flow.
3. If permission is requestable, show a short rationale only in this flow, then request permission.
4. If permission is denied or not requestable, show the repair state and keep text input available.
5. If permission is revoked while listening, stop capture and show a non-crashing error state.

Recommended copy:

```text
Jandal needs microphone access to listen when you tap the mic. You can still type without it.
```

---

## Hey Jandal enable flow

Hey Jandal is different from push-to-talk because it implies ongoing wake-word listening. Permission copy should appear when the user enables Hey Jandal, not on every app launch.

When the user turns on Hey Jandal:

1. Show an in-app rationale before any Android permission prompt.
2. Explain that audio stays on the device.
3. Request microphone permission if it is not granted.
4. Enable wake-word listening only after the permission check succeeds.
5. If permission is denied, leave Hey Jandal off and show a repair action.
6. If permission is later revoked, stop listening and mark Hey Jandal as needing repair or disabled.

Recommended copy:

```text
Hey Jandal needs microphone access so it can listen for your wake word. Wake-word detection runs on this device.
```

The app should not repeatedly warn users about microphone permission from unrelated screens. The rationale belongs to the enable flow, voice surfaces, or repair UI.

---

## One-time and ask-every-time behaviour

Android one-time or ask-every-time microphone grants can appear as granted for the current app session and later become unavailable. Jandal should not try to infer durable access from past permission state.

Required behaviour:

- Re-check microphone permission on app resume.
- Re-check microphone permission before starting the wake-word listener.
- Re-check microphone permission before starting push-to-talk capture.
- If permission is no longer granted, stop the affected voice feature without crashing.
- Do not show global warning copy solely because a previous grant might expire.

If a future implementation can reliably detect non-durable microphone access, the warning should still appear only when enabling Hey Jandal or repairing Hey Jandal.

Recommended copy for a repair state:

```text
Microphone access is off, so Hey Jandal cannot listen for the wake word. You can turn it back on in Android Settings.
```

---

## Revocation from Android Settings

If the user changes microphone permission in Android Settings while Jandal is running or backgrounded:

1. On resume, re-read permission state.
2. If microphone access is missing, stop active capture or wake-word services.
3. Update the Hey Jandal setting state so the UI does not claim the listener is active.
4. Show a repair state only in relevant voice or permission surfaces.
5. Never crash because a listener, recorder, recogniser, or service assumed permission still existed.

The app should treat permission revocation as a normal lifecycle event.

---

## App permissions screen

The App permissions screen should act as a repair dashboard, not a nag screen.

For each permission-backed feature, show:

- feature name;
- current state;
- short explanation of what is unavailable;
- one primary repair action;
- secondary explanation only when useful.

Recommended microphone row examples:

```text
Microphone
Ready
Used for push-to-talk and Hey Jandal.
```

```text
Microphone
Action required
Turn on microphone access to use voice input and Hey Jandal.
[Open settings]
```

---

## Android Settings bridge

When Android will not show a runtime prompt, route to the app's Android Settings page.

Required behaviour:

1. Open the correct Android app details or permission settings screen.
2. On return, re-check the permission state.
3. If repaired, re-enable only the feature the user explicitly enabled.
4. If still missing, keep the app usable and show the repair state.

Do not automatically enable Hey Jandal after returning from Settings unless the user had explicitly enabled it before and the permission is now granted.

---

## Crash-free degradation requirements

Permission code must avoid these regressions:

- starting microphone capture without checking permission;
- starting or resuming wake-word listening after permission was revoked;
- assuming a previously granted permission is still granted;
- crashing on `SecurityException` from recorder, wake-word, STT, or service code;
- showing permission rationale copy on unrelated screens;
- enabling Hey Jandal while the permission check fails;
- leaving UI state claiming Hey Jandal is active when the listener was stopped.

All microphone and wake-word code paths should handle permission failure as a recoverable state.

---

## Evidence expectations

For PRs that touch permission behaviour, use `.docs/agents/review-gates-permissions.md` and include evidence proportional to risk.

Minimum evidence for microphone or wake-word permission changes:

- first enable flow screenshot or recording;
- denied flow result;
- revoked-from-Android-Settings result;
- repair path result after returning from Settings;
- confirmation that text input still works without microphone access;
- confirmation that Hey Jandal does not crash when permission is revoked;
- S21 on-device evidence by default;
- S23U evidence only when the issue is device-sensitive or S21 evidence is ambiguous.

Manual on-device testing is required for runtime permission flows because Android system permission dialogs are not fully covered by unit tests or CI.

---

## Related docs

- `docs/SPEC_INDEX.md` — spec authority and update rules.
- `docs/UX_PATTERNS.md` — app-wide UX patterns.
- `.docs/agents/review-gates-permissions.md` — review gate and evidence checklist.
- `.docs/agents/risk-based-evidence-policy.md` — risk tiers and device evidence policy.
