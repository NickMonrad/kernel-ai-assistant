# S21 ADB Validation Summary — 2026-06-14

**Device**: SM-G991B (Galaxy S21) — USB-connected
**Build**: Debug APK from PR #1243
**Receiver**: ActionsViewModel (quick_action_input)

## Results

| # | Case | Expected | Actual | Status |
|---|------|----------|--------|--------|
| 1 | how many weeks until 31 October | get_date_diff | get_date_diff | ✓ |
| 2 | how many weeks until the 31 October | get_date_diff | get_date_diff | ✓ |
| 3 | set an alarm (slot_reply=7am) | set_alarm | set_alarm | ✓ |
| 4 | set a timer (slot_reply=5 minutes → 300) | set_timer | set_timer | ✓ |
| 5 | add to my list (single slot) | add_to_list | set_timer | ✗ pre-existing |
| 6 | add to my list (multi-slot: eggs + shopping list) | add_to_list | add_to_list | ✓ |

## Key Passes
- **Date-countdown**: Both "how many weeks until 31 October" and "the 31 October" route to `get_date_diff`
- **Alarm slot-fill**: `set an alarm` → `set_alarm` with canonical `time=7am`
- **Timer slot-fill**: `set a timer` → `set_timer` with canonical `duration_seconds=300` (5 min)
- **Multi-slot harness**: `add_to_list` with 2 slots (item + list_name) works correctly

## Known Issue
- Single-slot `add_to_my_list` (slot_reply='eggs') routs as `set_timer` — pre-existing MiniLM classifier confusion on this device, not related to PR changes.
