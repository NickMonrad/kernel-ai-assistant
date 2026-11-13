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
| Blank response guard | Verify ordinary 0-token output → retry without RAG → fallback; successful non-empty DirectReply with blank model output is returned without retry |
| Backend fallback change | Verify NPU→GPU→CPU fallback chain with forced failure at each tier |
| Structured output change | Test `generateStructuredOnce` with varied schema shapes |

## When Manual On-Device Testing Is Required

- **Required** for: model warmup timing validation, cross-command contamination
  validation (warmup timeouts are a known S21-specific intermittent issue when
  that device is in use)
- **Not required** for: pure unit-testable logic changes (context budget math,
  schema parsing), build/config changes

## Device Requirements

| Device | When |
|--------|------|
| S23U (SD 8 Gen 2) | Primary — default for all LiteRT/model evidence |
| S21 (Exynos) | Extra capacity only — second-SoC (Exynos/Mali warmup and GPU) comparison when the change is device-sensitive; see `.docs/agents/risk-based-evidence-policy.md` |

## Common Regressions to Check

- `conversation.reset()` clears KV cache (not just state variables)
- Cross-command contamination: 3+ independent intents with distinct parameters
  all return correct intent with correct parameters
- Blank-response guard: ordinary 0-token output retries without RAG, then falls back; successful non-empty DirectReply is surfaced without a blank-response retry
- Structured-output deadlock: `generateStructuredOnce` does not hang on malformed
  schema or empty response
- Context budget scaling: budget is proportional to window size, not absolute
  (see #1093)
- Model warmup: completes within expected window (S21 has a known intermittent timeout)
- `safeTokenCount()`: powers-of-2 token counts nudged ~2.4% to avoid Adreno
  reshape buffer bug

## Suggested Commands

```bash
# Run inference unit tests
./gradlew :core:inference:testDebugUnitTest

# Run session isolation smoke test
python3 scripts/adb_skill_test.py --tags safe_smoke

# Check for blank responses
python3 scripts/adb_skill_test.py --tags deterministic_core
```
