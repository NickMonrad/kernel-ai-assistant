# Plan: Issue #621 — Multi-turn QIR: dispatch pending intent on user confirmation

## Problem

When the LLM infers the user's intent from context and offers to perform an action (e.g., *"Want me to flick the flashlight on for you?"*), the user's follow-up *"Yeah"* routes through full LLM inference instead of being resolved as a pending confirmation. The `pendingConfirmationIntent` is only set when QIR's classifier returns `needsConfirmation=true`, but NOT when the LLM itself offers to perform an intent.

## Solution

Detect when the LLM offers to perform a known intent and set `pendingConfirmationIntent`. The existing affirmation shortcut (lines 707-743 in `ChatViewModel.kt`) already handles dispatching on affirmation — it just needs the pending intent to be set.

## Changes

### 1. New data class + detection function in `ChatTextUtils.kt`

```kotlin
/**
 * Represents an intent the LLM offered to perform but did not execute.
 * Used to enable the confirmation fast-path (#621) when the user
 * follows up with a simple affirmation.
 */
data class LlmIntentOffer(
    val intentName: String,
    val confidence: Float,
)

/**
 * Detects when the LLM response is a yes/no offer to perform a known intent.
 *
 * Matches patterns like:
 *   "Want me to turn on the flashlight?"
 *   "Should I adjust the volume?"
 *   "Shall I set an alarm for 7am?"
 *   "I can turn on the WiFi for you — want me to?"
 *
 * Returns the matched intent name via the MiniLM classifier, or null if no
 * clear offer pattern is detected. The classifier is only invoked when an
 * offer pattern is present, keeping the common path fast.
 */
fun looksLikeLlmIntentOffer(
    response: String,
    classifier: QuickIntentRouter.IntentClassifier?,
): LlmIntentOffer?
```

Detection logic:
1. Quick filter: check for offer patterns (`"want me to"`, `"should i"`, `"shall i"`, `"i can.*want"` + `?`)
2. If no pattern, return null (fast path for non-offer responses)
3. If pattern found, pass the response through the MiniLM classifier
4. If classifier returns confidence ≥ 0.65, return `LlmIntentOffer`
5. Otherwise return null (uncertain — let LLM handle it)

The lower confidence threshold (0.65 vs 0.75) accounts for LLM offers being phrased as questions rather than direct commands.

### 2. Set `pendingConfirmationIntent` in LLM completion handler (`ChatViewModel.kt`)

In the `GenerationResult.Complete` handler, after tool call processing and before hallucination detection (~line 1064):

```kotlin
// #621: If the LLM offered to perform an intent but didn't execute it,
// set pendingConfirmationIntent so the user's next affirmation dispatches
// the intent directly (skipping another LLM round-trip).
if (nativeToolCall == null && toolCallResult == null) {
    val offer = looksLikeLlmIntentOffer(fullContent, quickIntentRouter.classifier)
    if (offer != null) {
        pendingConfirmationIntent = QuickIntentRouter.MatchedIntent(
            intentName = offer.intentName,
            params = emptyMap(),
            source = "llm_offer",
        )
        Log.d("KernelAI", "LlmIntentOffer: pending ${offer.intentName} (conf=${offer.confidence})")
    }
}
```

The check `nativeToolCall == null && toolCallResult == null` ensures we don't set pending confirmation when the LLM already executed a tool.

### 3. Add negation detection in `ChatTextUtils.kt`

```kotlin
val NEGATIONS = setOf(
    "no", "nope", "nah", "never mind", "nevermind",
    "don't bother", "don't do it", "skip it",
    "not now", "not right now", "maybe later",
    "cancel", "cancel that", "forget it",
)

fun isNegation(input: String): Boolean = input.trim().lowercase() in NEGATIONS
```

### 4. Clear `pendingConfirmationIntent` on negation (`ChatViewModel.kt`)

In the confirmation shortcut block (~line 740), change the non-affirmation branch:

```kotlin
} else if (pendingConfirmation != null) {
    if (QuickIntentRouter.isNegation(text)) {
        Log.d("KernelAI", "ConfirmationFastPath: negation, clearing pending ${pendingConfirmation.intentName}")
    }
    pendingConfirmationIntent = null
}
```

### 5. Expand `AFFIRMATIONS` set (`QuickIntentRouter.kt`)

Add common affirmative responses that aren't currently captured:

```kotlin
val AFFIRMATIONS = setOf(
    "yes", "yeah", "yep", "yup", "sure", "ok", "okay",
    "go ahead", "go for it", "do it", "please", "please do",
    "aye", "absolutely", "definitely", "go on", "sounds good",
    // Additional confirmations for LLM-offered intents
    "that would be great", "that'd be great", "yes please",
    "that sounds good", "that works", "go ahead with it",
    "yeah do that", "yep that", "ok let's",
)
```

## Files modified

| File | Change |
|------|--------|
| `feature/chat/src/main/java/.../chat/ChatTextUtils.kt` | Add `LlmIntentOffer` data class, `looksLikeLlmIntentOffer()`, `NEGATIONS` set, `isNegation()` |
| `feature/chat/src/main/java/.../chat/ChatViewModel.kt` | Call `looksLikeLlmIntentOffer()` in completion handler; use `isNegation()` in confirmation shortcut |
| `core/skills/src/main/java/.../skills/QuickIntentRouter.kt` | Expand `AFFIRMATIONS` set |
| `feature/chat/src/test/java/.../chat/ChatTextUtilsTest.kt` | Add tests for `looksLikeLlmIntentOffer()` and `isNegation()` |

## Testing

**Unit tests for `looksLikeLlmIntentOffer()`:**
- Positive: "Want me to turn on the flashlight?", "Should I set an alarm?", "Shall I adjust the volume?", "I can help with that — want me to pause the media?"
- Negative: "What time is it?", "The weather is sunny.", "I've saved that to memory.", "Here's what I found about quantum physics"
- Edge cases: empty string, single word, multiple offers

**Unit tests for `isNegation()`:**
- Positive: "no", "nope", "nah", "never mind", "don't bother", "cancel", "cancel that", "forget it"
- Negative: "yes", "maybe", "what about the weather", "tell me more"

## Trade-offs considered

1. **Classifier vs keyword matching for intent detection**: Classifier is more robust to varied LLM phrasing but adds ~30ms latency. Only invoked when offer pattern is detected (fast path for non-offers). Keyword matching would be faster but miss varied phrasings.

2. **Confidence threshold**: 0.65 (lower than the 0.75 classifier threshold) because LLM offers are question phrasings, not direct commands. The existing affirmation shortcut provides a safety net — if the wrong intent is set, the user can negate it.

3. **Where to put detection function**: `ChatTextUtils.kt` keeps it alongside other text analysis functions (`looksLikeToolConfirmation`, `looksLikeAnaphora`). Could also go in `QuickIntentRouter.kt` but that's in a different module.

4. **Params for LLM-offered intents**: Initially set to `emptyMap()` since LLM offers for confirmation typically don't carry user-supplied params. If the LLM offers an intent that needs params, the skill execution will handle missing params gracefully.
