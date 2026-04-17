# Multi-Turn Dialog Management — Phase 2 Technical Specification

> **Issue:** [#522](https://github.com/NickMonrad/kernel-ai-assistant/issues/522)
> **Supersedes:** Phase 1 spike (#493, PR #517) — single slot-fill loop for `send_sms`
> **Status:** Draft
> **Last updated:** 2026-04-17

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Dialog Flows](#3-dialog-flows)
4. [Data Model](#4-data-model)
5. [ChatViewModel Integration](#5-chatviewmodel-integration)
6. [UI Changes](#6-ui-changes)
7. [Scope & Non-Goals](#7-scope--non-goals)
8. [Test Cases](#8-test-cases)
9. [Migration from Phase 1](#9-migration-from-phase-1)
10. [Open Questions](#10-open-questions)

---

## 1. Executive Summary

Phase 1 proved the core slot-filling loop: `QuickIntentRouter` returns `NeedsSlot` →
`SlotFillerManager` holds the pending intent → user replies → intent executes. This worked
for the single-slot `send_sms` case but has fundamental limitations:

- **Single slot only** — can't chain two missing slots (e.g. SMS missing both recipient and body)
- **No disambiguation** — if "Bob" matches two contacts, there's no way to ask which one
- **No confirmation** — destructive actions (send email, delete alarm) execute immediately
- **No error recovery** — skill failures are reported but offer no retry/fallback path
- **No anaphoric context** — "call him" or "do it again" can't resolve against recent intents

Phase 2 replaces `SlotFillerManager` with **`DialogManager`** — a general-purpose
multi-turn state machine that handles all five dialog flows through a single unified
processing pipeline. The existing `SlotFillerManager` API surface is preserved as a
thin shim during migration, then removed.

### Design Principles

1. **Single entry point** — `DialogManager.process(userMessage)` is the only call
   `ChatViewModel` needs to make. It returns a `DialogDecision` (execute / ask / cancel).
2. **State, not strategy** — all flows share the same state machine; the *type* of the
   pending action determines which transitions are valid, not a separate class hierarchy.
3. **Timeout by turns, not wall-clock** — if the user sends 3 unrelated messages without
   answering the clarifying question, the pending dialog is silently abandoned.
4. **Chip-first UX** — whenever the system asks a question, it provides tappable chips so
   the user rarely needs to free-type an answer.
5. **QIR bypass preserved** — `QuickIntentRouter` is not modified. `DialogManager` sits
   *between* QIR and execution, intercepting `RouteResult.NeedsSlot` and the new
   `NeedsDisambiguation` / `NeedsConfirmation` results.

---

## 2. Architecture Overview

### 2.1 Call Graph (Phase 2)

```
User message
     │
     ▼
ChatViewModel.sendMessage(text)
     │
     ├─── DialogManager.process(text) ◄── NEW: single entry point
     │         │
     │         ├─ Has pending dialog?
     │         │    YES → route reply to pending flow
     │         │           → DialogDecision.Execute / .AskFollowUp / .Cancel
     │         │    NO  → pass through (no dialog active)
     │         │           → DialogDecision.PassThrough
     │         │
     │         └─ (anaphoric check on PassThrough — "call him", "do it again")
     │              → DialogDecision.Execute (resolved) or PassThrough (no match)
     │
     ├─── [if PassThrough] QuickIntentRouter.route(text)
     │         │
     │         ├─ RegexMatch / ClassifierMatch
     │         │    └─ DialogManager.evaluate(intent, skillResult?)
     │         │         ├─ needs confirmation? → AskConfirmation
     │         │         ├─ needs disambiguation? → AskDisambiguation
     │         │         └─ ready → Execute
     │         │
     │         ├─ NeedsSlot
     │         │    └─ DialogManager.startSlotFill(intent, missingSlot)
     │         │         → AskFollowUp (slot question + chips)
     │         │
     │         └─ FallThrough → Gemma-4 E4B inference
     │              └─ (post-execution) DialogManager.evaluate(intent, skillResult?)
     │                   ├─ SkillResult.Failure → AskErrorRecovery (retry/fallback chips)
     │                   └─ success → record in context ring for anaphora
     │
     └─── Execute intent / show response
```

### 2.2 State Machine

```
                    ┌──────────────────────────────────────────────────┐
                    │                                                  │
                    ▼                                                  │
              ┌──────────┐     NeedsSlot      ┌────────────────┐      │
              │          │ ──────────────────► │                │      │
              │   IDLE   │                     │  SLOT_FILLING  │──┐   │
              │          │ ◄────────────────── │                │  │   │
              └──────────┘   filled / cancel   └────────────────┘  │   │
                │  ▲  ▲                          │ still missing   │   │
                │  │  │                          └─────────────────┘   │
                │  │  │                                                │
                │  │  │        multi-match     ┌────────────────────┐  │
                │  │  │  ────────────────────► │                    │  │
                │  │  └─────────────────────── │  DISAMBIGUATING    │  │
                │  │         selected/cancel   │                    │  │
                │  │                           └────────────────────┘  │
                │  │                                                   │
                │  │         destructive       ┌────────────────────┐  │
                │  │  ────────────────────────►│                    │  │
                │  └────────────────────────── │    CONFIRMING      │──┘
                │        confirmed / cancel    │                    │  timeout
                │                              └────────────────────┘
                │
                │         skill failure        ┌────────────────────┐
                │  ────────────────────────────►│                    │
                └──────────────────────────────│  ERROR_RECOVERY    │
                         retry / abandon       │                    │
                                               └────────────────────┘
```

**Transitions:**

| From | Event | To | Side effect |
|------|-------|----|-------------|
| `IDLE` | QIR → `NeedsSlot` | `SLOT_FILLING` | Show prompt + chips |
| `IDLE` | skill result has multiple matches | `DISAMBIGUATING` | Show option chips |
| `IDLE` | intent in confirmation list | `CONFIRMING` | Show confirm/cancel chips |
| `IDLE` | skill execution failed | `ERROR_RECOVERY` | Show retry/fallback chips |
| `SLOT_FILLING` | user fills slot, more missing | `SLOT_FILLING` | Ask next slot |
| `SLOT_FILLING` | user fills last slot | `IDLE` or `CONFIRMING` | Execute or confirm |
| `SLOT_FILLING` | blank / "cancel" / timeout | `IDLE` | Abandon |
| `DISAMBIGUATING` | user selects option | `IDLE` or `CONFIRMING` | Execute or confirm |
| `DISAMBIGUATING` | "cancel" / timeout | `IDLE` | Abandon |
| `CONFIRMING` | "yes" / confirm chip | `IDLE` | Execute |
| `CONFIRMING` | "no" / cancel chip / timeout | `IDLE` | Abandon |
| `ERROR_RECOVERY` | "retry" chip | `IDLE` | Re-execute |
| `ERROR_RECOVERY` | "cancel" / timeout | `IDLE` | Abandon |

**Timeout rule:** 3 consecutive user messages that don't match the expected reply pattern
cause automatic transition to `IDLE` with a silent abandon (no "cancelled" message — the
user has clearly moved on).

### 2.3 New Files

| File | Module | Purpose |
|------|--------|---------|
| `DialogManager.kt` | `core:skills` | State machine, replaces `SlotFillerManager` |
| `DialogState.kt` | `core:skills` | Sealed class for all states |
| `DialogDecision.kt` | `core:skills` | Sealed class returned by `process()` |
| `PendingAction.kt` | `core:skills` | Frozen intent + accumulated context |
| `ConfirmationPolicy.kt` | `core:skills` | Per-intent config: requires confirmation? |
| `ContextRing.kt` | `core:skills` | Fixed-size ring buffer of recent executed intents (for anaphora) |
| `DialogManagerTest.kt` | `core:skills` (test) | Unit tests for all state transitions |

### 2.4 Modified Files

| File | Change |
|------|--------|
| `ChatViewModel.kt` | Replace `SlotFillerManager` with `DialogManager`; add `evaluate()` calls after QIR match and after E4B skill execution |
| `QuickIntentRouter.kt` | No changes — `NeedsSlot` already works; disambiguation is detected *after* execution |
| `SlotFillerManager.kt` | Deprecated; thin delegation shim to `DialogManager` during migration, then removed |
| `ChatScreen.kt` / composables | New chip types (confirmation, error recovery); input hint changes |
| `SkillResult.kt` | Add `Ambiguous(matches: List<AmbiguousMatch>)` variant |

---

## 3. Dialog Flows

### 3.1 Slot Filling (hardened)

**Trigger:** `QuickIntentRouter.route()` returns `RouteResult.NeedsSlot`, *or* the LLM
calls a `@Tool` method with missing required parameters detected post-parse.

**Phase 1 limitation:** Only one slot could be missing. If `send_sms` was missing both
`recipient` and `body`, only the first was caught.

**Phase 2 design:** `DialogManager` iterates through *all* missing required slots,
asking for each in sequence. After the last slot is filled, the intent either executes
immediately or transitions to `CONFIRMING` if the intent requires confirmation.

#### Example: SMS missing both recipient and body

```
User:  "Send a text message"
QIR:   NeedsSlot(intent=send_sms, missingSlot=recipient)

State: IDLE → SLOT_FILLING
       PendingAction { intent=send_sms, filled={}, remaining=[recipient, body] }

Jandal: "Who would you like to text?"
        chips: [recent contacts from call log — "Alice", "Bob", "Mum"]

User:  "Bob" (or taps "Bob" chip)

State: SLOT_FILLING → SLOT_FILLING
       PendingAction { intent=send_sms, filled={recipient: "Bob"}, remaining=[body] }

Jandal: "What would you like to say to Bob?"
        chips: (none — free-text expected)

User:  "I'm running 10 minutes late"

State: SLOT_FILLING → CONFIRMING (send_sms requires confirmation)
       PendingAction { filled={recipient: "Bob", body: "I'm running 10 minutes late"} }

Jandal: "Send 'I'm running 10 minutes late' to Bob?"
        chips: ["Send ✓", "Cancel ✗"]
```

#### Guardrails

- **Max 3 slot prompts per intent** — if the 4th slot is still missing, abandon with
  "I couldn't complete that — could you try rephrasing?"
- **Over-filling:** user reply during slot fill bypasses QIR entirely. The full text
  is taken as the slot value. "Tell him I'm running late and bring pizza" → body =
  full string, no re-routing.
- **Cancel keywords:** "never mind", "cancel", "forget it", "stop" → immediate abandon.

### 3.2 Disambiguation

**Trigger:** A skill's `execute()` returns `SkillResult.Ambiguous` — meaning the
provided slot value matched multiple records and the skill can't determine which one.

This is a *post-execution* dialog. The skill runs, discovers ambiguity, and returns
the matches. `DialogManager` freezes the original intent and asks the user to choose.

#### Example: Two contacts named "John"

```
User:  "Call John"
QIR:   RegexMatch(intent=make_call, params={recipient: "John"})
Skill: SkillResult.Ambiguous(matches=[
         {label: "John Smith (Mobile)", value: "+6421555001"},
         {label: "John Davies (Work)", value: "+6421555002"},
       ])

State: IDLE → DISAMBIGUATING
       PendingAction { intent=make_call, slot=recipient, matches=[...] }

Jandal: "I found two contacts named John. Which one?"
        chips: ["John Smith (Mobile)", "John Davies (Work)", "Cancel"]

User:  taps "John Smith (Mobile)"

State: DISAMBIGUATING → IDLE
       Execute make_call with recipient="+6421555001"
```

#### Design Notes

- Chips are generated from `AmbiguousMatch.label`. Max 5 options shown; if >5 matches,
  show top 5 + a "Type to narrow down…" hint.
- Disambiguation results are *not* cached. If the user cancels and re-asks, the skill
  re-executes and may return different results (contacts could change).
- If the skill returns exactly 0 matches → that's a `SkillResult.Failure`, not ambiguity.

### 3.3 Confirmation

**Trigger:** The resolved intent is in the **confirmation-required** list *and* all
slots are filled. Confirmation is the *last* gate before execution.

#### Confirmation Policy

```kotlin
object ConfirmationPolicy {
    private val REQUIRES_CONFIRMATION = setOf(
        "send_sms",
        "send_email",
        "make_call",
        // Future: "delete_alarm", "delete_calendar_event"
    )

    fun requiresConfirmation(intentName: String): Boolean =
        intentName in REQUIRES_CONFIRMATION
}
```

**Design rationale:** Alarms, timers, and calendar events are *not* destructive — the
user can see them in the system clock/calendar and dismiss them. SMS, email, and calls
are irreversible once dispatched.

#### Example: Send email

```
User:  "Email Alice about the meeting tomorrow"
E4B:   runIntent(intent_name=send_email, recipient="Alice", subject="Meeting tomorrow")

State: IDLE → CONFIRMING
       PendingAction { intent=send_email, params={recipient: "Alice", subject: "Meeting tomorrow"} }

Jandal: "Send email to Alice with subject 'Meeting tomorrow'?"
        chips: ["Send ✓", "Cancel ✗"]

User:  "Yes" (or taps "Send ✓")

State: CONFIRMING → IDLE
       Execute send_email
```

#### Confirm/Cancel Detection

Positive: "yes", "yep", "yeah", "sure", "do it", "send it", "confirm", "go ahead",
"ok", chip tap on confirm chip.

Negative: "no", "nope", "cancel", "don't", "stop", chip tap on cancel chip.

Ambiguous reply (doesn't match positive or negative): re-prompt once —
"Sorry, should I send it? Yes or no?"
If still ambiguous after re-prompt → cancel.

### 3.4 Error Recovery

**Trigger:** `Skill.execute()` returns `SkillResult.Failure` *and* the failure is
potentially recoverable (network timeout, permission denied, app not found).

Not all failures are recoverable. The skill categorizes failures:

```kotlin
sealed class SkillResult {
    data class Failure(
        val error: String,
        val recoverable: Boolean = false,  // NEW field
        val retryable: Boolean = false,    // NEW field
        val fallbackSuggestion: String? = null, // e.g. "Try composing manually"
    ) : SkillResult()
}
```

#### Example: SMS send failure

```
User:  "Text Bob I'm on my way"
Exec:  send_sms(recipient="Bob", body="I'm on my way")
       → SkillResult.Failure(error="No SMS app found", recoverable=true,
           retryable=true, fallbackSuggestion="Open Messages app manually")

State: IDLE → ERROR_RECOVERY
       PendingAction { intent=send_sms, params={...}, error="No SMS app found" }

Jandal: "I couldn't send that text — no SMS app was found."
        chips: ["Try Again", "Open Messages App", "Cancel"]

User:  taps "Try Again"

State: ERROR_RECOVERY → IDLE
       Re-execute send_sms with same params
```

#### Non-Recoverable Failures

If `recoverable == false`, `DialogManager` does *not* enter `ERROR_RECOVERY`. The
failure message is shown directly (current behavior), and the intent is recorded in the
context ring as a failed attempt (for potential "try that again" anaphora).

### 3.5 Anaphoric Resolution

**Trigger:** User sends a message containing a pronoun or short reference that maps to a
recently executed intent. `DialogManager` checks this *before* QIR routing, since
anaphoric references wouldn't match any regex pattern.

#### Context Ring

`ContextRing` is a fixed-size (capacity = 5) ring buffer storing the last N successfully
executed intents with their full parameters, timestamps, and results.

```kotlin
data class ContextEntry(
    val intentName: String,
    val params: Map<String, String>,
    val result: SkillResult,       // what happened
    val timestamp: Long,           // System.currentTimeMillis()
    val userUtterance: String,     // original user message
)
```

#### Resolution Strategy

Anaphoric resolution uses a keyword-to-slot mapping, not LLM inference (keeping it
zero-latency like QIR). Patterns checked:

| User says | Pattern | Resolves to |
|-----------|---------|-------------|
| "call him" / "call her" / "call them" | `call\b.*\b(him\|her\|them)` | Most recent intent with a `recipient` param → `make_call(recipient=<that>)` |
| "text him" / "message her" | `(text\|message)\b.*\b(him\|her\|them)` | Most recent `recipient` → `send_sms(recipient=<that>)` |
| "do it again" / "do that again" | `do (it\|that) again` | Re-execute most recent intent with same params |
| "cancel that" / "undo that" | `cancel\|undo` + `that\|it` | Not executed — returns PassThrough (cancel requires knowing *what* to cancel; out of scope for Phase 2) |
| "try that again" / "retry" | `(try|retry).*again` | Re-execute most recent *failed* intent |

#### Example: "Call him back"

```
--- Context ring contains: ---
  [0] send_sms(recipient="Bob", body="I'm late") — 2 min ago
  [1] make_call(recipient="Alice") — 5 min ago

User:  "Call him back"
DialogManager.process():
  1. No pending dialog
  2. Anaphoric check: matches "call ... him" pattern
  3. Scan context ring for most recent entry with a `recipient` param
  4. Found: send_sms to "Bob" (2 min ago)
  5. Generate: make_call(recipient="Bob")

State: IDLE → (may go to CONFIRMING if make_call requires confirmation)

Jandal: "Calling Bob…"
```

#### Limitations

- Pronoun gender is not actually resolved — "him", "her", "them" all map to the most
  recent `recipient`. This is correct >95% of the time in a single-user assistant.
- Context ring is in-memory only. Process death clears it.
- If no context entry matches the anaphoric pattern → `DialogDecision.PassThrough`,
  and the message falls through to QIR/E4B as normal.

---

## 4. Data Model

### 4.1 `DialogState` — Sealed Class

```kotlin
package com.kernel.ai.core.skills.dialog

sealed class DialogState {

    /** No dialog in progress. Default state. */
    data object Idle : DialogState()

    /**
     * Waiting for the user to fill one or more missing slots.
     * [remainingSlots] is ordered — first entry is the one being asked about now.
     */
    data class SlotFilling(
        val pendingAction: PendingAction,
        val remainingSlots: List<SlotSpec>,
        val promptsAsked: Int = 0,      // increments each prompt; abandon at MAX_PROMPTS
    ) : DialogState()

    /**
     * Waiting for the user to pick from [options].
     */
    data class Disambiguating(
        val pendingAction: PendingAction,
        val slotToFill: String,         // which param the selection fills
        val options: List<AmbiguousMatch>,
    ) : DialogState()

    /**
     * Waiting for the user to confirm a destructive action.
     */
    data class Confirming(
        val pendingAction: PendingAction,
        val confirmPrompt: String,       // human-readable summary of what will happen
        val reprompted: Boolean = false,  // true if we already re-prompted once
    ) : DialogState()

    /**
     * A skill failed with a recoverable error; offering retry/fallback.
     */
    data class ErrorRecovery(
        val pendingAction: PendingAction,
        val error: String,
        val retryable: Boolean,
        val fallbackSuggestion: String?,
    ) : DialogState()
}
```

### 4.2 `PendingAction`

```kotlin
data class PendingAction(
    val intentName: String,
    val filledSlots: Map<String, String>,
    val source: ActionSource,            // QIR or E4B — affects execution path
    val createdAt: Long = System.currentTimeMillis(),
    val missedTurns: Int = 0,            // incremented on unrelated messages; abandon at 3
)

enum class ActionSource { QIR, E4B_TOOL_CALL }
```

### 4.3 `DialogDecision`

Returned by `DialogManager.process()` to tell `ChatViewModel` what to do:

```kotlin
sealed class DialogDecision {
    /** No active dialog — proceed with normal QIR → E4B pipeline. */
    data object PassThrough : DialogDecision()

    /** Dialog resolved — execute this intent now. */
    data class Execute(
        val intentName: String,
        val params: Map<String, String>,
        val source: ActionSource,
    ) : DialogDecision()

    /** Dialog needs more input — show this question + chips. */
    data class AskFollowUp(
        val message: String,
        val chips: List<ChipOption>,
        val inputHint: String?,          // e.g. "Type your message…" for free-text slots
    ) : DialogDecision()

    /** Dialog was cancelled (by user or timeout). */
    data class Cancel(
        val message: String?,            // null = silent abandon (timeout)
    ) : DialogDecision()
}

data class ChipOption(
    val label: String,                   // display text
    val value: String,                   // machine value (phone number, option ID, etc.)
    val style: ChipStyle = ChipStyle.DEFAULT,
)

enum class ChipStyle { DEFAULT, PRIMARY, DESTRUCTIVE }
```

### 4.4 `AmbiguousMatch`

```kotlin
data class AmbiguousMatch(
    val label: String,          // "John Smith (Mobile)"
    val value: String,          // "+6421555001"
    val metadata: Map<String, String> = emptyMap(),  // optional extra display info
)
```

### 4.5 Persistence

| Data | Survives rotation? | Survives process death? | Mechanism |
|------|-------------------|------------------------|-----------|
| `DialogState` | ✅ | ❌ | `@Singleton` (same as Phase 1) |
| `ContextRing` | ✅ | ❌ | `@Singleton`, in-memory ring buffer |
| `ConfirmationPolicy` | N/A | N/A | Static config, no state |

**Rationale:** Process death during an active dialog is a rare edge case. The user
simply re-asks. Persisting dialog state across process death would require serializing
`PendingAction` to `SavedStateHandle` or Room, adding complexity with minimal UX benefit.
This matches the Phase 1 design decision documented in `SlotFillerManager`.

---

## 5. ChatViewModel Integration

### 5.1 Injection

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    // ... existing deps ...
    private val dialogManager: DialogManager,   // replaces slotFillerManager
) : ViewModel() {
```

### 5.2 sendMessage() — Updated Flow

The current `sendMessage()` in `ChatViewModel` has this structure:

```
1. Append user message to UI
2. [SlotFiller shortcut] if slotFillerManager.hasPending → onUserReply()
3. QuickIntentRouter.route(text) → match or fall through
4. If QIR match → execute skill immediately
5. If fall-through → E4B inference → tool calling → response
```

Phase 2 changes step 2 and adds a post-execution gate:

```kotlin
private fun sendMessage(text: String) = viewModelScope.launch {
    appendUserMessage(convId, text)

    // ──────────────────────────────────────────────────────
    // STEP 1: DialogManager intercept (replaces SlotFiller shortcut)
    // ──────────────────────────────────────────────────────
    val decision = dialogManager.process(text)
    when (decision) {
        is DialogDecision.Execute -> {
            executeIntent(decision.intentName, decision.params, decision.source)
            return@launch
        }
        is DialogDecision.AskFollowUp -> {
            showDialogPrompt(convId, decision.message, decision.chips, decision.inputHint)
            return@launch
        }
        is DialogDecision.Cancel -> {
            if (decision.message != null) appendAssistantMessage(convId, decision.message)
            return@launch
        }
        is DialogDecision.PassThrough -> { /* continue to QIR */ }
    }

    // ──────────────────────────────────────────────────────
    // STEP 2: QuickIntentRouter (unchanged)
    // ──────────────────────────────────────────────────────
    val routeResult = quickIntentRouter.route(text)
    when (routeResult) {
        is RouteResult.NeedsSlot -> {
            val followUp = dialogManager.startSlotFill(
                intentName = routeResult.intent.intentName,
                existingParams = routeResult.intent.params,
                missingSlots = routeResult.allMissingSlots(),  // Phase 2: list, not single
                source = ActionSource.QIR,
            )
            showDialogPrompt(convId, followUp.message, followUp.chips, followUp.inputHint)
            return@launch
        }
        is RouteResult.RegexMatch, is RouteResult.ClassifierMatch -> {
            val intent = (routeResult as? RouteResult.RegexMatch)?.intent
                ?: (routeResult as RouteResult.ClassifierMatch).intent

            // ── Post-match evaluation: does this need confirmation? ──
            val evalDecision = dialogManager.evaluate(
                intentName = intent.intentName,
                params = intent.params,
                source = ActionSource.QIR,
            )
            when (evalDecision) {
                is DialogDecision.AskFollowUp -> {
                    showDialogPrompt(convId, evalDecision.message, evalDecision.chips, evalDecision.inputHint)
                    return@launch
                }
                is DialogDecision.Execute -> {
                    executeIntent(evalDecision.intentName, evalDecision.params, evalDecision.source)
                    return@launch
                }
                else -> { /* Execute immediately (no confirmation needed) */ }
            }
            executeQirIntent(intent)
        }
        is RouteResult.FallThrough -> { /* continue to E4B */ }
    }

    // ──────────────────────────────────────────────────────
    // STEP 3: E4B Inference (unchanged, except post-execution)
    // ──────────────────────────────────────────────────────
    val generationResult = runInference(text, systemContext)

    // ── Post-execution evaluation: disambiguation / error recovery ──
    if (toolSet.wasToolCalled()) {
        val skillResult = toolSet.lastToolResult()
        val postDecision = dialogManager.evaluateResult(
            intentName = toolSet.lastToolName(),
            params = toolSet.lastToolParams(),
            result = skillResult,
            source = ActionSource.E4B_TOOL_CALL,
        )
        when (postDecision) {
            is DialogDecision.AskFollowUp -> {
                showDialogPrompt(convId, postDecision.message, postDecision.chips, postDecision.inputHint)
                return@launch
            }
            is DialogDecision.Execute -> {
                // Disambiguation resolved inline — shouldn't happen here
            }
            else -> { /* Normal flow — show E4B response */ }
        }
    }

    appendAssistantResponse(convId, generationResult)
}
```

### 5.3 Timeout Handling

`DialogManager.process()` checks `PendingAction.missedTurns` on every call. If the user's
message doesn't look like a reply to the pending dialog (doesn't match expected slot type,
isn't a chip value, isn't a yes/no for confirmation), `missedTurns` is incremented.

```kotlin
// Inside DialogManager.process()
if (state !is DialogState.Idle && !isRelevantReply(userMessage, state)) {
    val newMissedTurns = currentPendingAction.missedTurns + 1
    if (newMissedTurns >= MAX_MISSED_TURNS) {  // MAX_MISSED_TURNS = 3
        reset()
        return DialogDecision.PassThrough  // silent abandon
    }
    // Update missed turns count but still pass through
    updateMissedTurns(newMissedTurns)
    return DialogDecision.PassThrough
}
```

This means:
- Turn 1 after prompt: user says something unrelated → missedTurns=1, message processes normally
- Turn 2: still unrelated → missedTurns=2
- Turn 3: still unrelated → dialog silently abandoned, missedTurns reset

The user never sees a "dialog timed out" message. If they come back to the original
request, they simply start fresh.

### 5.4 Multi-Turn vs Single-Turn Fast Path

| Path | Latency | Mechanism |
|------|---------|-----------|
| QIR single-turn (e.g. "turn on torch") | <30ms | QIR match → immediate execute, no DialogManager involvement |
| QIR + confirmation (e.g. "text Bob hello") | <30ms + 1 turn | QIR match → `evaluate()` → `AskFollowUp` → user confirms → execute |
| QIR + slot fill (e.g. "send a text") | <30ms + N turns | QIR → `NeedsSlot` → N prompts → execute |
| E4B single-turn (e.g. "what's the weather") | ~2s | E4B inference → tool call → response |
| E4B + disambiguation (e.g. "email John about X") | ~2s + 1 turn | E4B → tool call → Ambiguous → chips → user picks → execute |

`DialogManager.process()` is always O(1) — no inference, no I/O, just state checks and
regex matching. It adds negligible latency to the single-turn fast path.

---

## 6. UI Changes

### 6.1 Chip Rendering

Phase 1 already renders "disambig chips" for slot filling. Phase 2 extends this with
distinct styles:

| Flow | Chip style | Example chips |
|------|-----------|---------------|
| Slot filling | `DEFAULT` | Recent contacts, common values |
| Disambiguation | `DEFAULT` | "John Smith (Mobile)", "John Davies (Work)" |
| Confirmation | `PRIMARY` + `DESTRUCTIVE` | "Send ✓" (primary), "Cancel ✗" (destructive) |
| Error recovery | `DEFAULT` + `DESTRUCTIVE` | "Try Again", "Open App", "Cancel" (destructive) |

Chip colors:
- `DEFAULT` — surface variant (current)
- `PRIMARY` — filled primary color (green-ish tint)
- `DESTRUCTIVE` — outlined, red text

### 6.2 Input Hint Text

The `TextField` placeholder dynamically changes based on dialog state:

| State | Input hint |
|-------|-----------|
| `Idle` | "Ask me anything…" (current) |
| `SlotFilling` (free-text slot) | "Type your answer…" |
| `SlotFilling` (chip-expected) | "Tap a suggestion or type…" |
| `Disambiguating` | "Tap to select…" |
| `Confirming` | "Yes or No…" |
| `ErrorRecovery` | "Tap an option…" |

`DialogManager` exposes a `StateFlow<DialogState>` that the UI observes.

### 6.3 Cancel / Dismiss

- **Explicit cancel:** User types "cancel", "never mind", "forget it", or "stop" →
  `DialogManager` returns `DialogDecision.Cancel` with message "Okay, cancelled."
- **Chip cancel:** Every chip set includes a "Cancel" chip (except pure slot filling
  where the user can just type something unrelated).
- **Back-button / swipe dismiss:** If the user navigates away from the chat screen
  during an active dialog, `DialogManager.reset()` is called in
  `ChatViewModel.onCleared()` or `onStop()`.

### 6.4 Dialog Active Indicator

When `DialogState != Idle`, a subtle colored bar or dot appears above the input field
indicating an active dialog. Tapping it shows a tooltip: "Waiting for your answer —
tap Cancel to dismiss." This provides discoverability for the cancel mechanism.

---

## 7. Scope & Non-Goals

### In Scope (Phase 2)

| Feature | Description |
|---------|-------------|
| Multi-slot filling | Chain multiple missing slots in sequence |
| Disambiguation | Present options when a slot matches multiple records |
| Confirmation | Gate destructive actions behind yes/no |
| Error recovery | Offer retry/fallback on recoverable failures |
| Anaphoric resolution | "call him", "do it again" via keyword + context ring |
| Timeout / abandon | Silent abandon after 3 unrelated turns |
| Cancel keywords | "cancel", "never mind", etc. from any dialog state |
| Over-fill protection | Slot-fill replies bypass QIR to prevent re-routing |

### Non-Goals (explicitly out of scope)

| Feature | Why deferred |
|---------|-------------|
| **Voice input** | Voice is a separate initiative; dialog flows are text-first for now |
| **Nested multi-turn** | "Set an alarm for… actually, what time is my meeting?" — handling digressions that *themselves* need slot filling creates combinatorial state explosion. Deferred to Phase 3. |
| **Multi-intent parsing** | "Set an alarm for 7am and text Bob good morning" — two intents in one utterance. Requires LLM-level decomposition, not state machine. |
| **Correction / slot update** | "Actually, send it to Alice instead" during slot fill. Requires NLU to detect *which* slot is being corrected. Deferred. |
| **Digression stack** | Pausing an in-progress dialog to handle an unrelated request, then resuming. Phase 2 simply abandons after 3 missed turns. |
| **Contact resolution** | The disambiguation flow assumes the *skill* returns `Ambiguous`. Building the actual contacts lookup with multi-match detection is a separate feature (#256). |
| **Persistent dialog state** | Surviving process death. Rare edge case, complexity not justified. |
| **Undo / rollback** | "Cancel that alarm" after it's been set — requires OS-level undo support per intent, which varies wildly. |
| **Custom confirmation messages** | Per-intent confirmation templates. Phase 2 uses a generic template; custom messages can be added later. |

---

## 8. Test Cases

All tests use the `adb_skill_test.py` harness with `--multiturn` flag, sending sequential
messages and validating dialog state + UI output at each step.

### Slot Filling

| # | Flow | Turn 1 (User) | Expected Response | Turn 2 (User) | Expected Behavior |
|---|------|--------------|-------------------|---------------|-------------------|
| T01 | Single missing slot | "Send a text to Bob" | "What would you like to say to Bob?" + no chips | "I'm running late" | State → CONFIRMING; shows confirm chips |
| T02 | Two missing slots | "Send a text message" | "Who would you like to text?" + contact chips | "Bob" → "What would you like to say to Bob?" → "Hello" | State → CONFIRMING after both filled |
| T03 | Slot fill cancel | "Send a text to Bob" | "What would you like to say to Bob?" | "Never mind" | State → IDLE; "Okay, cancelled." |
| T04 | Over-fill protection | "Send a text to Bob" | "What would you like to say?" | "Tell him to set a timer for 5 minutes" | body = full string; does NOT trigger set_timer |

### Disambiguation

| # | Flow | Turn 1 (User) | Expected Response | Turn 2 (User) | Expected Behavior |
|---|------|--------------|-------------------|---------------|-------------------|
| T05 | Two contacts | "Call John" | "I found 2 contacts named John…" + chips ["John Smith (Mobile)", "John Davies (Work)", "Cancel"] | taps "John Smith" | Executes make_call("+6421555001") |
| T06 | Disambig cancel | "Call John" | "I found 2 contacts…" + chips | "Cancel" | State → IDLE; "Okay, cancelled." |

### Confirmation

| # | Flow | Turn 1 (User) | Expected Response | Turn 2 (User) | Expected Behavior |
|---|------|--------------|-------------------|---------------|-------------------|
| T07 | Confirm yes | "Text Bob I'm on my way" | "Send 'I'm on my way' to Bob?" + ["Send ✓", "Cancel ✗"] | "Yes" | Executes send_sms |
| T08 | Confirm no | "Text Bob I'm on my way" | confirm prompt | "No" | State → IDLE; "Okay, cancelled." |
| T09 | Ambiguous confirm reply | "Text Bob hello" | confirm prompt | "Maybe" | Re-prompt: "Should I send it? Yes or no?" |

### Error Recovery

| # | Flow | Turn 1 (User) | Expected Response | Turn 2 (User) | Expected Behavior |
|---|------|--------------|-------------------|---------------|-------------------|
| T10 | Retry on failure | "Text Bob hello" → skill returns Failure(recoverable=true) | "Couldn't send that text…" + ["Try Again", "Cancel"] | "Try Again" | Re-executes send_sms with same params |
| T11 | Non-recoverable | "Text Bob hello" → skill returns Failure(recoverable=false) | "Couldn't send that text: No SMS app found." (no chips) | — | State stays IDLE; no error recovery offered |

### Anaphoric Resolution

| # | Flow | Context | Turn 1 (User) | Expected Behavior |
|---|------|---------|---------------|-------------------|
| T12 | "Call him" | Last intent: send_sms(recipient="Bob") | "Call him" | Executes make_call(recipient="Bob") |
| T13 | "Do it again" | Last intent: set_alarm(hours=7, minutes=0) | "Do it again" | Executes set_alarm(hours=7, minutes=0) |
| T14 | No context | Context ring empty | "Call him" | PassThrough → QIR/E4B handles normally |

### Timeout

| # | Flow | Setup | Turns | Expected Behavior |
|---|------|-------|-------|-------------------|
| T15 | Silent abandon | State = SLOT_FILLING (asking for SMS body) | User sends 3 unrelated messages: "What time is it?", "What's the weather?", "Turn on torch" | After 3rd message: dialog silently abandoned; each message processes normally via QIR |

---

## 9. Migration from Phase 1

### Step 1: Add `DialogManager` alongside `SlotFillerManager`

Both coexist. `DialogManager` handles all new flows. `SlotFillerManager` continues
handling existing slot-fill calls. No behavior changes.

### Step 2: Route slot filling through `DialogManager`

`ChatViewModel` replaces the `slotFillerManager.hasPending` / `onUserReply()` block with
`dialogManager.process()`. `SlotFillerManager` is no longer called.

### Step 3: Remove `SlotFillerManager`

Delete `SlotFillerManager.kt`, `PendingSlotRequest.kt`. `SlotFillResult.kt` may be kept
if other code references it, or folded into `DialogDecision`.

### Step 4: Add remaining flows

Disambiguation, confirmation, error recovery, and anaphoric resolution are added
incrementally, each behind a feature flag if needed.

**Estimated migration:** Steps 1–3 in a single PR. Step 4 can be split per-flow.

---

## 10. Open Questions

| # | Question | Proposed Answer | Status |
|---|----------|-----------------|--------|
| Q1 | Should confirmation be skippable via a user preference? | Yes, but not in Phase 2. Add a "Don't ask me again" setting later. | Deferred |
| Q2 | Should E4B-initiated tool calls go through confirmation? | Yes, same policy. If `send_sms` requires confirmation, it doesn't matter whether QIR or E4B triggered it. | **Decided** |
| Q3 | What happens if QIR returns `NeedsSlot` but the intent also requires confirmation? | Slot fill first, then confirm. `SLOT_FILLING → CONFIRMING → Execute`. | **Decided** |
| Q4 | Should `ContextRing` entries expire after some wall-clock time? | Yes, entries older than 10 minutes are evicted on read. Prevents "call him" from resolving a contact from an hour ago. | **Decided** |
| Q5 | How does `allMissingSlots()` work for E4B tool calls? | E4B uses constrained decoding, so required params are always present (may be empty strings). Phase 2 only chains missing slots for QIR intents. E4B disambiguation/confirmation still applies. | **Decided** |
