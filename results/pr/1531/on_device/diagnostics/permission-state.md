# Location permission state and precondition strength

## Retained state evidence

- `ui/sc1_runtime_prompt.xml` and `screenshots/sc1_runtime_prompt.png` show Android's Location runtime prompt after the local-weather request.
- `ui/sc4_runtime1.xml` / `ui/sc4_runtime2.xml` and the corresponding screenshots show the runtime denial sequence.
- `ui/sc4_location_settings.xml`, `screenshots/sc4_location_settings.png`, and `screenshots/sc4_location_granted.png` show Android's Location choices and the manual grant step.
- The weather log excerpt records current-location calls being blocked with `ACCESS_COARSE_LOCATION denied` before the grant flows.

## Non-file state observation

Before the validation scenarios, the agent reset Location with these commands on the S23 Ultra:

```text
pm revoke com.kernel.ai.debug android.permission.ACCESS_COARSE_LOCATION
pm revoke com.kernel.ai.debug android.permission.ACCESS_FINE_LOCATION
pm clear-permission-flags com.kernel.ai.debug android.permission.ACCESS_COARSE_LOCATION user-set user-fixed
pm clear-permission-flags com.kernel.ai.debug android.permission.ACCESS_FINE_LOCATION user-set user-fixed
```

The agent contemporaneously checked `dumpsys package` and observed `ACCESS_COARSE_LOCATION: granted=false`; the current coarse-location app-op was observed as `ignore`. The same denied state was restored after the Tokyo regression. Those raw command outputs were not saved to disk, so this is a non-file observation rather than a directly retained state artefact.

## Per-scenario classification

| Scenario | Disabled at start | Basis |
| --- | --- | --- |
| Typed missing → grant → retry | supported | contemporaneous reset/check; blocked log and runtime prompt retained |
| Voice → Not now | supported | contemporaneous reset/check; direct voice transcript unavailable |
| Voice → named location | supported | contemporaneous reset/check; typed fallback evidence retained |
| Denial → settings repair → retry | supported | contemporaneous reset/check; denial/repair/settings artefacts retained |
| Tokyo regression | supported | contemporaneous reset/check immediately before regression; named-location result retained |

No retained file establishes the starting state independently for every scenario. Do not upgrade these classifications to `proven` from the screenshots alone.
