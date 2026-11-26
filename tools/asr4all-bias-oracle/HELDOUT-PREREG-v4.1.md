# HELDOUT-PREREG-v4.1 (#2093)

Amends the v4 pre-registration (`HELDOUT-PREREG-v4.md`, e397f530). Committed before any new scoring and before any
new audio is scored. v4 rules S4 and P1–P3 are unchanged. This file adds: rule S5 (a separately reported arm), a
SILENT start-boundary diagnostic (counted, not a rule), bounds computed in Python, and corrections to v4 figures.

## Frozen code

| File | sha256 | Status |
|---|---|---|
| `suggest_v4.py` | `629fe77ae15e6ae78868f487db908cbd54974b4510226a5afed18f0bf5ac0cb5` | unchanged; `decide` is S4 |
| `suggest_v41.py` | `73003a323bdc46c40705c58d26b7b7f5f7a72c687f71c960f4295224fdecd5c8` | new: S5, start-boundary diagnostic, bounds, report |
| `test_suggest_v41.py` | `d9b1302807a4cd70f8a9a26699950e15cf5597bccaa907bb309e81dbb11b4145` | synthetic tests only (17 tests with `test_suggest_v4`, pass) |
| `oracle.py`, `lexicon.py` | as in v4 | frozen; not modified |

The held-out scoring run uses `suggest_v41.py` as a full scan. The pruned path in `prefilter_eval.py` is a separate
equality check and is not used for any reported figure.

## Arms

- **S4** (v4 rule, unchanged): `suggest_v4.decide`.
- **S5** (reported arm, not a replacement): `suggest_v41.decide_s5`. Same as S4, except where the oracle gives
  `suggest` with reason `margin` or `boundary` and the top is a rewritable NZ name. Then:
  - if the folded top equals the heard text (identity): NONE;
  - otherwise: PROMPT(top).
  S5 is reported beside S4. P1–P3 are applied to each arm separately. A pass on one arm does not carry to the other.

## Criteria (v4, applied per arm, per size, primary slot hint)

- P1: gold wrong prompts = 0, pooled and per class.
- P2: gold SILENT with a name other than gold = 0. Note: an exact hear of a different NZ place gives SILENT, and
  counts as a P2 failure. This is conservative.
- P3: non-gold prompts = 0, pooled and per class.
- Precondition (v4): offered-right ≥ 50% at both sizes on the new set.

## Start-boundary diagnostic (not a rule change)

`suggest_v41.start_boundary_fail` mirrors the end check at the start of the matched span. For each SILENT, it records
whether the first matched character is inside a word (previous character is a letter, first matched character is not
a space). `decide` is not changed, so these SILENT outputs stay SILENT.

Earlier wording said a mid-word start is "impossible by construction". That claim is withdrawn. Mid-word starts are
counted and reported as a number.

## Reporting

- Non-gold SILENT counts per class, and how many have a mid-word start.
- Carrier split (trigger vs bare, from the new set's `carrier` column): gold and non-gold counts, Wilson 95% bounds.
- Per-town bound for gold: the 12 gold towns, each with three lines; a town counts as failed if any of its lines
  has a wrong prompt.
- Disclosure: gold towns are the fixed list in `make_lines_v4.py`. The script does not record whether they were
  chosen by hand or sampled; treat them as hand-picked for reporting.

## Bounds (Wilson 95%, zero events; computed by `suggest_v41.upper`)

| Set | n | Upper bound |
|---|---|---|
| gold, per town (towns with a wrong prompt, 0/12) | 12 | 24.25% |
| gold, per class | 30 | 11.35% |
| gold, pooled | 36 | 9.64% |
| non-gold, per class | 30 | 11.35% |
| non-gold, pooled | 180 | 2.09% |
| trigger carrier, pooled | 120 | 3.10% |
| bare carrier, non-gold per class | 10 | 27.75% |
| bare carrier, non-gold pooled | 60 | 6.02% |

Corrections to v4 figures: 0/30 → 11.35% (v4 said 11.3%); 0/180 → 2.09% (v4 said 2.0%); per-town 0/12 → 24.25%
(v4 said 24.2%, truncated).

## Margin field

The reported `margin` in `oracle.evaluate` can differ from the exact margin when the true margin is ≥ 1, because
pruned rows are not scored. Decisions use only `margin < 1.0`, so decisions are unchanged. The pruning check in
`prefilter_eval.py` compares decisions and the fields S4/S5 read, not the margin value.

## Not in this run

- Held-out audio: not scored under v4.1 at this commit.
- Pruned path: not used for any reported figure here.
- Owner and tune sets: may be scored under v4.1 only as a diagnostic, labelled as existing data (not blind).
