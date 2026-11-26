# #2079 offline "Did you mean" sweep: aggregate results

Aggregates only. Per-clip rows, transcripts, and clip ids are private and not in this repo.

Input: 16 gold + 20 non-gold owner lines, each read as M and L (72 rows). Greedy-decoder text.
Gazetteer: 167 rewritable NZ names. Competitor variants:
V0 NZ only; V1 + 144 impostor names; V2 + impostors + 16,806 AU PPL names (GeoNames); V3 + 4,167 LINZ locality/suburb names.

Gold rank among NZ names (no competitors): top-1 12/16, top-3 13/16 (M and L). Unchanged by the rule below.

## Chosen config

V2, threshold 0.30 (normalised Levenshtein), margin 0.05. Rule as of the spelling-tie change (below).

| size | gold accepted (of 16) | wrong-place prompts on gold | non-gold prompts (of 20) |
|------|----------------------|-----------------------------|--------------------------|
| M    | 6                    | 0                           | 0                        |
| L    | 6                    | 0                           | 0                        |

Previous rule (sound-first, competitors block on any distance): M 5 / 0 / 0, L 5 / 0 / 0.

## Rule change

Three changes in `PlaceSlotMatcher.suggest`. NZ candidates are scored exactly as before.

1. A competitor (non-NZ) takes part in ranking and blocking only if its spelling distance is within the threshold. A competitor that matches on sound alone (for example `Baddow` for `Porirwa`, sound distance 0, spelling far) neither wins nor blocks.
2. Any exact match (NZ or competitor) gives no prompt.
3. If the top two are within 0.02 of each other on the combined distance, the margin is measured on spelling distance instead.

Why not the proposed union shortlist: under the margin rule the runner-up minimum is always in the top N by distance, so a union of top-N by sound and top-N by spelling does not change the decision. What lets `Porirua` win for `Porirwa` is change 1, not the shortlist.

Offline check of the rules (Python port, reproduced the previous 5/0/0 baseline first; the Kotlin owner test gives the same 6/0/0 for the chosen rule):

| variant | M gold ok / wrong / none | L gold ok / wrong / none | non-gold prompts (M, L) |
|---|---|---|---|
| previous rule | 5 / 0 / 11 | 5 / 0 / 11 | 0, 0 |
| sound tie-break + spelling margin (union shortlist) | 5 / 0 / 11 | 5 / 0 / 11 | 0, 0 |
| spelling-only decision | 3 / 0 / 13 | 3 / 0 / 13 | 0, 0 |
| spelling margin on the sound-chosen NZ candidate | 3 / 0 / 13 | 3 / 0 / 13 | 0, 0 |
| competitor filter (chosen), no tie rule | 6 / 0 / 10 | 6 / 0 / 10 | 0, 0 |
| competitor filter + spelling tie rule (chosen) | 6 / 0 / 10 | 6 / 0 / 10 | 0, 0 |

The spelling-only and spelling-margin variants lose sound-driven gold recall, so they were not used.

## One-edit typo sweep (all 167 NZ names)

Every one-letter deletion, adjacent swap and substitution of each NZ name, run through the production
suggester with the bundled assets (16,938 competitor names). Test: `PlaceSlotMatcherTypoSweepTest`
(env-gated, `PLACE_DYM_TYPO_SWEEP=1`).

| typo kind | typos | right place | wrong place | nothing |
|---|---|---|---|---|
| all, before | 38,269 | 19,697 (51.5%) | 68 (0.2%) | 18,504 (48.4%) |
| all, after  | 38,269 | 32,624 (85.3%) | 1,106 (2.9%) | 4,539 (11.9%) |
| delete, before / after | 1,402 | 659 / 1,143 | 1 / 26 | 742 / 233 |
| substitute, before / after | 35,675 | 18,644 / 30,559 | 64 / 1,070 | 16,967 / 4,046 |
| swap, before / after | 1,192 | 394 / 922 | 3 / 10 | 795 / 260 |

Trade-off: the change recovers about 12,900 typos that were silent, at the cost of about 1,040 more
wrong-place prompts. The owner-line gate (0 wrong-place, 0 non-gold) holds.

Breakdown of the 1,106 wrong-place typos (aggregates; letters counted with spaces removed):

- Sound-key winner: in 608 (55%) the typo's sound key equals the wrong NZ name's sound key while the
  target's sound key differs, so the wrong name wins at sound distance 0. Its spelling distance from the
  typo is above 0.30 in 1,069 cases.
- Target spelling within 0.30 in 1,103 cases. The typo is never spelling-closer to the wrong name than to
  the target (8 cases tie on spelling distance).
- Near pairs: 0 wrong names one edit from the target, 25 two edits away, 1,081 three or more. Shared
  prefix of 4+ letters in 32. No Waikanae/Waikane-style pattern dominates.
- Target length: 49 at 5 letters or fewer, 453 at 6 to 8, 192 at 9 to 11, 412 at 12 or more.
- Edit kind: 1,070 substitute, 26 delete, 10 swap. Every wrong suggestion was an NZ name; no competitor was prompted.

Tweak tested, not applied: require the winning NZ name to be spelling-plausible (spelling within 0.30), as
already required of competitors. Typo sweep: wrong 1,106 to 45, right 85.3% to 88.1%. Owner gate: wrong 0 and
non-gold 0 hold, but gold ok falls 6 to 3 for both M and L, below the previous 5. Likely cause [INFERENCE]: the
lost gold cases rest on sound-only wins with spelling above 0.30, the same signal behind the typo wins, so no spelling
threshold separates them. Owner decision (27 Nov): keep the new rule, so the tweak is not in the code.

## Caveats

- Tuning set = the 36 owner lines. This is in-sample; the held-out check is the Honor 10×3 M/L acceptance on the owner's device (not run by the agent).
- Recall is still low (6/16). The "Did you mean" prompt is a stopgap, not a fix for the gold miss rate.
- Greedy-decoder text only; the ASR path on device may differ.
- The typo sweep uses one-edit misspellings of written names, not recorded speech.
