# STT Normalisation Triage — PR #1070

## Work Stream A: New normaliser patterns (this PR)

### A.1 — Add kumara mishear variants

Observed across 3 Samsung device tests: "kumara" is consistently misheard. Add to `TranscriptNormaliser.KIWI_PHONETIC_REPLACEMENTS`:

| Observed | Regex | Replacement |
|---|---|---|
| kumada | `\bkumada\b` | kumara |
| cumbra | `\bcumbra\b` | kumara |
| cornbra | `\bcornbra\b` | kumara |

Word-boundary, IGNORE_CASE. False positive risk: low (nonsense words, no common English collisions).

### A.2 — Add wharepaku "fatty pucker" variant

| Observed | Regex | Replacement |
|---|---|---|
| fatty Pucker | `\bfatty\s+pucker\b` | wharepaku |
| (defensive) | `\bfattypucker\b` | wharepaku |

Word-boundary, IGNORE_CASE. False positive risk: very low.

### A.3 — Tests

Add 5 test methods to `TranscriptNormaliserTest.kt`:
- `kumada becomes kumara`
- `cumbra becomes kumara`
- `cornbra becomes kumara`
- `fatty pucker becomes wharepaku`
- `fattypucker becomes wharepaku`

### A.4 — Verification

```bash
./gradlew :core:voice:testDebugUnitTest  # 24/24
```

---

## Work Stream B: QIR `get_date_diff` regex overmatch (this PR)

### B.1 — Root cause (oracle corrected)

**Was:** wrongly diagnosed as MiniLM classifier false positive.

**Is:** The QIR regex layer. Pattern 1 at `QuickIntentRouter.kt:3120-3124`:

```regex
(?:how\s+(?:many\s+(?:days?|weeks?|months?)\s+)?(?:long\s+)?(?:until|till|to|before))\s+(.+)
```

Both `many <unit>` and `long` are **optional**. Bare `how … to …` satisfies the pattern:
- `how to cook kumara` → `get_date_diff` with `target_date = "cook kumara"`
- `do you know how to cook kumara` → same (embedded `how to`)

Evidence:
1. MiniLM classifier asset (`intent_phrases.json`) has **no `get_date_diff` label** — impossible for classifier to route here
2. The regex is not start-anchored and allows bare `how to`

### B.2 — Fix

Require at least one of `many days/weeks/months` or `long` between `how` and the direction word.

**Before (buggy):**
```kotlin
Regex("""(?:how\s+(?:many\s+(?:days?|weeks?|months?)\s+)?(?:long\s+)?(?:until|till|to|before))\s+(.+)""", RegexOption.IGNORE_CASE)
```

**After (fixed):**
```kotlin
Regex("""(?:how\s+(?:many\s+(?:days?|weeks?|months?)(?:\s+long)?\s+|long\s+)(?:until|till|to|before))\s+(.+)""", RegexOption.IGNORE_CASE)
```

Preserves valid date-diff forms:
- `how many days to Christmas` ✓
- `how many weeks long to New Year` ✓
- `how long until my birthday` ✓
- `how many days before August 22` ✓

Rejects bare `how to`:
- `how to cook kumara` ✗
- `do you know how to cook kumara` ✗

### B.3 — Regression tests

Add to `QuickIntentRouterTest.kt`:
- `how to cook kumara` → fallthrough (not `get_date_diff`)
- `do you know how to cook kumara` → fallthrough

### B.4 — Verification

```bash
./gradlew :core:skills:testDebugUnitTest  # all pass + new regressions
```

---

## Work Stream C: LLM systemic issues (separate issues)

**Oracle note:** The cultural memory corpus for Kumara, Wharepaku, Chocka, and Taniwha
already exists in `core/inference/src/main/assets/nz_truth_memories.json:2333-2388`.
The problem is retrieval/tool-selection, not missing data.

### C.1 — Wikipedia over-eager tool use + cultural memory under-used

Title: "LLM over-uses query_wikipedia instead of checking cultural memory"
Body: Observed across 7/10 device test scenarios. Model calls Wikipedia even for:
- Terms in cultural memory (wharepaku, chocka, taniwha)
- Single-word queries where conversational response is better
- Queries where Wikipedia returns wrong article (Māori language for wharepaku)

Root cause is prompt/tool-selection: model should search memory before Wikipedia for NZ domain terms.

### C.2 — query_wikipedia wrong article

Title: "query_wikipedia returns Māori language article instead of wharepaku"

### C.3 — Cooking query → meal planner suggestion

Title: "Model should suggest meal planner when user asks about cooking"

---

## Dependency graph

```
A (normaliser patterns) ──── this PR
B (QIR regex fix) ────────── this PR (one-line change, same commit)
C (LLM systemic) ─────────── separate issues, independent
```

## Risk assessment

- **A**: Low risk. Additive patterns only. Word-boundary + IGNORE_CASE are well-tested.
- **B**: Low risk. One regex character-group change. Valid date-diff forms preserved; only bare `how to` rejected.
- **C**: Medium risk. System prompt changes affect all interactions. Separate issues, no rush.
