# #2093 real-recording test: pre-registration v2

Committed before any owner real recording exists. The owner's recordings have not been made or received when this file is committed. Line texts, audio, transcripts and per-clip results are private and stay out of git. The line list and class map are committed by hash before the first recording (section "Line set").

v2 supersedes the synthetic held-out test in `HELDOUT-PREREG.md` for the go/no-go decision. The v1 set was already seen when v2 was written, so v1 and post-hoc v2 results do not validate v2. v2 is tested only on the owner's real recordings.

## Frozen code and assets (sha256)

| File | sha256 |
|---|---|
| `oracle.py` (decision rule, AU homograph veto, `match` per beta row) | `142b2c8c82a44432913d390e139c9fb57c63897cc45b09e1594658e44294c62d` |
| `build_place_candidates.py` (`merge` flags `au_homograph`) | `f92ea4d234502c0b44e31a84287ecdfaacd8832b58fdb5a2e46ce5c755b0ef6b` |
| `lexicon.py` (fold, respellings, impostors) | `c2e74f265f9a8c4bb78f1494c824892e7b935798a2565c6e0ea82a568fc2ef25` |
| `score_v2.py` (identity-aware scoring) | `684896e945c91b5f003c6449c253106ee9eb04433dc516977735445e62b6b348` |
| `test_oracle.py` (25 tests, including the homograph veto) | `798c4c2ccac024135c6de46146e873a5fc797261c65484e873ce4abc85e04231` |
| `data/glossary_places.txt` | `2b192b453498688b619dcc1f1b1a86c286d4ef9bc246f7c0a5d0bd716bc7c07f` |
| `nz_au_places.json` (LINZ + AU competitors, 19 homograph flags; private) | `577fbd43711b73df35cfd0ea26a17921e37ade58bc712da84480b85ba93f3c9f` |
| `cga-populated-places.json` (private raw CGA download, retrieved 2026-11-23T10:53Z) | `f4a7cd0ca30ff020adf335d54417fd8d3d68e5e7686bb3d5eb48da5c4cfb1565` |

The v1 file `HELDOUT-PREREG.md` lists the v1 asset as `f625c1a7…0500`. That file was overwritten when v2 regenerated it, so the v1 hash no longer reproduces from disk. The regenerated v2 asset has the same 13,176 rows and 167 rewritable rows, with 19 `au_homograph` flags. A byte-level diff against v1 was not possible; this equivalence is by count only.

## Parameters

- β primary: **5.0**, chosen after the v1 β 4 failure (see post-hoc note; not validated). Sensitivity: β 3.0 and 4.0, reported, not used for pass/fail.
- Margin: `MARGIN = 1.0` against the runner-up of any kind. Gate: v2. Boundary and word-count checks as in v1.
- Outcomes: `accept`, `suggest` ("Did you mean X?", never rewrites), `abstain_competitor`, `impostor_top`, `below_threshold`, `veto`.
- Veto rules, in order:
  1. Exact folded match to a competitor or impostor name: `veto`.
  2. Exact folded match to a rewritable NZ name flagged `au_homograph` (its folded name is also a CGA name): `veto`, reason `au_homograph`. This is the homograph rule. It applies only to exact slot matches.
  3. Exact folded match to any other rewritable NZ name: `accept` to the canonical spelling.

## Scoring (changed from v1)

- **Match** (`match` field per beta row): the folded heard place text that a rewrite would replace. On an exact slot hit it is the slot text. Otherwise it is the folded greedy text in the top candidate's matched frames. It excludes carrier words such as "take me to".
- **Rewrite** = outcome `accept` and folded top name differs from `match`. An accept whose top name equals `match`, ignoring case and macrons, is an **identity**. Identities are not rewrites and are reported separately.
- **Output place text** = top name if `accept`, otherwise `match`. **Gold correct** = folded output place text equals the folded gold name.
- **Gold substitution** = rewrite whose folded top name is not the folded gold name.
- Wilson 95% intervals on all counts. Denominators: lines by class and size.

Note on v1 scoring: v1 counted every `accept` as a rewrite and compared the slot text. That mis-scored identity lines (see post-hoc note). v2 is the first version that scores identity correctly.

## Pass criteria (binding; per slot and size, β 5)

1. Gold substitutions = 0.
2. Non-identity rewrites on foreign city, foreign homograph, POI, contact/address, suburb/locality, AU competitor city and gate-trap lines = 0.
3. Competitor-place rewrites = 0.
4. NZ already-correct places: non-identity rewrites = 0.
5. Gate traps: non-identity rewrites = 0.
6. Scoring p95 is reported (PC). The S21 criterion (≤ 50 ms) is not tested by this offline run and is not claimed as passed.

Identity counts are reported and are not pass/fail.

## Foreign and AU homographs

- The AU homograph flag covers the 19 rewritable NZ names that also appear in the CGA: Alexandra, Bluff, Clive, Gore, Hamilton, Hastings, Inglewood, Lincoln, Matamata, Napier, Nelson, New Plymouth, Queenstown, Raglan, Reefton, Richmond, Rolleston, Waikato, Wellington. None has a macron, so the veto removes only exact slot matches. Their output is the heard place text, which already equals the canonical name.
- The veto applies only to an exact slot match. A homograph reached through a fuzzy match is not vetoed; it is judged by the normal rule. This is a limit, not an oversight, and the owner recordings will show whether it matters.
- No impostor name collides with a rewritable NZ name (checked). Foreign names outside the CGA are **not** covered. A foreign city absent from the CGA and from `lexicon.IMPOSTORS` cannot be flagged by this rule. This is a known gap, and it is reported as such.
- Any foreign-name list added before recording is a rule change and needs a new pre-registration.

## Line set (to commit before the first recording)

- Gold place lines, false-boost lines and class map: the owner's line list. Commit its sha256 and class counts here before recording. Lines must include at least the v1 classes: gold navigate, take-me, weather, bare; foreign city, foreign homograph, AU competitor city, POI, contact/address, suburb/locality, NZ already-correct, gate trap.
- Recording protocol: one complete take per line, read as written. A re-take is allowed only before any output is seen, and every re-take is logged and counted.
- Real recordings come from the owner's private Drive folder. Nothing in this repository reads that folder.

## Known risks (stated before the test)

- N is small per class. At N=30 a zero-count class has an upper 95% bound of about 11%. Zero counts are weak evidence.
- The owner's microphone and room differ from the TTS set, so the synthetic result does not transfer.

## Post-hoc note (not pre-registration evidence)

After the v2 code was frozen, v1 held-out clips were re-scored with the v2 code as a diagnostic. Labelled post-hoc; not used for pass/fail.

- The first v2 diagnostic pass used the whole greedy slot as the comparison text. That was wrong: the rewrite replaces only the matched place text, so it mis-scored lines whose slot contained carrier words. It was fixed before this file was frozen, by recording `match`.
- With `match`, at β 5: 0 non-identity rewrites and 0 gold substitutions in every class, both sizes, all three slots.
- The v1 homograph line `ho-f06` ("weather in Hamilton") was an **identity**: the matched place "hamilton" was rewritten to "Hamilton", a case change only. v1 criterion 2 counted it as a rewrite because v1 counted every `accept`. That was a v1 scoring error, not a rewrite.
- v1 criterion 1 still stands at β 4: hint L and trigger L had one gold substitution, Motueka → Owaka. At β 5 there were zero substitutions in v1 and in post-hoc v2. **β 5 was chosen after seeing the β 4 failure on the seen synthetic set, so it is a post-hoc choice, not a validated one.** The owner recordings are the only test of β 5.
