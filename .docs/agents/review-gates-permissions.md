# Review Gate: Permissions

## When This Gate Applies

This gate applies when the PR touches:
- New Android permission requirements
- Permission request flows (runtime permission prompts)
- Permission repair paths (detecting missing permission mid-flow)
- Permission revocation handling (graceful degradation)
- Android Settings intent bridges (deep-links to OS permission page)
- Permission-related UI (rationale dialogs, permission settings screens)

## Smallest Useful Evidence Slice

| Change Type | Minimum Evidence |
|-------------|-----------------|
| New permission request | Full flow: first-run → denied → revoked → repair |
| Permission UI change | Screenshots of all permission states + repair path |
| Settings bridge change | Test deep-link to OS settings and return |
| Graceful degradation change | Test behaviour with each permission denied/revoked |

## When Manual On-Device Testing Is Required

- **Required** for: all runtime permission flows — automated tests cannot
  simulate the system permission dialog interaction accurately
- **Not required** for: permission-related logic that is fully unit-testable
  (state management, UI rendering of grant/deny states)

## Device Requirements

| Device | When |
|--------|------|
| S21 (Exynos) | Default — permission behaviour is consistent across Samsung devices |
| S23U (SD 8 Gen 2) | Only when the permission behaviour differs by Android version
  or manufacturer skin |

## Common Regressions to Check

- First-run flow: permission requested at correct time, rationale before request
  (if applicable)
- Denied flow: user taps "Deny" — app continues gracefully, re-requests at
  appropriate time
- "Don't ask again" flow: user permanently denies — app routes to Settings
  or degrades gracefully
- Revoked flow: user revokes in OS Settings while app is running — app detects
  and offers repair
- Repair path: app detects missing permission mid-flow and initiates repair
  without crashing
- Android Settings intent: deep-link opens correct OS settings page
- Permission revocation → graceful degradation: app continues without crash,
  features degrade proportionally

## Suggested Commands

```bash
# Grant/revoke permissions via ADB
adb shell pm grant <package> <permission>
adb shell pm revoke <package> <permission>

# Open app Info screen
adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS \
  -d "package:<package>"
```
