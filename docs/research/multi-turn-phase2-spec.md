# Multi-Turn Dialog Management — Phase 2 Technical Specification
> **Issue:** [#522](https://github.com/NickMonrad/kernel-ai-assistant/issues/522)
> **Status:** Narrowed to the current deterministic quick-action slot-fill sprint
> **Last updated:** 2026-05-01

---

## 1. Purpose

This document replaces the earlier broad Phase 2 proposal with the smaller slice that is
actually being implemented in the current sprint.

Current implementation anchors:
- `core/skills/src/main/java/com/kernel/ai/core/skills/QuickIntentRouter.kt`
- `feature/chat/src/main/java/com/kernel/ai/feature/chat/ActionsViewModel.kt`

For this sprint:
- `QuickIntentRouter` owns the required-slot contracts and returns
  `RouteResult.NeedsSlot(intent, missingSlot)` when a required value is absent or blank.
- `ActionsViewModel` owns pending quick-action slot state locally via `_pendingSlot`; the
  Actions tab does not use a singleton `SlotFillerManager` or a new `DialogManager`.
- `ActionsViewModel.onSlotReply()` merges each reply into the pending params, asks
  `quickIntentRouter.nextMissingSlot(...)` for the next missing required slot, and only
  executes once the slot contract is complete.
- Blank slot replies cancel the pending action through `cancelSlotFill()`; they do not
  execute partial intents.

This document describes only that deterministic quick-action flow. It does not claim that
confirmation, disambiguation, anaphora, timeout counters, or error-recovery state machines
already exist for the Actions tab.

---

## 2. Current Flow

### 2.1 Entry from QuickIntentRouter

`ActionsViewModel.executeAction()` routes the normalized query through
`QuickIntentRouter.route()`.

- `RegexMatch` / `ClassifierMatch` with all required params present → execute immediately.
- `NeedsSlot` → store a `PendingSlotState` locally and show the missing-slot prompt in the
  Actions sheet.
- `FallThrough` → hand off to chat.

### 2.2 Slot continuation

`ActionsViewModel.onSlotReply()` now supports true multi-slot continuation for the reviewed
quick actions:

1. Read the current `PendingSlotState`.
2. Normalize the reply (voice replies still go through the existing slot-specific normalizer).
3. Merge the reply into the accumulated params for the current missing slot.
4. Call `quickIntentRouter.nextMissingSlot(intentName, mergedParams)`.
5. If another required slot is still missing, prime `_pendingSlot` again with the merged
   params and the next missing slot prompt.
6. If no required slot remains, clear `_pendingSlot` and execute the intent.

This is the whole contract for the sprint: after every reply, ask for the next missing
required slot until the contract is satisfied.

### 2.3 Example: `send_email`

```text
User:  "Send an email"
QIR:   NeedsSlot(intent=send_email, missingSlot=contact)

Assistant: "Who would you like to email?"
User:      "Alice"

Assistant: "What's the subject of your email to Alice?"
User:      "Meeting tomorrow"

Assistant: "What would you like the email to say?"
User:      "Please review the agenda before 9."

Result: execute `send_email` with
        { contact: "Alice", subject: "Meeting tomorrow", body: "Please review the agenda before 9." }
```

---

## 3. Sprint Scope

### 3.1 Included intents

The deterministic slot-fill contract is locked for these intents in this sprint:

| Intent | Required slots | Current prompt source |
|--------|----------------|-----------------------|
| `set_alarm` | `time` | inline `SlotSpec` in `QuickIntentRouter` |
| `set_timer` | `duration_seconds` | inline `SlotSpec` in `QuickIntentRouter` |
| `open_app` | `app_name` | inline `SlotSpec` in `QuickIntentRouter` |
| `navigate_to` | `destination` | inline `SlotSpec` in `QuickIntentRouter` |
| `find_nearby` | `query` | inline `SlotSpec` in `QuickIntentRouter` |
| `send_sms` | `contact`, `message` | `slotContracts["send_sms"]` |
| `send_email` | `contact`, `subject`, `body` | `slotContracts["send_email"]` |
| `add_to_list` | `item`, `list_name` | `slotContracts["add_to_list"]` |
| `make_call` | `contact` | `slotContracts["make_call"]` |
| `save_memory` | `content` | `slotContracts["save_memory"]` |
| `create_list` | `list_name` | `slotContracts["create_list"]` |

### 3.2 Locked reviewed requirements

These are the reviewed contracts that this sprint must preserve:

- `send_sms` requires `contact` and `message`.
- `send_email` requires `contact`, `subject`, and `body`.
- `add_to_list` requires `item` and `list_name`; there is no implicit default shopping list
  in the slot-fill contract.
- `make_call` requires `contact`.
- `save_memory` requires `content`.
- `create_list` requires `list_name`.

### 3.3 Explicit exclusions

The following are intentionally outside this sprint's deterministic slot-fill contract:

| Excluded area | Reason it stays out of scope now |
|---------------|----------------------------------|
| Weather / forecast | Existing weather behavior already has safe defaults, including current-location handling and optional forecast fields. Adding slot continuation here would add latency without closing a correctness gap for this sprint. |
| `create_calendar_event` | Not part of the reviewed quick-action slot contract for this sprint. |
| `remove_from_list` | Not part of the reviewed quick-action slot contract for this sprint. |
| Zero-slot toggles and media controls | These actions remain one-turn deterministic commands; there is no slot continuation requirement to document here. |

---

## 4. Boundaries and honesty checks

To keep the docs aligned with the repo as it exists today:

- Do not describe a shipped `DialogManager` for the Actions tab.
- Do not describe a singleton `SlotFillerManager` as the owner of Actions-tab slot state.
- Do not describe confirmation or disambiguation as already implemented for this quick-action
  loop unless the code actually grows those paths.
- Keep slot names aligned with current code (`contact`, `message`, `subject`, `body`,
  `item`, `list_name`, `content`, `time`, `duration_seconds`, `app_name`, `destination`,
  `query`).

---

## 5. Acceptance notes

Documentation is correct for this sprint when it communicates all of the following:

1. Pending quick-action slot state lives in `ActionsViewModel`.
2. `QuickIntentRouter` defines the required-slot contract per intent.
3. `onSlotReply()` continues prompting until every required slot is present.
4. `send_email` needs three slots: `contact`, `subject`, `body`.
5. `add_to_list` needs `item` plus `list_name`, with no default list fallback.
6. Weather/forecast, `create_calendar_event`, `remove_from_list`, and zero-slot
   toggles/media controls are explicitly excluded from this sprint.