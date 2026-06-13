# Review Gate: LiteRT / Model Handling

## When This Gate Applies

This gate applies when the PR touches:
- LiteRT inference engine or session lifecycle
- Model loading, warmup, or KV cache management
- Context budget scaling
- Structured output generation (`generateStructuredOnce`)
- GPU/NPU/CPU backend selection or fallback
- `safeTokenCount` or reshape buffer workaround
- Conversation state or session isolation
- Blank response handling

## Smallest Useful Evidence Slice

| Change Type | Minimum Evidence |
|-------------|-----------------|
| Session isolation fix | 3 independent intents in sequence, each returns correct intent |
| Context budget change | Verify budget is applied proportionally, not fixed absolute |
| Blank response guard | Verify 0-token output → retry without RAG → fallback |
| Backend fallback change | Verify NPU→GPU→CPU fallback chain with forced failure at each tier |
| Structured output change | Test `generateStructuredOnce` with varied schema shapes |

## When Manual On-Device Testing Is Required

- **Required** for: model warmup timing on S21 (known intermittent timeout),
  cross-command contamination validation
- **Not required** for: pure unit-testable logic changes (context budget math,
  schema parsing), build/config changes

## Device Requirements

| Device | When |
|--------|------|
| S21 (Exynos) | Default — S21 has known warmup and Mali GPU issues that S23U does not expose |
| S23U (SD 8 Gen 2) | Required when the change is GPU backend, device-specific model loading,
  or S21 behaviour is ambiguous |

## Common Regressions to Check

- `conversation.reset()` clears KV cache (not just state variables)
- Cross-command contamination: 3+ independent intents with distinct parameters
  all return correct intent with correct parameters
- Blank-response guard: 0-token output triggers retry without RAG, then fallback
- Structured-output deadlock: `generateStructuredOnce` does not hang on malformed
  schema or empty response
- Context budget scaling: budget is proportional to window size, not absolute
  (see #1093)
- Model warmup on S21: completes within expected window
- `safeTokenCount()`: powers-of-2 token counts nudged ~2.4% to avoid Adreno
  reshape buffer bug

## Suggested Commands

```bash
# Run inference unit tests
./gradlew :core:inference:testDebugUnitTest

# Run session isolation smoke test
scripts/adb_skill_test.py --phase safe_smoke

# Check for blank responses
scripts/adb_skill_test.py --phase deterministic_core
```
