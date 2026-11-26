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

## Review corrections (26 Nov 2026; aggregates; supersede the sections above where they differ)

Set by an independent review of the amendment 6 write-up. No code, oracle behaviour or frozen hash changed; the oracle stays `8e616943…`. Reported in full in `README.md` (Tuning section).

1. **Distinct clips, not only cell-rows.** The 6 gold substitutions are **2 noisy size-L clips** from 2 lines (each in all three slots). The 6 false-boost rewrites are **1 noisy clip** (NZ already-correct class; both sizes, all slots). Cell counts overstate the number of independent failures.
2. **Owner bounds withdrawn in pooled form.** The 94 and 114 are correlated cell-lines: 16 gold lines (about 10 distinct places) and 20 non-gold lines, each across six cells. The pooled "0/94, upper 3.9%" and "0/114, upper 3.3%", and the Wilson 30.1–49.5% on 37/94, are withdrawn. Per cell: gold substitution 0/16 or 0/15 (upper 19.4% or 20.4%); non-gold rewrite 0/20 or 0/17 (upper 16.1% or 18.4%). Per line (union over cells): 0 of 16 gold lines with a substitution (upper 19.4%); 0 of 20 non-gold lines with a rewrite (upper 16.1%).
3. **The kept rule fails both hard constraints on the tuning set** (6 substitutions, 6 rewrites). Keeping it is the pre-registered fallback under amendment 5. It is not a validation. The owner re-score cannot change it (same rule). The fresh recording named in amendment 5 as the clean check has not been done.
4. **Recall, restated.** 11 of the 80 configs are at least as good as the current rule on substitutions and rewrites and have more correct accepts (best: β 4, margin 0.5, boundary off, competitor runner-ups ignored; 320/672 correct; 0 substitutions; 6 rewrites). All 11 keep the same single-clip rewrite, so none meets the both-zero rule; nine have 0 substitutions. Of the 389 baseline misses, 309 are competitor abstains (202) or impostor tops (107), which margin and boundary settings do not change. Large gains need a different rule, with its own pre-registration.
5. **Reporting that amendment 5 required, now in the README:** exclusion counts per source (owner 7/144; gap 18/82) and per class; the full 80-config table; Wilson 95% and per-class bounds for the tuning baseline.
6. **Bare-name coverage.** 13 of 16 bare-name clips were excluded for intelligibility, leaving 3 scored. The tuning set says almost nothing about bare-name lines, which is the class that failed on the synthetic gap set.
7. **Wording only.** `tune_sweep.py` describes a substitution as any accept whose top is not gold; the code counts changed text only. No config differs under either definition.
8. **Files.** Amendments 5 and 6 moved from the repository root into `tools/asr4all-bias-oracle/`, beside amendments 1–4. The README example now uses `nz_geonames_places.json` (`fd1b0904…`), not the LINZ-only list.
