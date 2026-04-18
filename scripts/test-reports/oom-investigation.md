# OOM / KV-Cache Growth Investigation — Issue #542

**Branch:** `feat/sprint5-oom-investigate`  
**Author:** Copilot  
**Date:** 2026-04-18  
**Related:** PR #557 (merged), PR #558 (open, `feat/s5-rolling-context`)

---

## 1. Summary

The original hypothesis is **partially wrong in direction**. Three distinct issues
were found; two are now fixed in this branch and one is deferred to PR #558.

---

## 2. Files Investigated

| File | Key findings |
|---|---|
| `core/inference/src/main/java/com/kernel/ai/core/inference/LiteRtInferenceEngine.kt` | `EngineConfig(maxNumTokens = resolvedConfig.maxTokens)` where `resolvedConfig.maxTokens = safeTokenCount(config.maxTokens.coerceAtMost(profile.recommendedMaxTokens))` |
| `core/inference/src/main/java/com/kernel/ai/core/inference/ModelConfig.kt` | `DEFAULT_MAX_TOKENS = 8000` (L57); `safeTokenCount()` reduces power-of-2 values: 4096→4000, 8192→8000 |
| `core/inference/src/main/java/com/kernel/ai/core/inference/hardware/HardwareProfileDetector.kt` | `recommendedMaxTokens`: FLAGSHIP=8000, MID_RANGE=2000, LOW_POWER=1000 |
| `core/memory/…/repository/ModelSettingsRepositoryImpl.kt` | Default `contextWindowSize = hardwareProfileDetector.profile.recommendedMaxTokens` |
| `feature/chat/src/main/java/com/kernel/ai/feature/chat/ChatViewModel.kt` | `activeContextWindowSize` (L94 default 4096); `proactiveReset` at 75% of `tokenBudget` (L754-755); `estimatedTokensUsed` tracking (L112, L795-797, L908-909, L966-967) |

---

## 3. Hypothesis Evaluation

> **Original hypothesis:** model loaded with `DEFAULT_MAX_TOKENS = 8000` but
> `activeContextWindowSize` tracks 4096, so `proactiveReset` fires at ~3072 while
> KV cache can hold 8000 — leaving a ~5000-token gap of unconstrained growth.

**Status: Hypothesis direction is inverted for most devices; real bugs differ.**

### What actually happens in the steady-state path

1. Both `initGemma4()` callers (eager init at L396 and lazy init at L440) do:
   ```kotlin
   val settings = modelSettingsRepository.getSettings(preferred.modelId)
   activeContextWindowSize = settings.contextWindowSize          // e.g. 8000
   inferenceEngine.initialize(ModelConfig(maxTokens = settings.contextWindowSize, ...))
   ```
   `settings.contextWindowSize` defaults to `recommendedMaxTokens` (FLAGSHIP=8000).
   Both `activeContextWindowSize` **and** the LiteRT `maxNumTokens` come from the
   same source — they are aligned in the normal case.

2. `DEFAULT_MAX_TOKENS = 8000` (L57, ModelConfig.kt) is only the **fallback constructor
   default** for `ModelConfig`. It is never used at runtime because both init paths
   explicitly pass `maxTokens = settings.contextWindowSize`.

---

## 4. Real Issues Found

### Issue A — `safeTokenCount` mismatch (fixed in this PR)

**Severity: Medium**

`LiteRtInferenceEngine.initialize()` applies `safeTokenCount()` to avoid a
GPU reshape alignment bug on Adreno hardware
(L82-84, `LiteRtInferenceEngine.kt`):

```kotlin
maxTokens = safeTokenCount(config.maxTokens.coerceAtMost(profile.recommendedMaxTokens))
```

`safeTokenCount` reduces **exact powers of 2**:
- 4096 → 4000 (−96 tokens)
- 8192 → 8000 (−192 tokens)

But `activeContextWindowSize` was set to the **raw** `settings.contextWindowSize`
before `initialize()` returned — so the two values could diverge.

**Consequence (inverted from original hypothesis):** KV cache fills at 4000
tokens but `proactiveReset` doesn't fire until `estimatedTokensUsed > 3072`
(`4096 × 0.75`). If a reply fills the remaining 928 tokens, LiteRT's KV cache
overflows **before** the reset fires, producing OOM.

**Fix:** After `initialize()`, read back `inferenceEngine.resolvedMaxTokens.value`
(new `StateFlow<Int>` on the interface) and update `activeContextWindowSize` if
non-zero. `LiteRtInferenceEngine` emits `resolvedConfig.maxTokens` (post-clamp)
into this flow after init.

```
ChatViewModel.kt  L419, L476 (both init paths):
  inferenceEngine.resolvedMaxTokens.value.takeIf { it > 0 }?.let {
      activeContextWindowSize = it
  }
```

---

### Issue B — Thinking tokens not counted (fixed in this PR)

**Severity: High — likely primary driver of the Sprint 4 OOM failures**

Gemma 4's chain-of-thought channel (`<|think|>…<|/think|>`) emits reasoning
tokens before the visible response. These tokens are:

- Received as `GenerationResult.Thinking` in `ChatViewModel`
- Stored in `accumulatedThinking` and persisted to Room  
- **NOT added to `estimatedTokensUsed`** at either `Complete` handler site

From `LiteRtInferenceEngine.generate()`, `thinkingCharCount` is logged
(`Log.d("KernelAI", "Thinking tokens: $thinkingCharCount chars")`) but this
count is never surfaced to `ChatViewModel`.

In practice, Gemma 4 thinking output for a typical query is ~300–800 characters
(~100–270 tokens at 3 chars/token). Over 15 turns this accumulates to
**~1500–4000 uncounted tokens** — enough to fill the entire gap undetected and
push the real KV usage well past the proactiveReset threshold without triggering
it.

**Fix:** At both `estimatedTokensUsed +=` sites inside the `GenerationResult.Complete`
handler, add `thinking` (the accumulated chain-of-thought string for this turn):

```kotlin
// Tool-call path (L920-921 before fix, L920-922 after):
estimatedTokensUsed += contextWindowManager.estimateTokens(text) +
    contextWindowManager.estimateTokens(resultContent) +
    contextWindowManager.estimateTokens(thinking ?: "")

// Normal-reply path (L966-967 before fix, L966-968 after):
estimatedTokensUsed += contextWindowManager.estimateTokens(text) +
    contextWindowManager.estimateTokens(displayContent) +
    contextWindowManager.estimateTokens(thinking ?: "")
```

---

### Issue C — No token-usage logging (fixed in this PR)

**Severity: Medium (observability)**

`estimatedTokensUsed` was never logged. With 17/23 Sprint 4 test failures
in the OOM range (tests 141-172), there was no logcat signal to correlate
KV growth with failures.

**Fix:** Added at the `proactiveReset` evaluation:

```kotlin
Log.d("KernelOOM", "tokens_in_use=$estimatedTokensUsed budget=$tokenBudget " +
    "threshold=${(tokenBudget * 0.75).toInt()} proactive_reset=$proactiveReset")
```

Filter in logcat: `adb logcat -s KernelOOM`

---

### Issue D — No turn-count guard (deferred to PR #558)

**Severity: Medium**

PR #558 (`feat/s5-rolling-context`) adds `MAX_CONTEXT_TURNS = 12` and
`turnCountReset` alongside `proactiveReset`. This branch is based on `main`
(which has PR #557 merged but not #558) and does **not** include the turn-count
guard.

A session with many short turns (e.g. skill-testing harness running ~40 rapid
tool calls) could stay below the 75% token threshold while still accumulating a
large KV cache from repeated system-prompt injections and tool call/result pairs.

**Recommendation:** Merge PR #558 after this PR. The two PRs are complementary:
this PR fixes the counter accuracy; PR #558 adds a hard turn-count backstop.

---

## 5. `historyBudget()` at Runtime

With the corrected `activeContextWindowSize = 8000` (FLAGSHIP post-safeTokenCount):

```
historyBudget(8000) = 8000 − 1024 (response reserve) − 1400 (system overhead) = 5576 tokens
```

At `proactiveReset` threshold (75%): `estimatedTokensUsed > 6000`  
Available window for history replay: `5576` tokens ≈ ~16,700 chars ≈ ~40–80 typical turns

On MID_RANGE (default `contextWindowSize = 2000`):
```
historyBudget(2000) = 2000 − 1024 − 1400 = −424 → coerceAtLeast(0) = 0
```
**MID_RANGE devices have zero history budget.** Every history replay returns empty;
the model has no conversation context. This is a pre-existing usability issue but
not a direct OOM driver (no KV growth from history).

---

## 6. Token Counting Accuracy

`ContextWindowManager.estimateTokens()` uses 3 chars/token (conservative heuristic).
Gemma 4's SentencePiece tokenizer averages ~4 chars/token for English prose,
meaning the heuristic *underestimates* by ~25%. This is intentional — it biases
toward keeping more history rather than aggressive resets. With thinking tokens now
counted, the estimation will be closer to reality.

---

## 7. Changes in This PR

| File | Change |
|---|---|
| `core/inference/…/InferenceEngine.kt` | Added `resolvedMaxTokens: StateFlow<Int>` |
| `core/inference/…/LiteRtInferenceEngine.kt` | Added `_resolvedMaxTokens` flow; emits `resolvedConfig.maxTokens` after init; resets to 0 on shutdown |
| `feature/chat/…/ChatViewModel.kt` | Sync `activeContextWindowSize` from `resolvedMaxTokens` after init; add thinking token cost to `estimatedTokensUsed`; add `KernelOOM` logcat at proactiveReset evaluation |

---

## 8. Recommended Next Steps

1. **Merge this PR** (logging + thinking token fix + resolvedMaxTokens alignment)
2. **Merge PR #558** (turn-count backstop — complementary, not redundant)
3. **Fix MID_RANGE history budget**: `SYSTEM_OVERHEAD + RESPONSE_RESERVE = 2424 > 2000`.
   Consider reducing `RESPONSE_RESERVE` to 512 for MID_RANGE or increasing the
   minimum `contextWindowSize` for MID_RANGE to 3072.
4. **Run Sprint 5 harness** with `adb logcat -s KernelOOM` piped to a file to
   confirm `estimatedTokensUsed` stays below threshold across the 141–172 test range.
5. **Consider exposing actual thinking token count** from `GenerationResult.Complete`
   (add `thinkingTokenEstimate: Int` field) for more precise accounting — currently
   estimated via `estimateTokens(thinking ?: "")` which is sufficient but not exact.
