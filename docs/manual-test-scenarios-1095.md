# Manual Device Test Scenarios — PR #1095 (Intent Recovery Orchestrator)

## Prerequisites
- Device connected via ADB (S23 Ultra or equivalent with GPU inference)
- Fresh install recommended for baseline
- `adb logcat -s KernelAI` for recovery/logging observation

---

### 1. FallThrough recovery — medium risk, sufficient slots
**Steps:**
1. Type "chuck dinner with Sam in for next Friday night"
2. Observe assistant response

**Expected:**
- QIR produces a FallThrough with `bestGuess` for `create_calendar_event`
- Orchestrator extracts `date=next friday` (deterministic, no Gemma)
- Since risk is medium (`EditCalendar`), orchestrator returns `AskConfirmation`
- Chat displays a confirmation prompt (not a silent slot-fill start)
- Log line: `RecoveryOrchestrator.askConfirmation`

---

### 2. Low risk — execute directly (no confirmation)
**Steps:**
1. Open a context where a low-risk intent is the best guess
2. Observe

**Expected:**
- Low-risk intents (`ReadCalendar`, `ReadSms`, `OpenApp`) with sufficient params execute directly
- No confirmation prompt shown
- Response text includes the tool call result

---

### 3. High risk — always requires confirmation
**Steps:**
1. Type "send 50 dollars to mum" (high risk — `SendPayment`)
2. Observe

**Expected:**
- `SendPayment` is high risk
- Orchestrator returns `AskConfirmation`
- Wallet/execution **never** happens without affirmative user reply
- Confirmation prompt asks user to confirm

---

### 4. Missing slots — AskSlot flow
**Steps:**
1. Type "set a meeting" (no time specified)
2. Type "confirm" / "yes" when prompted

**Expected:**
- Orchestrator returns `AskSlot` with the missing slot (date/time)
- SlotFillerManager starts a recovery slot-fill
- On user reply, the slot is filled and execution proceeds
- If `isSlotCollectionMode`, the slot is collected and a follow-up question asked
- If `isMediumOrHigherRisk`, execution requires one more confirmation

---

### 5. Capability query — no extraction
**Steps:**
1. Type "do you know how to create calendar events"
2. Observe

**Expected:**
- QIR may produce a FallThrough with `create_calendar_event` guess
- CalendarSlotExtractor returns `NotActionable` (capability guard fires because no extracted params)
- Orchestrator returns `NotActionable`
- Fall through to Gemma for a natural language response
- No slot-fill or execution attempt

---

### 6. Polite action request with temporal cue — extracts
**Steps:**
1. Type "can you put dinner with Sam in for next Friday night"
2. Observe

**Expected:**
- QIR FallThrough with `create_calendar_event` best guess
- CalendarSlotExtractor extracts `date=next friday`
- Capability guard does **not** fire (params non-empty)
- Normal AskConfirmation or AskSlot flow

---

### 7. Low-confidence fallback (confidence < 0.55)
**Steps:**
1. Type an ambiguous phrase that barely matches any intent
2. Observe

**Expected:**
- QIR produces a FallThrough but with confidence < 0.55
- Orchestrator returns `NotActionable` (SOFT_FALLBACK_THRESHOLD)
- Falls through to Gemma

---

### 8. Recovered slot-fill — P0 risk gate
**Steps:**
1. Trigger a recovered slot-fill for a medium/high-risk intent
2. Fill the missing slot when prompted
3. Observe

**Expected:**
- After slot is filled, risk check runs
- Medium/high-risk recovered slot-fill **does not** auto-execute
- User must confirm before execution proceeds
- Log shows the risk check

---

### 9. Tool call chip sanitisation
**Steps:**
1. Trigger any recovery execution (low risk, direct)
2. Observe the assistant message with the tool call chip

**Expected:**
- Tool call chip shows `skillName = "Recovered: intentName"`
- Tool call chip shows empty `requestJson` (no raw params leaked)
- No raw intent name or parameter map visible in the chip

---

### 10. Fallthrough with no extractor — Gemma fallback
**Steps:**
1. Type something that produces a FallThrough with an intent that has **no** registered extractor (e.g. `send_sms`, `read_sms`)
2. Observe

**Expected:**
- Orchestrator checks for a supporting extractor → returns `NotActionable`
- Falls through to Gemma
- Gemma handles the intent normally

---

### 11. Conversation recovery state cleanup
**Steps:**
1. Trigger a recovery flow (e.g. AskSlot, AskConfirmation)
2. Type a completely unrelated message (cancelling the flow)
3. Trigger recovery again

**Expected:**
- Recovery conversation state is cleared on `onUserReply` cancel/diversion
- Second recovery starts fresh
- No stale recovery state leaks across unrelated replies

---

### 12. Acceptance test — end-to-end meal plan creation
**Steps:**
1. Type "chuck dinner with Sam in for next Friday night" (or similar)
2. If confirmation is asked, reply "yes"
3. Observe the full slot-fill and execution path

**Expected:**
- Intent is recovered deterministically (no Gemma for extraction)
- Slot fill completes via user interaction
- Calendar event is created
- Response confirms the action with appropriate summary
