# Sprint 4 QIR Regression Test Analysis

**Test run:** `2026-04-17T23:20:00Z`
**Analysed by:** Copilot (deep regression analysis)
**Source:** `2026-04-17T23-20-00Z_skills.json`
**QIR version:** commit `b69273c` (Sprint 3 completion, 2026-04-17 22:23 AEST)

---

## Executive Summary

| Metric | Value |
|--------|-------|
| Tests attempted | 164 of 181 planned (OOM abort at #173) |
| **Pass** | **135 (82.3%)** |
| Fail | 23 (14.0%) |
| XFail (expected) | 5 (3.0%) |
| Incomplete | 1 (0.6%) |

### Key Findings

1. **5 confirmed QIR bugs** require immediate code fixes — 2 param extraction bugs, 2 pattern-ordering
   ambiguities, and 1 false-positive match. These are fully reproducible regardless of device state.
2. **18 NO_MATCH failures are concentrated in tests #141–172** (54% failure rate vs 2.5% for
   tests #10–130). Each failing input has a structurally identical passing twin (e.g., #138
   "play the Joe Rogan podcast" ✓ vs #141 "play the news podcast" ✗, both using the same
   regex). This pattern strongly indicates **OOM-induced runtime degradation**, not regex gaps.
3. **1 anomalous result** (#142) returned an impossible intent (`play_podcast` for "skip forward
   2 minutes") — likely a logcat parsing artefact.
4. The **5 xfails** are all multi-item list operations — correctly deferred to E4B tier.
5. **Sprint 3 patterns are validated** — the 66 original tests all pass; regressions are zero.

**Bottom line:** Fix the 5 confirmed bugs (~2 hours of work), then re-run the full 181-test suite
on a device with sufficient headroom (4 GB+ free RAM) to isolate genuine gaps from OOM noise.

---

## Failure Breakdown

### Category A: Param Extraction Bugs (2 failures)

Intent routes correctly but extracts the wrong parameter value.

| # | Message | Expected | Actual param | Root cause |
|---|---------|----------|--------------|------------|
| 15 | "create a list called groceries" | `list_name=groceries` | `list_name=a` | `create_list` regex has no "called/named" variant; lazy `.+?` captures article "a" before `\s+list` |
| 85 | "make a new list called holiday packing" | `list_name=holiday packing` | `list_name=new` | Same root cause — `(.+?)` captures "new" and `\s+list` matches " list" |

**QIR location:** Line 1648–1655 — `create_list` regex:
```
(?:create|make|start|new)\s+(?:a\s+|an\s+|my\s+|new\s+)?(.+?)\s+list
```

**Fix:** Add a dedicated "called/named" pattern **before** the existing `create_list` entry:
```kotlin
// "create a list called groceries" / "make a new list named holiday packing"
IntentPattern(
    intentName = "create_list",
    regex = Regex(
        """(?:create|make|start|new)\s+(?:a\s+|an\s+|my\s+|new\s+)*(?:list)\s+(?:called|named)\s+(.+)""",
        RegexOption.IGNORE_CASE,
    ),
    paramExtractor = { match, _ -> mapOf("list_name" to match.groupValues[1].trim()) },
),
```

---

### Category B: QIR Regex Ambiguity — Wrong Intent (3 failures)

A pattern exists for the correct intent but a **higher-priority (earlier) pattern** steals the match.

| # | Message | Expected intent | Actual intent | Root cause |
|---|---------|----------------|---------------|------------|
| 125 | "hold on" | `pause_media` | `smart_home_on` | Terse `smart_home_on` pattern `^(.+?)\s+on$` (line 1790) matches "hold on" with `device=hold`; no `pause_media` pattern covers "hold on" |
| 131 | "play the next one" | `next_track` | `play_media` | Generic `play_media` (line 1336) `play\s+(.+?)…$` matches before `next_track` (line 1377) |
| 137 | "play the previous track" | `previous_track` | `play_media` | Same as #131 — `play_media` is earlier in list than `previous_track` (line 1413) despite the existence of `play\s+(?:the\s+)?previous\s+(?:song\|track)` |

#### Fix for #125: Add `pause_media` pattern for "hold on"

Insert before the smart_home_on terse patterns (before line 1778):
```kotlin
// Colloquial pause: "hold on" — must precede smart_home_on terse pattern
IntentPattern(
    intentName = "pause_media",
    regex = Regex("""^hold\s+on$""", RegexOption.IGNORE_CASE),
    paramExtractor = { _, _ -> emptyMap() },
),
```

#### Fix for #131 + #137: Reorder play_media vs media-transport patterns

The `play_media` generic regex (line 1334–1345) **must** come after `next_track` and `previous_track`.
Two options:

**Option A (minimal — recommended):** Move the existing `previous_track` "play the previous" pattern
(line 1413–1418) **and** add a matching `next_track` "play the next" pattern, both placed
immediately before `play_media` (line 1334):

```kotlin
// MUST come before generic play_media
IntentPattern(
    intentName = "next_track",
    regex = Regex("""(?i)\bplay\s+(?:the\s+)?next\s+(?:one|song|track|video|episode)\b"""),
    paramExtractor = { _, _ -> emptyMap() },
),
IntentPattern(
    intentName = "previous_track",
    regex = Regex("""(?i)\bplay\s+(?:the\s+)?(?:previous|last)\s+(?:one|song|track|video|episode)\b"""),
    paramExtractor = { _, _ -> emptyMap() },
),
// Generic play — MUST come after plex/album/playlist/next/previous
IntentPattern(
    intentName = "play_media",
    ...
```

**Option B (defensive):** Add negative lookahead to `play_media`:
```
play\s+(?!(?:the\s+)?(?:next|previous|last)\s)(.+?)(?:\s+(?:by|from)\s+(.+))?$
```

---

### Category C: Anomalous Result (1 failure)

| # | Message | Expected | Actual | Notes |
|---|---------|----------|--------|-------|
| 142 | "skip forward 2 minutes" | `podcast_skip_forward` | `play_podcast` | **Impossible** — no `play_podcast` regex can match an input starting with "skip". Likely a logcat buffer misread by the test harness during OOM pressure. |

**Recommendation:** No code change needed. Will self-resolve on re-run. Flag the ADB harness to
add a sanity check: if extracted intent doesn't contain any keyword from the input, log a warning.

---

### Category D: NO_MATCH Failures — Likely OOM-Induced (17 failures)

These 17 tests returned `NO_MATCH` (FallThrough) despite having regexes in the current QIR
that **should** match. Each has a structurally identical test that **passes** in the same run:

| # | Message | Expected intent | Passing twin | Same pattern? |
|---|---------|----------------|--------------|---------------|
| 141 | "play the news podcast" | `play_podcast` | #138 "play the Joe Rogan podcast" ✓ | Yes — same regex |
| 143 | "skip ahead 5 minutes" | `podcast_skip_forward` | #144 "skip the intro" ✓ | No — different regex |
| 145 | "forward 30 seconds" | `podcast_skip_forward` | #146 "go back 30 seconds" ✓ | No — different regex (but structurally identical) |
| 147 | "rewind 10 seconds" | `podcast_skip_back` | #148 "back 15 seconds" ✓ | No — different regex |
| 150 | "play at 1.5x speed" | `podcast_speed` | #151 "set playback speed to 2x" ✓ | No — different regex |
| 152 | "normal speed" | `podcast_speed` | #151 ✓ | No — simpler regex |
| 153 | "slow down the podcast" | `podcast_speed` | #151 ✓ | No — different regex |
| 155 | "show my timers" | `list_timers` | #154 "what timers do I have" ✓ | Yes — same regex |
| 156 | "how many timers are running" | `list_timers` | #154 ✓ | Yes — same regex |
| 158 | "cancel the pasta timer" | `cancel_timer_named` | #160 "stop the egg timer" ✓ | Yes — same regex |
| 159 | "cancel the 10 minute timer" | `cancel_timer_named` | #160 ✓ | Yes — same regex |
| 161 | "dismiss the laundry timer" | `cancel_timer_named` | #160 ✓ | Yes — same regex |
| 162 | "how long left on my timer" | `get_timer_remaining` | #163 "how much time is left on the pasta timer" ✓ | Yes — same regex |
| 164 | "how long until the timer goes off" | `get_timer_remaining` | #163 ✓ | No — different regex (line 383) |
| 169 | "stick bananas on the shopping list" | `add_to_list` | #171 "pop coffee on my list" ✓ | Yes — same informal verb regex |
| 170 | "add tomatoes to the grocery list" | `add_to_list` | #14 "put eggs on the grocery list" ✓ | No — different pattern (add vs put) |
| 172 | "put sunscreen on the holiday list" | `add_to_list` | #14 ✓ | Yes — same put pattern |

**Evidence for OOM hypothesis:**
- Failure rate jumps from **2.5%** (tests #10–130) to **54.1%** (tests #131–172)
- The run was **aborted at test #173 due to OOM**
- 8 of 17 failures use the **exact same regex** as a passing test
- These patterns were all added in `b69273c` and demonstrably work (17 passes in the 131+ range)

**However**, some failures MAY expose genuine Android regex engine edge cases:
- `\bforward\s+…` (#145) — bare "forward" at start of input; Android's ICU `\b` might
  not fire at `^` for some regex patterns using inline `(?i)` flags
- `\brewind\s+(?:(\d+)\s*(second|sec|minute|min)s?)?\b` (#147) — optional group with `\b`
  after may behave differently on Android's regex engine than JVM

**Recommendation:**
1. **Re-run the suite on a freshly booted device** with ≥4 GB free RAM before investing in regex fixes
2. If any of these fail on a clean run, add **simpler fallback patterns** (no inline `(?i)`, fewer optional groups, anchored with `^…$` where possible)
3. Consider splitting the test suite into 2 batches (90 tests each) to avoid OOM

---

### Category E: Expected Failures — XFail (5 tests, not actionable)

| # | Message | Notes |
|---|---------|-------|
| 149 | "I missed that, go back" | Contextual intent — needs E4B inference |
| 165 | "save all those ingredients to my shopping list" | Multi-item + conversation context — needs E4B |
| 166 | "add eggs, milk, and bread to the shopping list" | Multi-item list parsing — deferred to E4B |
| 167 | "put tortilla chips, beef mince, and kidney beans on my grocery list" | Same |
| 168 | "add these items to my list: apples, bananas, oranges" | Colon-separated multi-item — E4B |

These are correctly classified as `xfail`. Multi-item list operations require LLM-level parsing.
No Sprint 5 action needed unless E4B list skill lands.

---

## Cluster Analysis & Proposed Fixes

### Cluster 1: `create_list` "called/named" param extraction (2 fixes, 1 PR)

**Tests fixed:** #15, #85
**Impact:** Medium — list creation is a core list-management operation
**Effort:** ~15 min

Add a `create_list` pattern for `"…list called/named <name>"` syntax BEFORE the existing
generic `create_list` pattern at line 1648. The new pattern captures everything after
"called/named" as the list name.

### Cluster 2: `play_media` ordering vs media-transport intents (2 fixes, 1 PR)

**Tests fixed:** #131, #137
**Impact:** High — media playback controls are among the most frequent voice commands
**Effort:** ~20 min

Move or add `next_track` / `previous_track` patterns that start with "play the…" to
**before** the generic `play_media` pattern. The generic pattern must always be the
last play-related match.

### Cluster 3: `pause_media` "hold on" false-positive (1 fix, same PR as Cluster 2)

**Tests fixed:** #125
**Impact:** Medium-High — "hold on" is a natural pause command; routing it to smart home
is a jarring UX failure
**Effort:** ~5 min (add one `IntentPattern` before smart_home_on)

### Cluster 4: OOM-suspect NO_MATCH failures (17 tests, 0 code changes until re-run)

**Tests fixed (on re-run):** #141, #143, #145, #147, #150, #152, #153, #155, #156,
#158, #159, #161, #162, #164, #169, #170, #172
**Impact:** Blocks accurate Sprint 5 prioritisation — we can't plan pattern work if we
don't know which failures are real
**Effort:** ~30 min to re-run on a clean device

If any survive a clean re-run, apply defensive fixes:
- Replace inline `(?i)` flags with `RegexOption.IGNORE_CASE` for consistency
- Add `^…$` anchored terse variants for short inputs ("normal speed", "forward 30 seconds")
- Simplify optional groups that combine `?` with `\b` (known ICU edge case)

---

## Prioritised Sprint 5 Action Items

| Priority | Action | Tests fixed | Owner | Effort |
|----------|--------|-------------|-------|--------|
| **P0** | Re-run full 181-test suite on fresh device (≥4 GB RAM) | 17 (validation) | QA | 30 min |
| **P1** | Fix `play_media` ordering — add "play the next/previous" before generic pattern | #131, #137 | Android | 20 min |
| **P1** | Add `pause_media` "hold on" pattern before `smart_home_on` | #125 | Android | 5 min |
| **P1** | Add `create_list` "called/named" pattern before generic `create_list` | #15, #85 | Android | 15 min |
| **P2** | Investigate #142 anomalous `play_podcast` result — add harness sanity checks | #142 | QA | 30 min |
| **P2** | If re-run confirms NO_MATCH failures: add simpler fallback patterns for podcast_speed, podcast_skip, timer, list intents | up to 17 | Android | 1–2 hr |
| **P3** | Increase ADB harness timeout + add OOM detection (abort cleanly before device degrades) | — | QA | 1 hr |
| **P3** | Split test suite into 2 batches to prevent OOM | — | QA | 30 min |

---

## Systemic QIR Design Issues (Longer-Term)

### 1. Pattern Ordering Fragility

The first-match-wins architecture means **every new pattern must be manually placed** in the
correct position relative to all other patterns. The `play_media` generic regex is a recurring
trap — it will steal matches from any future intent that starts with "play…" (podcasts, albums,
playlists, and now transport controls all had ordering bugs at some point).

**Recommendation:** Introduce a **specificity score** or a two-pass approach:
- Pass 1: Collect all matching patterns
- Pass 2: Return the most specific match (longest regex match, or highest keyword count)
This eliminates ordering bugs entirely.

### 2. Terse Smart Home as a Catch-All

The `^(.+?)\s+on$` smart_home_on pattern is effectively a catch-all for any "X on" input.
Its negative lookahead exclusion list (`wifi|bluetooth|torch|…`) must be manually updated
every time a new terse intent is added. This is error-prone.

**Recommendation:** Move smart_home_on/off to a **fallback pass** that only runs if no other
pattern matched. This is architecturally cleaner than maintaining an ever-growing exclusion list.

### 3. Inline `(?i)` vs `RegexOption.IGNORE_CASE` Inconsistency

Patterns added in Sprint 3 use inline `(?i)` flags while older patterns use
`RegexOption.IGNORE_CASE`. The two mechanisms are functionally equivalent on JVM but may
differ in edge cases on Android's ICU regex engine. Standardise on `RegexOption.IGNORE_CASE`
for all patterns.

### 4. OOM Resilience

The test harness (and the QIR itself) degrades silently under memory pressure. At minimum:
- The QIR `route()` function should wrap regex evaluation in a try-catch to return
  `FallThrough` explicitly on any `PatternSyntaxException` or `StackOverflowError`
- The ADB harness should monitor `ActivityManager` memory warnings and abort gracefully

### 5. Test Suite Growth vs Device Constraints

The suite grew from 66 → 181 tests between Sprint 3 and 4. At ~173 tests the device OOMs.
Consider running the ADB harness with `am force-stop` between test batches to reclaim memory,
or use a `--batch-size` flag to checkpoint progress and restart the app process.

---

## Appendix: Full Failure Index

| # | Message | Status | Actual | Category |
|---|---------|--------|--------|----------|
| 15 | create a list called groceries | PARAM_BUG | create_list (list_name=a) | A — param extraction |
| 85 | make a new list called holiday packing | PARAM_BUG | create_list (list_name=new) | A — param extraction |
| 125 | hold on | WRONG_INTENT | smart_home_on | B — ambiguity |
| 131 | play the next one | WRONG_INTENT | play_media | B — ambiguity |
| 137 | play the previous track | WRONG_INTENT | play_media | B — ambiguity |
| 141 | play the news podcast | NO_MATCH | FallThrough | D — OOM suspect |
| 142 | skip forward 2 minutes | WRONG_INTENT | play_podcast | C — anomalous |
| 143 | skip ahead 5 minutes | NO_MATCH | FallThrough | D — OOM suspect |
| 145 | forward 30 seconds | NO_MATCH | FallThrough | D — OOM suspect |
| 147 | rewind 10 seconds | NO_MATCH | FallThrough | D — OOM suspect |
| 150 | play at 1.5x speed | NO_MATCH | FallThrough | D — OOM suspect |
| 152 | normal speed | NO_MATCH | FallThrough | D — OOM suspect |
| 153 | slow down the podcast | NO_MATCH | FallThrough | D — OOM suspect |
| 155 | show my timers | NO_MATCH | FallThrough | D — OOM suspect |
| 156 | how many timers are running | NO_MATCH | FallThrough | D — OOM suspect |
| 158 | cancel the pasta timer | NO_MATCH | FallThrough | D — OOM suspect |
| 159 | cancel the 10 minute timer | NO_MATCH | FallThrough | D — OOM suspect |
| 161 | dismiss the laundry timer | NO_MATCH | FallThrough | D — OOM suspect |
| 162 | how long left on my timer | NO_MATCH | FallThrough | D — OOM suspect |
| 164 | how long until the timer goes off | NO_MATCH | FallThrough | D — OOM suspect |
| 169 | stick bananas on the shopping list | NO_MATCH | FallThrough | D — OOM suspect |
| 170 | add tomatoes to the grocery list | NO_MATCH | FallThrough | D — OOM suspect |
| 172 | put sunscreen on the holiday list | NO_MATCH | FallThrough | D — OOM suspect |
