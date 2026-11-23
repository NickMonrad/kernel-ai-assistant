# #2093 owner recordings: pre-registration v3, amendment 1

Committed before any owner audio exists. The owner's private folder holds no audio when this is committed; the line list is text-only. Line texts, audio and per-line results are private and not in git.

## What changed

The owner's recording script gained part 2 (R31–R50). Its line list now has 50 rows, four tab columns (id, text, glossary class, class). Part 1 is unchanged.

- Line list sha256: `b9af729bd4be6ac66d82530012aa6f5413a1e06a0418c272880679fb8d906696` (50 rows). This supersedes the 30-line hash `efc21aba0403d64d9efa2faa8f1524864aa43434fc9b32e64f37b0e04966a08d` (v2).
- Rows 1–30 are byte-identical to the v2 script: `head -n 30` hashes to `efc21aba…08d`. R31–R50 is a pure append.
- The v2 hash remains the record for part 1. The v3 record is the 50-row file.

## Per-class counts (50 lines)

| Group (glossary column) | Class | n |
|---|---|---|
| glossary | navigate | 6 |
| glossary | weather | 7 |
| glossary | bare_name | 2 |
| non_glossary | navigate | 7 |
| non_glossary | weather | 7 |
| non_glossary | bare_name | 1 |
| false_boost | au_town | 4 |
| false_boost | foreign_city | 2 |
| false_boost | poi | 4 |
| false_boost | address | 1 |
| false_boost | nz_suburb_competitor | 2 |
| false_boost | nz_correct_place | 2 |
| false_boost | gate_trap | 4 |
| false_boost | no_place | 1 |
| | **total** | **50** |

Gold lines (glossary + non_glossary): 30, unchanged from v2. False-boost lines: 20.

## Mapping to v3 criteria (fixed before audio)

The v3 pass criteria are unchanged. This amendment maps owner classes to them.

| v3 criterion | Owner classes | n | Evaluable? |
|---|---|---|---|
| 1. Gold substitutions = 0 | glossary + non_glossary (navigate, weather, bare_name) | 30 | yes |
| 2. Non-identity rewrites = 0 on foreign city, foreign homograph, POI, contact/address, suburb/locality, AU competitor city, gate trap | foreign_city 2; foreign homograph 0; poi 4; address 1; nz_suburb_competitor 2; au_town 4; gate_trap 4 | 17 (+0) | yes, except foreign homograph (no lines) |
| 3. Competitor-place rewrites = 0 | au_town 4; nz_suburb_competitor 2 | 6 | yes |
| 4. NZ already-correct: non-identity rewrites = 0 | nz_correct_place 2 | 2 | yes |
| 5. Gate traps: non-identity rewrites = 0 | gate_trap 4; no_place 1 | 5 | yes |
| 6. Scoring p95 | none (PC timing, not owner audio) | 0 | no; S21 not tested |

Notes on the mapping:
- `no_place` (a weather line with no place) is mapped to criterion 5. This is a classification fixed before audio. Any non-identity rewrite on it counts as a gate-trap failure.
- `au_town` and `nz_suburb_competitor` count under both criterion 2 and criterion 3. `gate_trap` counts under criteria 2 and 5. Criteria are not summed.
- Foreign homograph (criterion 2) has no owner lines. Its sub-criterion is "not evaluated", not a pass.

## Small-n limits (stated before audio)

For a class with zero non-identity rewrites, the one-sided 97.5% upper bound on the rate is 1 − 0.025^(1/n):
- n = 1: 97.5% (address, no_place)
- n = 2: 84.2% (foreign_city, nz_suburb_competitor, nz_correct_place)
- n = 4: 60.2% (au_town, poi, gate_trap)

A single zero-count line gives almost no evidence about its class. The owner's zero-count results on those classes can show failure but cannot show a pass for them. This is recorded as a limit, not corrected by changing criteria.

## Unchanged

Identity rule, `match` scoring, β primary (β 5, post-hoc from v2), margin, gate v2, outcomes, veto order, and the recording protocol (one take per line, logged re-takes) are unchanged from v3. Frozen code and asset hashes are as in `HELDOUT-PREREG-v3.md`.

## Not claimed

- No owner audio has been received. Nothing in this amendment is a result.
- The post-hoc v3 diagnostic on the seen synthetic set is not validation and is unchanged by this amendment.
