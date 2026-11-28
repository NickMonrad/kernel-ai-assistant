# #2093 real-recording test: pre-registration v3

Committed before any owner real recording exists. The owner's recordings are not made or received when this file is committed. Line texts, audio, transcripts, per-clip results and derived place lists are private and stay out of git.

v3 supersedes v2 for: the Australian competitor source, the homograph list source, the homograph flag name, and the asset. Everything else in v2 carries over unchanged: identity rule, `match` scoring, pass criteria, β primary, owner-recording protocol, and the owner script line set (committed in v2 with sha256 `efc21aba…08d`).

## Why v3 (rule changes)

1. **Composite Gazetteer of Australia dropped.** The published CGA terms are "© Commonwealth of Australia (Geoscience Australia) 2025", access Open, with no CC BY 4.0 statement and a blank licence field. The CGA is not shipped in any artefact. Its raw download stays local and unused.
2. **AU competitors from GeoNames (CC BY 4.0).** Feature class `P`, country `AU`, codes `PPL, PPLX, PPLL, PPLA, PPLA2, PPLC, PPLS, STLMT`. Excluded: `PPLQ` (abandoned), `PPLW` (destroyed), `PPLH` (historical), `PPLF` (farm). 19,202 competitor rows. Competitors only; never rewrite targets.
3. **Homograph veto names from GeoNames.** Two sources:
   - AU GeoNames competitor names, as in item 2.
   - Foreign names: GeoNames `cities15000` names whose country is neither NZ nor AU (31,854 distinct folded names).
   A rewritable NZ row whose folded name is in either set is flagged `homograph`. The veto applies to exact slot matches only, as in v2. The flag name `au_homograph` is renamed `homograph`, and the veto reason becomes `homograph`.
4. **Coverage limit (new).** `cities15000` covers cities of 15,000 population or more. Smaller foreign places are not covered by the homograph veto. This is a known gap, reported as such.
5. **Attribution.** Any published artefact that uses the GeoNames data must carry: "GeoNames (geonames.org), CC BY 4.0".

## Sources (public dump, no account)

Retrieved 2026-11-23T12:00Z from `https://download.geonames.org/export/dump/`. Hashes are of the downloaded files.

| File | URL | sha256 |
|---|---|---|
| `readme.txt` | `…/export/dump/readme.txt` | `b1957379b6c1242c700c98ac9a8aa0a09f56c3c0a50ee72175527005f48ef2c5` |
| `AU.zip` | `…/export/dump/AU.zip` | `99a68ce026567df9a72211d9df641a6efd71618264bdf74e91a649df53eeec9f` |
| `AU.txt` (extracted) | from `AU.zip` | `88f1997f5c2d3c0007835fd68039f05d0a4a37fed60fc3fbb7e4da936689ec55` |
| `cities15000.zip` | `…/export/dump/cities15000.zip` | `acdd89371e0ae9c4b38e120c7d3cfe7cd40bf0613bb6c5910cb1dd416c2065d6` |
| `cities15000.txt` (extracted) | from `cities15000.zip` | `e7515f9c49893c1f22f0e72c92c92905c7b725fdb138f9cff214c3a8d2b5cc6a` |

Licence: the readme states "This work is licensed under a Creative Commons Attribution 4.0 License, see https://creativecommons.org/licenses/by/4.0/" (readme lines 6–7).

## Frozen code and assets (sha256)

| File | sha256 |
|---|---|
| `oracle.py` (decision rule; `homograph` veto; `match` per beta row) | `397329a5926e919b67f1b8200e1000d41573fa5095e5b2d11b234051faf61195` |
| `build_place_candidates.py` (GeoNames AU competitors; `homograph` flag) | `291905ebab2c890644a1bf8afb69dec6f9e178a420556b271266bdd79f0b86aa` |
| `test_oracle.py` (25 tests) | `7edb0ebd069c7376bf8feea04bbb4e62b0b09fce60cf1cc102294375820e3efb` |
| `score_v2.py` (identity-aware scoring; unchanged in v3) | `684896e945c91b5f003c6449c253106ee9eb04433dc516977735445e62b6b348` |
| `lexicon.py` (unchanged) | `c2e74f265f9a8c4bb78f1494c824892e7b935798a2565c6e0ea82a568fc2ef25` |
| `data/glossary_places.txt` (unchanged) | `2b192b453498688b619dcc1f1b1a86c286d4ef9bc246f7c0a5d0bd716bc7c07f` |
| `nz_geonames_places.json` (private derived asset: 167 rewritable rows, 20,973 competitor rows, 35 `homograph` flags) | `fd1b09048e3a1a17704818f500f699a9a31c0004a941cae184270c2f70d3bceb` |

The LINZ NZ input and the glossary are unchanged from v1 and v2. The v2 asset (CGA-based, `577fbd43…`) is superseded and is not used for any owner-recording decision.

## Parameters (carried from v2)

- β primary: **5.0**. This was chosen after the v1 β 4 failure. It is a post-hoc choice and is not validated. Sensitivity: β 3.0 and 4.0, reported, not used for pass/fail.
- Margin 1.0, gate v2, boundary and word-count checks, outcomes, and veto order as in v2. Rule 2 is now `homograph` (GeoNames sources).

## Scoring and pass criteria (carried from v2; binding)

- Match, rewrite, identity, output-text and gold-substitution definitions as in v2 (`score_v2.py`).
- Criteria per slot and size, β 5:
  1. Gold substitutions = 0.
  2. Non-identity rewrites on foreign city, foreign homograph, POI, contact/address, suburb/locality, AU competitor city and gate-trap lines = 0.
  3. Competitor-place rewrites = 0.
  4. NZ already-correct places: non-identity rewrites = 0.
  5. Gate traps: non-identity rewrites = 0.
  6. Scoring p95 reported (PC). The S21 criterion is not tested here.

**Evaluability on the owner script.** The owner script (30 lines, classes glossary/non_glossary × navigate/weather/bare_name, v2 section "Line set") has no false-boost lines. Criteria 2–5 are therefore **not evaluated** on it and will be reported as "not evaluated", not as pass. Criterion 1 and the gold correct-output rates are evaluable. A false-boost amendment needs an owner decision and a committed amendment before any audio exists.

## Post-hoc diagnostic (seen synthetic set; not pre-registration evidence)

Run with v3 code and v3 asset on the seen held-out set (61 clips; the same run used by v2 post-hoc). β 5 only.

- Non-identity false-boost rewrites: 0 in every class, both sizes, all three slots.
- Gold substitutions: 0 in every cell.
- Versus v2 post-hoc at β 5:
  - Gold correct output, L size, `gold_bare`: 9/12 → 8/12 in the full and hint slots. The one loss is an `accept` on a line that becomes `abstain_competitor` with a competitor top, so the heard text is kept and is wrong. Whether that competitor is an AU GeoNames name was not checked.
  - Gold `navigate`: one line in hint L and one line in trigger L is now vetoed by the homograph list (`homograph`) instead of accepted. The output is the heard text, so the correct-output count is unchanged.
- Homograph flags: 35 NZ rows (AU GeoNames and foreign). The seen `foreign_city_homograph` line is an identity (no rewrite) in both sizes, as in v2.

This is a diagnostic only. The seen set cannot validate v3.

## Known risks (stated before audio)

- The larger AU competitor set (19,202 vs CGA 9,499) raises competitor abstentions. A narrower code set would be a rule change and would need v4.
- The homograph veto is exact-slot-only and cannot see a foreign place reached through a fuzzy match.
- Foreign coverage is limited to cities of 15,000+ population.
- N is small per class. At N=30 a zero-count class has an upper 95% bound of about 11%.
- The owner's microphone and room differ from the TTS set. The synthetic result does not transfer.

## Recording protocol (carried from v2)

One complete take per line, read as written. A re-take is allowed only before any output is seen, and every re-take is logged and counted. Recordings come from the owner's private Drive folder. Nothing in this repository reads that folder.
