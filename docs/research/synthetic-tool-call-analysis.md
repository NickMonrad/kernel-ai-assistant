# Synthetic Tool Call Constrained Decoding — Failure Analysis

**Date:** 2026-05-25  
**Issue:** Meal planner `generateStructuredOnce` returns empty string or hangs  
**PR:** #976 (merged to main)  
**Status:** BROKEN — model never produces the expected structured output

---

## Problem Summary

The meal planner's `generateStructuredOnce` call (which uses a synthetic OpenAPI tool call to trigger constrained decoding) consistently fails to produce structured JSON output. The model either:

1. **Returns empty string** — `generateStructuredOnce` times out or the text fallback triggers before any tool call arrives
2. **Hangs indefinitely** — the model generates text without calling the synthetic tool, and the 60s timeout eventually fires
3. **Generates wrong output** — the model produces natural language text instead of a tool call

### Device Test Evidence (from PR #976 comments)

Every device test shows the same pattern:
- Slot collection works fine (model correctly collects people, days, dietary, protein, cuisine preferences)
- When `generatePlanForReview` is called, the model either:
  - Returns empty string → "I couldn't finish building the plan because the model didn't return one"
  - Hangs → "I'm still building your meal plan. Give me a moment" (repeatedly)
  - Produces variety validation errors → "I couldn't generate a varied enough high-level plan yet"

---

## Current Implementation

### Architecture

```
MealPlannerCoordinator.generatePlanForReview()
  └─ inferenceEngine.generateStructuredOnce(
       prompt = buildPlanUserPrompt(snapshot, history, favRecipes),
       spec = StructuredOutputSpec.MealPlan,
       systemPrompt = buildPlanSystemPrompt(),
       thinkingEnabled = false,
     )
```

### `generateStructuredOnce` Flow (LiteRtInferenceEngine.kt)

1. Builds a **synthetic OpenAPI tool** from `StructuredOutputSpec` — the tool's `execute()` method echoes its input back unchanged
2. Creates a **new conversation** with `automaticToolCalling = false` and `enableConversationConstrainedDecoding = true`
3. Sends the prompt via `sendMessageAsync`
4. Callback waits for `message.toolCalls` matching the spec's tool name
5. If no tool call arrives within 60s or 2000 chars of text, returns empty string

### Key Code Path

```kotlin
// Synthetic tool echoes input back — no-op for the model
val syntheticToolProvider = tool(
    object : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = desc  // JSON schema
        override fun execute(paramsJsonString: String): String = paramsJsonString  // echo
    },
)

// Conversation config
val convConfigWithTool = convConfig.copy(
    tools = listOf(syntheticToolProvider),
    automaticToolCalling = false,  // model's tool call returned to callback, not auto-executed
)

ExperimentalFlags.enableConversationConstrainedDecoding = true
val conv = eng.createConversation(convConfigWithTool)

// Callback waits for tool call
conv.sendMessageAsync(
    Contents.of(Content.Text(prompt)),
    object : MessageCallback {
        override fun onMessage(message: Message) {
            val toolCalls = message.toolCalls
            if (toolCalls.isNotEmpty()) {
                // Extract JSON from tool call arguments
                capturedToolJson.set(JSONObject(call.arguments).toString())
                latch.complete(capturedToolJson.get()!!)
            }
            // Fallback: text accumulation with 2000 char limit
            if (responseBuilder.length > 2000 && capturedToolJson.get() == null) {
                conv.cancelProcess()
                latch.complete("")
            }
        }
    },
    if (requestedThinkingEnabled) mapOf("enable_thinking" to true) else emptyMap(),
)
```

### System Prompt (designed for tool calling)

```
You generate a high-level meal plan for a local-first Android assistant.
You MUST call the tool `emit_meal_plan` with your plan as the single argument.
The argument must be a JSON object with this exact shape:
{
  "days": [
    {
      "day_index": 0,
      "title": "...",
      "summary": "...",
      "protein_tags": ["..."]
    }
  ]
}
```

---

## Why This Approach Fails

### Root Cause: Synthetic Tool Calls Don't Trigger Constrained Decoding

The synthetic tool call approach relies on the LiteRT-LM SDK's constrained decoding path being activated when tools are present. However:

1. **The synthetic tool is a no-op** — it echoes its input back. The model sees a tool that does nothing useful, so it has no incentive to call it.
2. **`automaticToolCalling = false`** — the model's tool call is returned to the callback instead of being auto-executed. This is correct for extraction, but it means the model must *explicitly* choose to call the tool.
3. **Constrained decoding requires the model to actually use the tool path** — if the model generates text instead of a tool call, constrained decoding never activates.
4. **The model sees the tool as artificial** — unlike real skills (e.g., `saveMemory`, `get_system_info`), the synthetic tool has no real-world semantics. The model doesn't "understand" why it should call it.

### Evidence from Device Logs

The logs show the model generating text responses instead of tool calls. The text fallback (2000 chars) triggers, and `generateStructuredOnce` returns empty string.

### Comparison with Working Tool Calls

Real skill tool calls work because:
- They have clear semantics (e.g., `saveMemory` stores a memory, `get_system_info` returns the current time)
- The system prompt instructs the model to use them
- The model recognizes them as legitimate actions

The synthetic tool has none of these properties. It's a hack to trigger constrained decoding, and the model sees through it.

---

## LiteRT-LM Constrained Decoding (Native Support)

According to the official documentation:

- **[Constrained Decoding Docs](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/cpp/constrained-decoding.md)**
- **[LiteRT-LM C++ API — Constrained Decoding](https://ai.google.dev/edge/litert-lm/cpp#constrained_decoding)**

The LiteRT-LM SDK **natively supports constrained decoding** via the C++ API. This is a first-class feature, not a hack.

### How Native Constrained Decoding Works

```cpp
// C++ API example (from docs)
auto config = lite_rt_lm::ConversationConfig();
config.set_constrained_decoding(true);
config.set_constrained_decoding_schema(json_schema);

auto conversation = engine.CreateConversation(config);
conversation->SendMessageAsync(
    lite_rt_lm::Contents::Of(lite_rt_lm::Content::Text(user_prompt)),
    callback
);
```

The SDK enforces JSON structure at the **token level** — the model can only emit tokens that are valid according to the schema. This is fundamentally different from the synthetic tool call approach, which relies on the model *choosing* to call a tool.

### Key Differences

| Aspect | Synthetic Tool Call (Current) | Native Constrained Decoding |
|--------|------------------------------|---------------------------|
| Activation | Model must explicitly call synthetic tool | SDK enforces at token level |
| Reliability | Low — model often ignores synthetic tool | High — enforced by SDK |
| Token efficiency | Wastes tokens on text before tool call | Direct JSON output |
| Schema enforcement | Best-effort (model can still produce invalid JSON) | Strict (invalid tokens blocked) |
| Implementation complexity | Simple (no SDK changes) | Requires JNI/Kotlin SDK additions |

---

## Options

### Option A: Abandon Synthetic Tool Call Approach

**Recommendation:** YES — abandon the synthetic tool call approach entirely.

**Reasons:**
1. The approach is fundamentally flawed — synthetic tools don't trigger constrained decoding reliably
2. Multiple iterations have failed to make it work (PR #976 had 8+ iterations)
3. The model consistently produces text instead of tool calls
4. The approach wastes tokens and context window on failed attempts

**Next steps:**
- Revert PR #976's `generateStructuredOnce` implementation
- Use the native constrained decoding path (Option B) instead

### Option B: Implement Native Constrained Decoding

**What's needed:**
1. Add `DecodingConstraint` sealed class to `core:inference`
2. Add `decodingConstraint` parameter to `InferenceEngine.generateOnce()`
3. Wire conversation-level enablement for non-tool conversations that request constraints
4. Build schemas from runtime state (not hard-coded)
5. Add a capability gate (check if SDK supports constrained decoding)

**Implementation plan:**
1. Define `DecodingConstraint.JsonSchema` with `Map<String, Any>` schema parameter
2. Pass constraint via `extraContext["__kernel_ai_internal.decoding_constraint"]` (LiteRT-LM C++ bridge wire format)
3. Set `ExperimentalFlags.enableConversationConstrainedDecoding = true` before `createConversation()`
4. Add capability detection: try a small constrained generation and check if it works
5. Graceful fallback: if constrained decoding is not supported, fall back to `generateOnce` + text parser

**Pros:**
- Uses the SDK's first-class feature as intended
- Reliable, token-efficient, strict schema enforcement
- No synthetic tool hack needed

**Cons:**
- Requires JNI/Kotlin SDK additions
- May need upstream PR to LiteRT-LM if the wire format isn't exposed

### Option C: Better Logging (Interim)

**What's needed:**
1. Add verbose logging to `generateStructuredOnce` that captures:
   - Full prompt sent to the model
   - All messages received from the model (text chunks, tool calls)
   - Timeout and cancellation events
   - JSON extraction results
2. Export logs via Jandal's existing verbose logging / export functionality
3. Log the raw LiteRT-LM conversation state at each step

**Why this helps:**
- Would have caught the "model generates text, not tool calls" issue earlier
- Provides visibility into what the model is actually doing
- Helps diagnose future structured output failures

**This is a stopgap, not a solution.** It helps diagnose but doesn't fix the underlying problem.

---

## Recommendation

**Abandon the synthetic tool call approach (Option A) and implement native constrained decoding (Option B).**

The synthetic tool call approach is a workaround that doesn't work. The LiteRT-LM SDK has first-class constrained decoding support — use it directly.

**Priority:**
1. **Option C (better logging)** — immediate, helps diagnose current and future issues
2. **Option B (native constrained decoding)** — proper fix, replaces synthetic tool call approach
3. **Option A (abandon synthetic tool call)** — consequence of Option B

---

## Files Referenced

- `core/inference/src/main/java/com/kernel/ai/core/inference/LiteRtInferenceEngine.kt` — `generateStructuredOnce` implementation
- `core/inference/src/main/java/com/kernel/ai/core/inference/StructuredOutputSpec.kt` — JSON schemas
- `core/skills/src/main/java/com/kernel/ai/core/skills/mealplan/MealPlannerCoordinator.kt` — call sites
- PR #976 — synthetic tool call implementation (merged, but broken)

## Device Test Logs

- `kernel_debug_log_0.1.0_20260524_204500_347.txt` — first test (empty output)
- `kernel_debug_log_0.1.0_20260524_211940_831.txt` — second test (empty output)
- `kernel_debug_log_0.1.0_20260524_220600_807.txt` — third test (empty output)
- `kernel_debug_log_0.1.0_20260525_004439_087.txt` — fourth test (hangs)
- `kernel_debug_log_0.1.0_20260525_071700_044.txt` — fifth test (hangs)
## Oracle Review (2026-05-25)

Issue: https://github.com/NickMonrad/kernel-ai-assistant/issues/977

### Verdict
**Abandon synthetic tool call approach. Not salvageable in current SDK.**

### Root Cause (confirmed by Oracle)

The problem is deeper than the model ignoring the synthetic tool:

1. **Gemma-4's constraint mode is `kTextAndOr` by default** — even with constrained decoding enabled, the model is *explicitly allowed* to output plain text. Prompting harder cannot turn a permissive output space into a guarantee.

2. **LiteRT-LM Kotlin/JNI 0.11.0 has no binding for native constrained decoding.** The C++ API supports `OptionalArgs.decoding_constraint` and `ConstraintProviderConfig`, but the Kotlin/JNI layer only exposes `ExperimentalFlags.enableConversationConstrainedDecoding` plus tool-preface wiring. `ConversationConfig` has no constraint-provider field, and `sendMessageAsync` only carries `extraContext` — not a decoding constraint.

3. **`extraContext["__kernel_ai_internal.decoding_constraint"]` is not a real bridge.** The JNI layer ignores that concept entirely.

### Evidence

- `gradle/libs.versions.toml` pins `litertlm-android` version `0.11.0`
- Kotlin `ConversationConfig` exposes: system instruction, tools, sampler config, channels, extraContext — **no constraint provider**
- Kotlin `LiteRtLmJni.kt`: `nativeCreateConversation`, `nativeSendMessage`, `nativeSendMessageAsync` have no decoding-constraint parameters
- Kotlin `Capabilities` only exposes `hasSpeculativeDecodingSupport()` — no constrained-decoding capability gate
- Upstream already tracks this: [google-ai-edge/LiteRT-LM#1662](https://github.com/google-ai-edge/LiteRT-LM/issues/1662)

### Recommended Path

**Primary:** Vendor or fork LiteRT-LM Kotlin/JNI and expose native JSON-schema constrained decoding. Then replace `generateStructuredOnce`'s synthetic-tool path with a direct schema constraint.

**Secondary (not recommended):** Expose `Gemma4DataProcessorConfig.constraint_mode = kFunctionCallOnly` and keep the synthetic tool wrapper. This is a smaller SDK change but keeps the tool-call hack and doesn't expose the first-class JSON-schema API.

### Implementation Plan (Oracle)

1. In the vendored LiteRT-LM binding, add a Kotlin `DecodingConstraint` API (`JsonSchema`, `Regex`, `Lark`) and conversation-level enablement
2. Extend JNI `nativeCreateConversation` to call `SetEnableConstrainedDecoding(true)` and `SetConstraintProviderConfig(LlGuidanceConfig())`
3. Extend JNI `nativeSendMessage`/`nativeSendMessageAsync` to accept a decoding constraint and populate `OptionalArgs.decoding_constraint`
4. In app code, replace `generateStructuredOnce` synthetic-tool generation with direct constrained send using `spec.jsonSchema`
5. Verify on device with a trivial schema smoke test and a meal-plan schema smoke test

### Logging Note

If exportable proof is needed before SDK work, log raw `Message` JSON in `generateStructuredOnce`. Current logging based on `Message.toString()` discards `tool_calls`, which can hide what the SDK actually returned.
