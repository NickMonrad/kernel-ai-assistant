# Issue #481 — Hallucination Guard: Outcomes Audit

> **Branch:** `feature/519-profile-parser-llm-extraction`  
> **Audit date:** 2025-07-19  
> **Scope:** QIR param extraction gaps, ChatViewModel C2 guard effectiveness, NL test coverage, new risk patterns

---

## Executive Summary

The sprint fixed several acute hallucination vectors (`extractCalendarHints` rewrite, `cancel_timer` handler, C2 retry guard). However **systemic gaps remain**:

1. **5 intents** routed by QIR have **no handler branch** — every match → silent `Failure`
2. **`set_volume` direction-only path** always fails (handler ignores `direction`, hard-requires `value`)
3. **`create_calendar_event`** still fails on most natural phrasing — title/date extraction is too narrow
4. The **C2 guard only covers one code path** (plain-text LLM responses with no tool call); native SDK and legacy JSON tool-call paths are unguarded
5. The **NL test spec has zero hallucination tests** — it only validates routing and slot-filling

---

## 1. QIR → Handler Param Extraction Gaps

### 1.1 Intents With NO Handler Branch (Always Fail)

These intents match in QIR, but `NativeIntentHandler.handle()` has no `when` branch for them. Every match falls through to `Failure("run_intent", "Unknown intent: $intentName")`.

| Intent | QIR Extracts | Risk |
|--------|-------------|------|
| `cancel_alarm` | `emptyMap()` | 🔴 "Cancel my alarm" → instant Failure → LLM claims alarm cancelled |
| `get_weather` | `location` (optional) | 🔴 Weather query → instant Failure → LLM fabricates forecast |
| `get_system_info` | `emptyMap()` | 🔴 Device info query → instant Failure → LLM fabricates specs |
| `save_memory` | `content` | 🔴 "Remember that X" → instant Failure → LLM confirms memory saved |
| `set_brightness` | `direction` or `value`+`is_percent` | 🔴 Every brightness command → instant Failure |

### 1.2 Param Extraction Mismatches

#### 🔴 `set_volume` — direction-only path always fails

- **QIR pattern 1** (e.g. "turn volume up", "louder"): extracts `{"direction": "up"}` — no `value`
- **QIR pattern 2** (e.g. "set volume to 50%"): extracts `{"value": "50", "is_percent": "true"}` ✅
- **Handler**: `val raw = params["value"]?.toIntOrNull() ?: return Failure(…)` — hard-requires `value`; **never reads `direction`**
- **Result**: ~6 regex variants for relative volume changes always produce handler Failure

#### 🔴 `create_calendar_event` — title/date conditionally extracted but hard-required

- **QIR `extractCalendarHints()`**: Always emits `raw_query`. Conditionally emits:
  - `title` — only if "for a/an X" or verb+article pattern matches
  - `date` — only if a date keyword (tomorrow, next monday, bare weekday) matches
  - `time` — only if "at X" matches
- **Handler requires both** `title` and `date` with immediate `Failure` if either is absent
- **Gap**: Most natural calendar phrases ("add a meeting", "block time next week", "schedule an appointment") lack the narrow patterns needed → both absent → Failure

#### ⚠️ `set_alarm` — minor gap

- `parseAlarmTime()` emits `hours`, `minutes`, `time` (HH:mm), optional `day`, `label`
- Handler reads only `time` (string), `label`, `day` — **`hours`/`minutes` are dead keys**
- If `parseAlarmTime()` returns empty (unparseable input), `time` is absent → Failure
- Low frequency — most alarm phrases have parseable times

### 1.3 Aligned Intents (No Gap)

The following intents have full QIR ↔ handler alignment:

`toggle_flashlight_on/off`, `cancel_timer`, `get_battery`, `get_time/get_date`, `toggle_dnd_on/off`, `toggle_wifi`, `toggle_bluetooth`, `toggle_airplane_mode`, `toggle_hotspot`, `set_timer`, `send_email`, `send_sms`, `make_call`, `navigate_to`, `find_nearby`, `play_plex`, `play_youtube`, `play_spotify`, `play_plexamp`, `play_youtube_music`, `play_netflix`, `open_app`, `play_media`, `play_media_album`, `play_media_playlist`, `add_to_list`, `create_list`, `get_list_items`, `remove_from_list`, `smart_home_on/off`

---

## 2. ChatViewModel C2 Guard Analysis

### 2.1 How the Guard Works

```
hallucinationRetryAttempted = false   // reset per turn

do {
  inferenceEngine.generate(prompt).collect { result ->
    when Complete:
      if (no tool call detected) {
        if (looksLikeToolConfirmation(text) && !hallucinationRetryAttempted) {
          // C2: retry with correction prefix
          hallucinationRetryAttempted = true
          currentPrompt = HALLUCINATION_RETRY_CORRECTION + "\n\n" + prompt
        } else if (isHallucination) {
          // C1: replace with "I wasn't able to complete that action"
        }
      }
  }
} while (needsHallucinationRetry)
```

`looksLikeToolConfirmation()` matches ~22 fixed phrases ("i've saved", "alarm set", "timer set", "turned on", "done!", etc.) via case-insensitive substring search.

### 2.2 Guard Coverage by Code Path

| Code Path | Guard Applies? | Notes |
|-----------|---------------|-------|
| Pure LLM text (no tool call) | ✅ Yes | This is the only guarded path |
| Native SDK `@Tool` calls | ❌ No | Handled in `if (nativeToolCall != null)` branch; guard lives in `else` |
| Legacy JSON tool calls | ❌ No | Same branch as native SDK |
| QIR Tier 2 → E4B wrap (failure) | ✅ Partial | E4B's response is checked, but only against fixed phrase list |
| QIR Tier 2 → DirectReply | ❌ No | `return@launch` before LLM |
| Slot-fill completion | ❌ No | `return@launch` before LLM |

### 2.3 Edge Cases Where the Guard Fails

| # | Edge Case | Impact |
|---|-----------|--------|
| 1 | **Native SDK tool returns error not prefixed with "error"** — `isSuccess` is determined by `!result.startsWith("error")`. Strings like `"failed:"`, `"null"`, `"Exception:"` register as success. | LLM receives "success" + failure content; may hallucinate |
| 2 | **LLM rephrases confirmation** — "Your alarm has been scheduled", "Absolutely, that's been saved ✓", "All set!", "Memory updated" are NOT in the 22-phrase list | Guard doesn't trigger |
| 3 | **Token budget >75%** — retry is skipped, C1 only fires if `isHallucination` is true via the fixed phrase list | Same phrase-list blind spot |
| 4 | **Exceptions in tool execution** — caught by outer `try/catch`, user sees "Sorry, generation was cancelled." No hallucination but tool errors are silently swallowed. | Silent failure — user doesn't know what happened |
| 5 | **No timeout on generation** — no `withTimeout` around `inferenceEngine.generate()` | Stalled LLM → infinite hang |
| 6 | **No partial-success model** — `SkillResult` has no `PartialSuccess` variant. Alarm set at wrong time returns `Success`. | Semantic hallucination — technically succeeded but wrong |

### 2.4 Does the Guard Prevent Hallucination for the QIR Param Gaps?

For the 5 missing-handler intents and the `set_volume`/`create_calendar_event` gaps:

1. QIR matches → `NativeIntentHandler.handle()` returns `SkillResult.Failure`
2. Failure is injected into E4B's system context: `"[System: {intent} failed — {error}]"`
3. E4B generates a user-facing response with this failure context
4. **The guard DOES run** on E4B's output (QIR-failure → E4B wrap path)
5. **BUT** it only catches responses containing the 22 fixed phrases. E4B could say "I've handled that" or "All set" and evade detection.

**Verdict:** The guard provides **partial, brittle protection** for QIR param gaps. It is not a reliable safety net.

---

## 3. NL Test Specification Coverage

### 3.1 Current Coverage

The NL test spec (`docs/testing/nl-test-specification.md`) comprehensively covers:
- **§1**: Routing correctness (39 test groups — does utterance → correct intent?)
- **§2**: False positives / disambiguation (12 test groups)
- **§3**: Slot-filling / multi-turn (16 scenarios)
- **§4**: Edge cases (11 groups — vague, compound, typos, emoji)
- **§5**: Python harness assertions

### 3.2 Hallucination Coverage

**Zero.** The word "hallucination" does not appear in the document. No test asserts:
- What the response should be when a handler returns failure
- That the LLM must not claim success after a tool failure
- That a failed action must surface as an error to the user

### 3.3 Structural Gaps

| Gap | Detail |
|-----|--------|
| `web_search` | Not modelled anywhere in the spec — zero routing, slot, or hallucination tests |
| `create_reminder` | Not modelled as distinct intent — ambiguously mapped to `set_alarm` or `save_memory` |
| **All handler-failure scenarios** | Spec tests the request phase only; no coverage of the response phase after failure |

---

## 4. New Hallucination Risk Patterns

Beyond the known QIR param gaps, this audit identified:

### 4.1 Native SDK `isSuccess` Heuristic Is Fragile

```kotlin
isSuccess = !result.startsWith("error")
```

Any tool result not prefixed with literal `"error"` registers as success. This is a **systemic risk** across all `@Tool`-annotated methods — if any returns failure in a non-standard format, the LLM is told the tool succeeded.

### 4.2 Fixed Phrase List Cannot Scale

The 22-phrase `looksLikeToolConfirmation()` list is a string-matching heuristic. It will:
- Generate false negatives as LLM vocabulary evolves
- Never catch contextual hallucinations ("Your event is on your calendar" when no event was created)
- Require manual maintenance for every new intent

### 4.3 Tier 2 Failure Context Injection Is Advisory-Only

When QIR fails and the error is injected as `[System: intent failed — error]`, this is just a system message. The E4B model is free to ignore it. There is no structural enforcement that prevents the LLM from generating a success confirmation after reading a failure context.

### 4.4 Dead Key Extraction

`set_alarm`'s `parseAlarmTime()` extracts `hours` and `minutes` keys that the handler never reads. `toggle_airplane_mode` and `toggle_hotspot` extract `state` that their handlers never use. These are maintenance hazards — developers may assume these keys are used.

---

## 5. Recommended Fixes

### P0 — Critical (fix before next release)

| # | Fix | Detail |
|---|-----|--------|
| 1 | **Add handler branches for 5 missing intents** | `cancel_alarm`, `get_weather`, `get_system_info`, `save_memory`, `set_brightness` — implement real handlers or reroute to E4B with `DeferToLlm` so QIR doesn't match-then-fail |
| 2 | **Fix `set_volume` direction path** | Handler must read `direction` and translate to +/- step adjustment (e.g. `audioManager.adjustStreamVolume(…, ADJUST_RAISE/LOWER, …)`) |
| 3 | **Widen `extractCalendarHints()` or defer** | Either: (a) expand title/date regex to cover common natural phrasing, or (b) if title or date is missing, set a `needs_llm_extraction: true` flag and let E4B fill the gaps via tool call instead of returning immediate Failure |

### P1 — High (fix in next sprint)

| # | Fix | Detail |
|---|-----|--------|
| 4 | **Replace `isSuccess` string-prefix heuristic** | Define a `ToolResult` sealed class with explicit `Success`/`Failure` variants for native SDK tool returns. Never infer success from string content. |
| 5 | **Extend C2 guard to tool-call paths** | After native SDK and legacy JSON tool calls, if `isSuccess == false`, inject `HALLUCINATION_RETRY_CORRECTION` context and run the same retry/C1 logic |
| 6 | **Add §6 to NL test spec: Tool Failure / Hallucination Guard Tests** | For every state-mutating intent (`send_sms`, `make_call`, `create_calendar_event`, `send_email`, `play_*`, `navigate_to`, `set_alarm`, `set_timer`, `toggle_*`): one test with mocked handler failure asserting the response must NOT contain confirmation phrases |

### P2 — Medium (backlog)

| # | Fix | Detail |
|---|-----|--------|
| 7 | **Replace phrase list with classifier** | Replace `looksLikeToolConfirmation()` with a lightweight binary classifier (e.g. BERT-tiny fine-tuned on success/failure confirmations) to catch novel phrasings |
| 8 | **Add generation timeout** | Wrap `inferenceEngine.generate()` with `withTimeout(30_000)` to prevent infinite hangs |
| 9 | **Remove dead keys** | Remove `hours`/`minutes` from `parseAlarmTime()` return, remove `state` from `toggle_airplane_mode`/`toggle_hotspot` extraction |
| 10 | **Model `web_search` and `create_reminder`** | Add as first-class intents with routing tests, slot-fill tests, and hallucination tests |
| 11 | **Structural failure enforcement** | Instead of advisory `[System: failed]` context, use a hard gate: if `SkillResult.Failure`, bypass E4B entirely and show a fixed failure template. This eliminates LLM hallucination risk for known failures at the cost of less natural error messages. |

---

## Appendix: Param Gap Summary Table

| Intent | QIR Params | Handler Requires | Gap | Severity |
|--------|-----------|-----------------|-----|----------|
| `cancel_alarm` | `emptyMap()` | N/A (no branch) | No handler | 🔴 |
| `get_weather` | `location` (opt) | N/A (no branch) | No handler | 🔴 |
| `get_system_info` | `emptyMap()` | N/A (no branch) | No handler | 🔴 |
| `save_memory` | `content` | N/A (no branch) | No handler | 🔴 |
| `set_brightness` | `direction` or `value` | N/A (no branch) | No handler | 🔴 |
| `set_volume` (dir) | `direction` | `value` (hard) | `direction` ignored | 🔴 |
| `create_calendar_event` | `title` (cond), `date` (cond) | `title` (hard), `date` (hard) | Both conditional | 🔴 |
| `set_alarm` | `time` (cond) | `time` (hard) | Minor parse failures | ⚠️ |
