# asr4all bias oracle (#2093 step 1)

**Dev-set results only.** No held-out set exists in this environment. β, m and the gate were chosen on the
same clips they are reported on, so every figure is optimistic.

Offline go/no-go for decoder-side biasing of place names. It answers one question: when the encoder
logits already contain the spoken place, does a biased CTC span score put the true place first among the
rewritable places, and does the decision rule rewrite only correct places? No production code is involved.
The tool reads logits that the host harness dumps.

## Pipeline

1. **Dump logits** (test source, `core/voice`). `Asr4AllHostHarnessTest` accepts the config key
   `dump_logits_dir`. When set, `LogitsTapBackend` (test-only wrapper) copies the output of every
   `stream_*` call, and for repeat 1 the harness writes `<clip>.<size>.<tier>.logits.f32`
   (float32 LE, `[frames, vocab]`, frames = `floor(samples / 640)`) plus a `.json` sidecar.
   Dumps are private; they are not committed.
2. **Score** (`oracle.py`). Greedy CTC gives the text; the slot is chosen by `--slot`:
   - `trigger`: the gate (`--gate v1|v2`) fires on the greedy text; the slot starts after the anchor.
   - `hint`: caller slot-fill hint. If the gate does not fire, the whole utterance is the slot.
   - `full`: whole utterance (diagnostic).
3. **Test** (`python3 -m unittest -v test_oracle`).

```bash
# 1. dump (from the worktree root; Android SDK required for Gradle)
ANDROID_HOME=$HOME/Android/Sdk ASR4ALL_HARNESS_CONFIG=/abs/path/bias-oracle-dump.json \
  ./gradlew :core:voice:testDebugUnitTest --tests 'com.kernel.ai.core.voice.asr4all.host.Asr4AllHostHarnessTest'

# 2. score (labels and place list are private; see Data). The step-2 rule: β 4.0, margin 1.0.
python3 tools/asr4all-bias-oracle/oracle.py \
  --dumps /abs/out/bias-oracle-dump \
  --model M=/abs/models/asr4all-m --model L=/abs/models/asr4all-l \
  --labels /abs/private/bias-oracle-labels.json \
  --places /abs/private/nz_places_rw.json --betas 4 \
  --slot full --gate v2 --out /abs/private/oracle-out

# 3. checks (fixture check needs the M model dir with config.json and merges.txt)
ASR4ALL_MODEL_M=/abs/models/asr4all-m python3 -m unittest -v tools/asr4all-bias-oracle/test_oracle.py
```

Harness config keys used: `python`, `bridge`, `models`, `sizes`, `tiers`, `chunk_ms`, `repeat`,
`clips`, `output`, plus `dump_logits_dir`. Input WAVs must be 16-bit mono at 16 kHz; resample
48 kHz clips with ffmpeg first.

Labels file (private): `{"<clip id>": {"split": "owner-dev" | "clone", "gold": ["<display name>", ...]}}`.
`gold` is empty for non-place clips and must match a candidate's display name. The oracle stops with an
error if a gold name is missing from the candidate list.

## Gate

- **v1** (before step 2): exact phrasings `navigate to`, `directions to`, `take me to`,
  `weather in|at`.
- **v2** (step 2): generic travel and weather phrasings, fuzzy on words of 4+ letters (edit distance 1,
  which covers greedy-CTC garbling), except `long` and `take`, which must match exactly (fuzzy matching makes
  "along" a "long" and "make" a "take"). A travel verb (`navigate`, `directions`, `drive`, `route`,
  `travel`, `trip`, `far`, `long`, `take`, `get`, `go`) followed by `to` or `too` within 7 words; `two` is not
  accepted as `to` after `take`, `get` or `go` (the numeral: "take two tablets"). Or a weather word
  (`weather`, `forecast`, `raining`, `rain`, `temperature`) followed by `in`, `at` or `for` within 3 words.
  The slot starts after the anchor.

  Provenance: the v2 phrasings were written after the owner's transcripts were read, so the gate is not
  independent of the dev set. The `take`/`long`/`two` exclusions were added after an independent review
  found false triggers in the phrasings; they changed no gated place count (10 of 14 per size, before and
  after). The gate has not been checked against held-out speech, and the exclusions are not pre-registered
  for step 2.

## Scoring

- **Segmentation: merges-rank BPE** (`merges.txt` from the model directory). `' ' + phrase` with the
  SentencePiece space marker; the lowest-rank adjacent pair is merged repeatedly until none applies.
  Every final symbol is checked against the 256-piece vocab; a miss raises. One canonical segmentation
  per variant.
- Span score: `Δ_raw = max over spans [a,b) in the slot of Σ_t (logp[t, path_t] − M_t)`, where `path` is the
  best CTC path collapsing to the segmentation (Viterbi, max not sum), `M_t` the frame max. Start and end
  are free; outside frames contribute nothing. `Δ_raw ≤ 0`. The Viterbi also returns the frames `[a, b)` of
  the best span, which the word-boundary check uses.
- Score `Δ_raw + β·n` (n = pieces). The step-2 rule fixes **β = 4.0**; the runs below use only that value.
- Respellings (`lexicon.py`, fixed before measurement): macron vowel → `vowel + r` (ā → ar); `r → d|t`,
  `wh → f|w`, `au → o|ow`, `ei → ay`, `u → oo`. Each rule applies to one occurrence, then all. At most 12
  variants per name. Impostors get the same variants. Each entry is scored by its best variant.

### Decision rule (step 2, fixed)

Candidates: rewritable places, competitor places, impostors. There is no prior: ranking is by score only.

1. **Exact veto.** The folded greedy slot text equals a competitor or impostor name → no rewrite
   (`veto`). Equals a rewritable name → rewrite to that name's canonical spelling (`accept`); this only
   restores macrons and capitals.
2. The overall top must be a rewritable place with score > 0. A competitor top with score > 0 → abstain
   (`abstain_competitor`); an impostor top → `impostor_top`; otherwise `below_threshold`.
3. The top must beat the runner-up of any kind (a different entry) by **m = 1.0**. Otherwise, if the top is
   rewritable, the outcome is `suggest` (the UI would show "Did you mean X?"), with `reason = margin`.
4. The matched span must end at a greedy word boundary (the next greedy character is a space or the end of
   the text). Otherwise `suggest` with `reason = boundary`.
5. The matched span may cover at most the place's word count + 1 words. Otherwise `suggest` with
   `reason = word_count`.
6. Otherwise `accept` (rewrite). `suggest` and `accept` both use the same rewritable top; only `accept` rewrites.

rank-1 (reporting only) = the best rewritable entry by score, respelled and canonical. It is reported
even when a competitor wins, so it measures the rewritable set alone.

Folded-spelling collisions keep the first entry, in the order rewritable, competitor, impostor; the
skipped names are reported on stderr.

The earlier prior-based policy (City > Town tie-break within a band) is withdrawn. Its ties were resolved
across entry kinds, so an impostor or competitor within the band could lose to a rewritable place; its
results are not reported here.

## Candidates

- **Rewritable (the substitution set)**: Town and City from the LINZ export, plus every glossary place
  (`data/glossary_places.txt`, 15 names; matched by folded spelling, so `Pūkekohe` matches LINZ `Pukekohe`).
  167 names.
- **Competitors**: Village, Suburb and Locality from the LINZ export, excluding the rewritable names, plus
  AU populated places from GeoNames (see "AU competitors and homograph names" below). Competitors can
  win and cause an abstain; they are never rewritten to.
- **Impostors**: `lexicon.IMPOSTORS` (hand-written world cities, destinations, frequent words). `kia ora`
  was removed because LINZ lists it as a place. 144 names.
- **Baseline**: `lexicon.NZ_PLACES`, about 110 hand-written names, all rewritable, no competitors. Used
  only for the comparison rows; the default when `--places` is not given.

## Data (private, not in git)

- Dev set: 25 clips (M and L dumps of each): 11 non-place clips and 14 place clips. The place clips are
  10 owner clips and 4 cloned clips. Gold names are in the private labels file. Owner clip identifiers,
  transcripts and per-clip results are not recorded in this repository.
- Held-out clone test: pre-registered in `HELDOUT-PREREG.md` (committed before any held-out audio was
  synthesised). Lines and audio are private; results are in "Held-out clone test" below.

### LINZ place list (layer 51681, CC BY 4.0)

Licence (LINZ's required wording): *Contains data sourced from the LINZ Data Service and licensed for reuse
under CC BY 4.0*. Licence URL: https://creativecommons.org/licenses/by/4.0/

Source: owner's download `nz-place-names-nzgb.csv`, NZ Place Names layer 51681, LINZ export dated
23 Nov 2026 (the date is owner-stated; the file does not carry it). Source sha256
`3e99508b32e5c39ea5767328882f0d859b16d44f54c15e72dd1eb1ed1b64402c`. The export parses to 54,401
records; the owner's note said 54,752, which this export does not reproduce (the file has 54,416 physical
lines because of multi-line fields). Columns used: `name`, `feat_type`, `status`, `crd_latitude`,
`crd_longitude`. The CSV is private; only the recipe is committed.

Filter (populated places): types `Town, City, Village, Suburb, Locality`; excluded status substrings
`Replaced, Discontinued, Original`. Roles: rewritable = Town/City or a glossary name; competitor = the rest.
Pinned command:

```bash
python3 tools/asr4all-bias-oracle/build_place_candidates.py \
  --from-file /abs/private/nz-place-names-nzgb.csv --name-field name --type-field feat_type \
  --status-field status --types "Town,City,Village,Suburb,Locality" \
  --exclude-status "Replaced,Discontinued,Original" \
  --glossary-places tools/asr4all-bias-oracle/data/glossary_places.txt \
  --out /abs/private/nz_places_rw.json
```

The output is `{"header": {...}, "places": [...]}`. The header records the licence text and URL, the layer,
the export date, the source sha256 (computed from the file read), and what was changed: filtered by feature
type and status; reduced to name, type, role, priority, coordinates and a per-row attribution; names stored as
exported with macrons. Folding to ASCII and respelling are applied at scoring time, not in the list.

Result: **4,334 places** in the file: 167 rewritable (Town 147, City 18, plus Petone (Suburb), Waikato
(Locality) and the glossary's own Suburb/Locality entries), 4,167 competitor rows, of which 20 folded
duplicates are skipped at scoring (leaving 4,147). Output sha256
`69be8242052b09d77e709c373944ba475a4185b21475f0e81cf65dafd80ac602`. All 15 glossary places matched a LINZ
row; the generator reports any that do not.

Other sources, checked without an account:
- `gazetteer.linz.govt.nz`: JS app; no unauthenticated CSV export found in its bundles; two guessed CSV
  paths returned HTTP 500.
- `data.linz.govt.nz` layer page: an embedded token in its page source is not used.
- WFS endpoint: HTTP 401 without a key. `--api` reads `LINZ_API_KEY` from the environment and sends it in
  the `X-API-Key` header, never in the URL. The header name is unverified; no key was available to test it.

## Clean-room

Rules came from general letter patterns and the glossary's observed-error classes
(`core/voice/src/lexicon/assets/stt_glossary.json`), not from any source excluded by
`docs/evidence/2004/CLEANROOM.md`. The respelling rules were fixed before measurement. The gate v2
phrasings are general, but the transcripts were visible while writing them (see Gate). The rewritable
set uses the glossary's place names and LINZ feature types, not any clip result.

## AU competitors and homograph names (GeoNames, CC BY 4.0)

- Source: GeoNames public dump, `https://download.geonames.org/export/dump/` (no account). Retrieved
  2026-11-23T12:00Z. Files `AU.zip` and `cities15000.zip`, readme `readme.txt`; sha256 and licence in
  `HELDOUT-PREREG-v3.md`. Licence: CC BY 4.0 (readme). Attribution required in any published artefact:
  "GeoNames (geonames.org), CC BY 4.0".
- AU competitors: feature class P, country AU, codes PPL, PPLX, PPLL, PPLA, PPLA2, PPLC, PPLS, STLMT
  (19,202 rows). PPLQ, PPLW, PPLH and PPLF are excluded. AU names are never rewrite targets.
- Homograph veto: a rewritable NZ row whose folded name is an AU competitor name, or a name of a GeoNames
  `cities15000` city outside NZ and AU (31,854 distinct folded names), is flagged `homograph`. Exact slot
  match only; the reason is `homograph`. Flagged rows: 35 (no macrons, so no restoration is lost).
- Limits: `cities15000` covers cities of 15,000 population or more; smaller foreign places are not covered.
- Generated `nz_geonames_places.json` (private): 21,140 rows (167 rewritable, 20,973 competitors).
- The Composite Gazetteer of Australia is dropped. Its licence is not CC BY 4.0 per the public record.
  Its download is local and is not used by any shipped artefact.
- Dev-set cost: the earlier CGA competitor set blocked one correct NZ answer per slot (size L). The
  GeoNames set changed the held-out outcomes (see `HELDOUT-PREREG-v3.md`, post-hoc section).

## Limits

- Dev set only. β = 4.0, m = 1.0 and the gate are not validated on held-out speech.
- The gate was written after reading the transcripts and changed after the review; its coverage is
  optimistic. Gate misses remain: clips whose greedy text has no travel verb or weather word.
- The rewritable set is 167 names, not the full place list. A place outside it is never rewritten to; it can
  only win as a competitor (abstain). The set was partly chosen from the glossary, which came from the same
  error observations, so whether every promoted name is a true gold place is not checked.
- Non-place false accepts: 0 of 11 clips. The 95% Wilson upper bound for 0 of 11 is about 26%. In the
  full-utterance runs every non-place clip ended with an impostor on top, so the zero depends on the impostor
  list. No clip in the set is a foreign city or POI after a trigger, and the trigger slot gated no non-place
  clip, so trigger-slot false accepts are untested.
- Substitutions: 0 in every cell, but a cell has only 2–6 accepts. With 0 of 4 accepts, the 95% upper bound is
  about 49%; with 0 of 2, about 66%.
- Respellings did not improve rank-1 in this run: canonical rank-1 was equal to or higher than respelled in
  all six full/hint/trigger cells. Their value for acceptance is not shown; no canonical-only accept run was
  done.
- The audio came from WAV files through the host harness, not played through the S21 microphone.
- β above 5 was not run under this rule. Its false-accept behaviour is unknown.
- BPE is one canonical segmentation per variant.
- Gold labels are 16 instances over 14 clips; each count can move by one clip.

## Results (dev set, step-2 rule, β 4.0, gate v2)

One run per slot, both sizes, 25 clips each (14 place, 11 non-place). Counts are clips. For the trigger slot
only the 10 gated place clips are scored (12 gold instances among them); no non-place clip was gated.
rank-1 is shown as respelled / canonical.

Full utterance (diagnostic):

| Size | rank-1 (resp / canon, of 14) | accepted (of 14) | substituted | impostor top | margin | abstain (competitor won) | FA (of 11) |
|---|---|---|---|---|---|---|---|
| L | 13 / 14 | 4 | 0 | 5 | 3 | 2 | 0 |
| M | 12 / 12 | 2 | 0 | 7 | 3 | 2 | 0 |

Slot-fill hint:

| Size | rank-1 (resp / canon, of 14) | accepted (of 14) | substituted | impostor top | margin | abstain (competitor won) | FA (of 11) |
|---|---|---|---|---|---|---|---|
| L | 13 / 14 | 6 | 0 | 4 | 2 | 2 | 0 |
| M | 12 / 13 | 4 | 0 | 3 | 5 | 2 | 0 |

Trigger slot, gate v2 (of 10 gated place clips):

| Size | rank-1 (resp / canon, of 10) | accepted (of 10) | substituted | impostor top | margin | abstain (competitor won) | FA |
|---|---|---|---|---|---|---|---|
| L | 10 / 10 | 4 | 0 | 2 | 2 | 2 | not tested (0 non-place clips gated) |
| M | 9 / 9 | 2 | 0 | 2 | 5 | 1 | not tested (0 non-place clips gated) |

Each place row sums to the clip count: accepted + impostor top + margin + abstain (+ boundary, word_count, veto,
below_threshold, all 0 here). Every non-place clip ended with an impostor on top in the full and hint runs.

Comparison, not directly comparable:
- Earlier run, every LINZ place rewritable (no competitors, prior-free, β 5): full L accepted 7 with 3
  substitutions; full M accepted 5 with 5 substitutions. The substitutions were Localities that are
  near-homophones of gold names. Making Localities competitors removes that failure mode.
- Hand-written baseline (about 110 rewritable names, no competitors, β 5, full): L accepted 8, M 7; 0
  substitutions; 0 FA. Its rewritable set differs from the 167-name set, so no run separates the set change
  from the competitor change.

Gate coverage (greedy text, no audio), place clips fired (of 14) and non-place clips fired (of 11):

| Gate | M place | L place | M non-place | L non-place |
|---|---|---|---|---|
| v1 | 6/14 | 5/14 | 0/11 | 0/11 |
| v2 | 10/14 | 10/14 | 0/11 | 0/11 |

## Go/no-go (revised, step-2 rule)

- **Safety on the dev set:** 0 substitutions and 0 non-place accepts in all six full/hint/trigger cells.
  Impostors or margin failures block most gold clips: accepted gold is 2–6 of 14 clips (4 of 10 at best on
  the trigger slot).
- **Rank-1 among rewritable:** 12–13 of 14 clips (respelled), 12–14 (canonical). It depends least on the
  decision rule and is the measure the design review set.
- **Verdict: go for step 2 only behind a flag that is off by default**, with β 4.0, m 1.0, no prior, the
  gate above, and the decision rule above. Auto-rewriting for users waits for the held-out test. The
  conditions are:
  1. Pre-register in a commit, before any held-out audio is decoded: β, m, the gate code, the asset sha256,
     the respelling rules, the decision code and the pass criteria.
  2. Run the held-out test: gold places through the S23U into the S21 microphone, 10 places × 3 tries, both
     sizes, including at least 3 Towns outside the glossary; and at least 30 false-boost trigger utterances
     (foreign cities, POIs, names and addresses, near-homophones of rewritable places, already-correct NZ
     places, and gate traps such as "take two …" and "how long to …").
  3. Pass needs 0 substitutions, 0 rewrites of foreign cities or POIs, 0 rewrites of competitor places, and
     scoring p95 ≤ 50 ms on the S21. Even then, 0 of 30 only bounds the error rate at about 11%.
  4. Check β above 5 for false accepts under this rule before any β change.
- Not done: any ablation of the margin or the boundary check, and any further tuning. The dev set has been
  used for this rule; the next numbers should come from held-out speech.

## Held-out clone test (pre-registered; β 4 primary)

Set: 30 gold place lines and 31 false-boost lines, synthesised in the owner's cloned voice (Qwen3-TTS 1.7B
Base, x-vector-only reference from `clone_24k.wav`, one take per line, 16 kHz). Frozen decision code, assets and
criteria: `HELDOUT-PREREG.md`. Audio, line texts, transcripts and per-clip rows are private. Scored on the
LINZ + AU asset (`nz_au_places.json`) with gate v2. Gold denominators: 30 (full, hint), 13 (trigger; only
gated lines are scored). Intervals are Wilson 95%.

Gold place lines:

| Slot | Size | Correct accept | Substitution (wrong name) | Suggest (correct name) | Abstain (competitor top) | Other |
|---|---|---|---|---|---|---|
| full | M | 23/30 (76.7%, 59.1–88.2) | 0/30 (0–11.4) | 0 | 2 | 5 |
| full | L | 20/30 (66.7%, 48.8–80.8) | 0/30 (0–11.4) | 3 | 2 | 5 |
| hint | M | 25/30 (83.3%, 66.4–92.7) | 0/30 (0–11.4) | 0 | 3 | 2 |
| hint | L | 24/30 (80.0%, 62.7–90.5) | 1/30 (0.6–16.7) | 2 | 2 | 1 |
| trigger | M | 12/13 (92.3%, 66.7–98.6) | 0/13 (0–22.8) | 0 | 1 | 0 |
| trigger | L | 11/13 (84.6%, 57.8–95.7) | 1/13 (1.4–33.3) | 1 | 0 | 0 |

All suggestions named the gold place. "Other" is impostor top, veto or below threshold.

False-boost lines (rewrites, same at M and L; trigger column = lines the gate scored):

| Class | n | Rewrites (full / hint) | Trigger scored | Trigger rewrites |
|---|---|---|---|---|
| AU competitor city | 6 | 0/6 | 4 | 0/4 |
| Foreign city | 5 | 0/5 | 4 | 0/4 |
| Foreign city, homograph of an NZ City | 1 | 0/1 non-identity (identity: "hamilton" → "Hamilton", case only) | not gated | – |
| POI | 4 | 0/4 | 4 | 0/4 |
| Contact name / street address | 3 | 0/3 | 1 | 0/1 |
| Suburb / locality (must stay unchanged) | 4 | 0/4 | 3 | 0/3 |
| NZ place already correct | 3 | 0/3 non-identity (all identity) | 2 | 0/2 non-identity (identity) |
| Gate trap | 5 | 0/5 | 3 | 0/3 |

Sensitivity: β 3 gives 1 gold substitution in hint L and in trigger L. β 5 gives none; β 5 was chosen after the
β 4 failure (post-hoc, not validated). The homograph line is an identity at every β, so it is not a rewrite.

Pre-registered criteria (β 4):

1. Gold substitutions = 0: **fail** (1/30 hint L; 1/13 trigger L).
2. Non-identity false-boost rewrites = 0: **pass**. The homograph line is an identity. v1 counted it as a rewrite; that was a scoring error.
3. Competitor-place rewrites = 0: **pass** (0 in every competitor class). Competitor abstains: 2 distinct gold lines; both had an AU CGA name as top (one in each of full M, full L, hint M, hint L).
4. NZ already-correct places: **pass**. All lines are identity (no change).
5. Gate traps: **pass** (0 rewrites; 2 of 5 trap lines were not gated in the trigger slot).
6. Scoring p95: **not measured**. The oracle is a batch PC run with no per-clip timing, and nothing ran on the S21.

Verdict: the pre-registered test (β 4) does not pass. Only criterion 1 fails: the L-size gold substitution
(Motueka → Owaka) at hint and trigger. Criterion 2 was mis-scored in v1 (see above). Post-hoc at β 5 (chosen after
this failure, not validated): zero gold substitutions, zero non-identity false-boost rewrites. The v2 scoring and
pass criteria are in `HELDOUT-PREREG-v2.md` and apply only to the owner's real recordings. Limits: synthetic (TTS)
audio, not the S21 microphone; one take per line; 61 lines; zero-count upper bounds ≈ 11% per class with 30 lines.
The flag stays off.

## Owner real recordings (pre-registered v3 + amendments 1–2; β 5 primary)

36 of 50 owner lines recorded (quiet room only): gold 16/30 (bare_name 0/3 not recorded), false-boost 20/20.
R17–R30 not recorded; noisy-room and bare_name not evaluated. Scored with frozen v3 code and asset (oracle
sha256 `397329a5…`, asset `fd1b0904…`). 16 kHz mono conversion via ffmpeg. Commits: `ebb619e9` (amendment 2,
before scoring).

Criteria (Wilson 95% on the count of violations; intervals are the upper bound when zero):

| # | Criterion | Result (β 5, all slots) | Verdict |
|---|---|---|---|
| 1 | Gold substitutions = 0 | 0 in all 6 slot×size cells; upper 19.4% (full, hint, n=16), 20.4% (trigger, n=15) | **pass** (on recorded lines) |
| 2 | Non-identity rewrites = 0 (foreign, POI, address, suburb, AU city, gate trap) | 0/17 (full, hint), 0/15 (trigger) | 0 observed; per-class bounds 60-97.5% (amendments 1-2); foreign homograph not evaluated (no lines) |
| 3 | Competitor-place rewrites = 0 | 0/6 (AU city 4, suburb 2) | 0 observed; per-class bounds 60-97.5% (amendments 1-2) |
| 4 | NZ already-correct: non-identity rewrites = 0 | 0/2 | 0 observed; per-class bounds 60-97.5% (amendments 1-2) |
| 5 | Gate traps = 0 non-identity rewrites | 0/5 (full, hint, incl. no-place line), 0/2 (trigger) | 0 observed; per-class bounds 60-97.5% (amendments 1-2) |
| 6 | Scoring p95 | not measured (PC batch) | **not evaluated** |

Gold correct output (informational; no recall threshold was pre-registered), correct / gold lines:

| Slot | Size | navigate | weather |
|---|---|---|---|
| full | L | 2/8 | 3/8 |
| full | M | 1/8 | 6/8 |
| hint | L | 3/8 | 3/8 |
| hint | M | 1/8 | 6/8 |
| trigger | L | 3/7 | 3/8 |
| trigger | M | 1/8 | 5/7 |

Gold misses at β 5 (owner, all six cells; 57 misses of 94 cell-lines): 27 `suggest` (24 margin, 3 boundary; a "Did you mean X?" prompt, no rewrite), 19 competitor abstains, 9 impostor tops, 2 vetoes.
False-boost lines: every class (AU city, foreign city, POI, address, suburb, NZ already-correct, gate trap) has zero non-identity rewrites in every slot and size.

Sensitivity (β 3 and β 4, not primary; recomputed from the same per-clip rows): 0 gold substitutions and 0 non-identity rewrites in every cell at both.

Gold-line independence: the 16 gold lines cover only 10 distinct place names, and the gap set reuses the same 10.
Lines are therefore not independent, and the gold counts overstate coverage.

Go/no-go (pre-registered criteria): criterion 1 passes on the recorded lines (0 gold substitutions, small-n limits
above). Criteria 2–5: 0 observed; per-class bounds 60-97.5% (amendments 1-2), so they are not evidence of safety. Criterion 6 is not evaluated. Recall is low at β 5: the rule gets the gold name right on
only 1–3 of 7–8 navigate lines per cell. That is a usefulness problem, not a safety failure. Owner decision required
before any flag change.

## Synthetic gap fill (pre-registered amendments 3–4; β 5 primary; NOT pooled with the real recordings)

Synthetic supplementary set for the 14 owner script lines not recorded (R17–R30), plus noisy copies of R21–R30.
Voice clone: the same owner reference audio as the held-out clone test, in full voice-clone (ICL) mode with its
ASR transcript (not x-vector-only). The same voice, not a new speaker, and not the owner's microphone or room.
Scored with the frozen v3 code and asset (oracle `397329a5…`, asset `fd1b0904…`). Reported separately. The real-recording
verdict above stands on its own.

Pipeline and counts: 41 valid clean takes (42 minus one over-cap take excluded, amendment 4: a generation that ran over
the 5 s cap was dropped, not truncated or re-seeded). 58 noisy copies from 29 valid R21–R30 takes at 10 dB and 5 dB
SNR, using a babble-like noise generated locally by the script (not a recording of a TV or kitchen; no third-party
audio). Noisy results are combined over both SNRs because the scorer groups by class, not SNR. The synthesis
script was amended during the run (resume, excluded-take record, valid-only noisy and harness inputs); the changed hashes are in the amendment 4 addendum, which corrects the noisy count to 58.

Clean takes, criteria (β 5, Wilson 95%):

| # | Criterion | Result | Verdict |
|---|---|---|---|
| 1 | Gold substitutions = 0 | navigate and weather: 0 in all 6 slot×size cells (upper bounds 17.6–29.9%); bare_name: 1/8 in L full and L hint (upper 47.1%), 0/8 in M | **fail**: one bare_name source take (clean L copy and noisy M copy; one failure, not two) |
| 2 | Non-identity rewrites = 0 (non-glossary classes) | no such lines in this set | **not evaluated** |
| 3 | Competitor-place rewrites = 0 | no such lines | **not evaluated** |
| 4 | NZ already-correct non-identity = 0 | no such lines | **not evaluated** |
| 5 | Gate traps = 0 | no such lines | **not evaluated** |
| 6 | Scoring p95 | not measured | **not evaluated** |

Gold correct output, clean (informational, no threshold): full and hint, both sizes: navigate 9/15 (L and M); weather
8/18 (L), 7/18 (M); trigger navigate 9/15 (L and M), weather 4/9 (L) and 5/12 (M); bare_name 2/8 (L) and 3/8 (M).

Noisy copies (β 5; "rewrites" = accepted non-identity output; "gold substitutions" = a rewrite that is not the gold name):

| Slot | Size | bare_name rewrites / sub | navigate rewrites / sub | weather rewrites / sub |
|---|---|---|---|---|
| full | L | 4/16 / 0 | 4/18 / 0 | 13/24 / 0 |
| full | M | 1/16 / **1** | 3/18 / 0 | 9/24 / 0 |
| hint | L | 4/16 / 0 | 4/18 / 0 | 13/24 / 0 |
| hint | M | 1/16 / **1** | 3/18 / 0 | 9/24 / 0 |
| trigger | L | — | 4/18 / 0 | 7/11 / 0 |
| trigger | M | — | 3/17 / 0 | 7/12 / 0 |

Most noisy rewrites are correct rewrites to the gold name; the substitution column is the count that matters. The
noisy M bare_name substitution (1/16, upper 28.3%) is in full and hint. It comes from the same source take as the
clean L bare_name substitution, so this is reported as one failure.

Sensitivity (β 3 and β 4, not primary; recomputed from the same per-clip rows). Gold substitutions (a rewrite whose top is not the gold name):

| β | Clean L bare_name, full / hint | Noisy M bare_name, full / hint | Clean navigate and weather |
|---|---|---|---|
| 3 | 1/8, 1/8 | 0/16, 0/16 | 0 in every cell |
| 4 | 1/8, 1/8 | 1/16, 1/16 | 0 in every cell |
| 5 (primary) | 1/8, 1/8 | 1/16, 1/16 | 0 in every cell |

The noisy M substitution is therefore present at β 4 and β 5, not at β 3. Both substitutions come from the same bare_name source take.

Intelligibility check: the full-run ASR check promised in amendment 3 was not run before scoring. Before the amendment
commit, only the R17 takes were checked (take 1: 0.769 word similarity). A later faster-whisper medium.en check, run
after scoring, gave median word similarity 0.80 over the 41 valid takes. The failing bare_name take scored 0.67
(mid-range), so it is not an unintelligible synthesis.

Glossary check: one place in the owner script's glossary column (Whangārei) is absent from both `data/glossary_places.txt` and `stt_glossary.json`. It is a LINZ City, so it is rewritable through the LINZ rows and no number depends on glossary membership. The other five absent gold names (of the ten) are in the non_glossary column, so their absence is expected. Unresolved: the glossary-column label for this place does not match the glossary files.

Go/no-go: the synthetic set is a supplementary check and does not change the real-recording verdict. It shows one
bare_name failure on synthetic audio (one source take, clean and noisy copies). The real set has no bare_name
recordings, so it can neither show nor rule out this failure. Owner decision still required before any flag change.
Recall is low at β 5, as in the real set.

Listening set (not scored, not in git): six owner sentences rendered in the same ICL mode, kept privately.

## Privacy

Clips, transcripts, gold labels, owner clip identifiers, per-clip results, the LINZ export and the derived
place list are private and stay out of the repository. This README and the PR body carry aggregate counts,
the glossary names (public repository data) and LINZ place names with their attribution only.
