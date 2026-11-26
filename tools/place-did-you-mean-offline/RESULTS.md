# #2079 offline "Did you mean" sweep: aggregate results

Aggregates only. Per-clip rows, transcripts, and clip ids are private and not in this repo.

Input: 16 gold + 20 non-gold owner lines, each read as M and L (72 rows). Greedy-decoder text.
Gazetteer: 167 rewritable NZ names. Competitor variants:
V0 NZ only; V1 + 144 impostor names; V2 + impostors + 16,806 AU PPL names (GeoNames); V3 + 4,167 LINZ locality/suburb names.

Gold rank among NZ names (no competitors): top-1 12/16, top-3 13/16 (M and L).

## Chosen config

V2, threshold 0.30 (normalised Levenshtein), margin 0.05 (runner-up minus top).

| size | gold accepted (of 16) | wrong-place prompts on gold | non-gold prompts (of 20) |
|------|----------------------|-----------------------------|--------------------------|
| M    | 5                    | 0                           | 0                        |
| L    | 5                    | 0                           | 0                        |

## Sweep summary (V2, gold accepted / wrong-place / non-gold prompts)

| threshold | margin 0.00 | margin 0.05 | margin 0.10 |
|-----------|-------------|-------------|-------------|
| 0.20      | 7-8 / 1-3 / 2 | 5 / 0 / 0 | 5 / 0 / 0 |
| 0.25      | 8-9 / 1-3 / 2 | 5 / 0 / 0 | 5 / 0 / 0 |
| 0.30      | 9-10 / 1-3 / 2 | 5 / 0 / 0 | 5 / 0 / 0 |
| 0.35      | 9-10 / 1-3 / 2-3 | 5 / 0 / 0 | 5 / 0 / 0 |

Ranges span M and L. Margin 0 gives recall but two non-gold prompts (one AU city, one gate-trap phrase) and up to three wrong-place prompts. Margin ≥0.05 removes all false prompts at a recall cost.

Variant effect at T=0.30, margin 0.05: V1, V2 and V3 agree on non-gold prompts (0). V3 (LINZ suburbs) drops gold acceptance to 0 at margin ≥0.10, so it is not used.

## Caveats

- Tuning set = the 36 owner lines. This is in-sample; the held-out check is the Honor 10×3 M/L acceptance on the owner's device (not run by the agent).
- Recall is low (5/16). The "Did you mean" prompt is a stopgap, not a fix for the gold miss rate.
- Greedy-decoder text only; the ASR path on device may differ.
