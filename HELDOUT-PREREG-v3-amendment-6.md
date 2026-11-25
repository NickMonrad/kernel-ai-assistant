# HELDOUT pre-registration v3, amendment 6: tuning result and frozen decision (#2093)

Committed after the tuning sweep and before the owner real recordings are re-scored. Aggregates only.
Governed by amendment 5 (`HELDOUT-PREREG-v3-amendment-5.md`).

## Frozen artifacts

- Oracle: `tools/asr4all-bias-oracle/oracle.py`, sha256 `8e61694346ca…`. Amendment 5 froze `397329a5…`.
  The only change is keyword flags (`margin_min`, `boundary`, `competitor_margin`) with defaults equal to the
  previous behaviour (margin `MARGIN` = 1.0, boundary on, competitor runner-ups counted). No default changed.
- Sweep tool: `tools/asr4all-bias-oracle/tune_sweep.py`, sha256 `5061809d…`.
- Asset: `nz_geonames_places.json`, sha256 `fd1b0904…` (unchanged).
- Score code `score_v2.py`: sha256 `684896e9…` (unchanged).

## Tuning set (after intelligibility exclusion)

- 226 clips generated: 113 clean + 113 at 10 dB babble. Intelligibility (faster-whisper medium.en, word ratio,
  threshold 0.60): 25 excluded (12 clean, 13 noisy). 201 clips scored (101 clean, 100 noisy).
- Six cells (size M/L × slot full/hint/trigger); gold clips per cell are in the table below.

## Result: no feasible config

Grid: 80 configs (β {3,4,5,6,7} × margin {0.5,1.0,1.5,2.0} × boundary on/off × competitor runner-up counted/ignored).

- Configs with 0 gold substitutions: 34.
- Configs with 0 non-identity false-boost rewrites: 32.
- Configs with both: 0. The hard constraints are jointly infeasible on this grid.

Per amendment 5 fallback: **the current rule is kept**: β 5, margin 1.0, boundary on, competitor runner-ups counted.

Current rule over the six cells (aggregate):

| Cell | Gold clips | Gold correct | Gold substitutions | False-boost rewrites |
|---|---|---|---|---|
| M full | 122 | 51 | 0 | 1 of 79 |
| M hint | 122 | 52 | 0 | 1 of 79 |
| M trigger | 96 | 41 | 0 | 1 of 63 |
| L full | 122 | 49 | 2 | 1 of 79 |
| L hint | 122 | 53 | 2 | 1 of 79 |
| L trigger | 88 | 37 | 2 | 1 of 65 |

Totals: gold correct 283 of 672; substitutions 6; false-boost rewrites 6 of 444.

Gold misses over six cells (672): suggest margin 56, suggest word_count 12, suggest boundary 6, competitor
abstain 202, impostor top 107, substitution 6.

Gold-correct for the best feasible-on-substitution configs (reported, not chosen): e.g. β 4, margin 0.5,
boundary off, competitor ignored gave 0 substitutions, 6 false-boost rewrites and 320 of 672 correct. It has
the same false-boost count as the current rule, so it is not feasible under the rule and is not chosen.

## Consequence for the owner real set

The frozen config is the v3 rule. Step 4 re-scores the owner recordings once with the frozen oracle; the expected
result is the v3 β 5 owner result, unchanged, which also checks that the refactored oracle reproduces the
previous output. Owner recordings are reuse-disclosed (see amendment 5).
