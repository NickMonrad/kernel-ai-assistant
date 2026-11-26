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
wrong-place prompts. The owner-line gate (0 wrong-place) holds; the typo sweep shows the rule does not
guarantee 0 wrong-place prompts in general. Wrong-place typos are mostly cases where another NZ name, or a
spelling-plausible competitor previously blocking it, is the nearest. The composition of the 1,106 was
not analysed. Reviewer decision: keep this rule (recall) or revert to the previous rule (precision, 68 wrong).

## Caveats

- Tuning set = the 36 owner lines. This is in-sample; the held-out check is the Honor 10×3 M/L acceptance on the owner's device (not run by the agent).
- Recall is still low (6/16). The "Did you mean" prompt is a stopgap, not a fix for the gold miss rate.
- Greedy-decoder text only; the ASR path on device may differ.
- The typo sweep uses one-edit misspellings of written names, not recorded speech.
