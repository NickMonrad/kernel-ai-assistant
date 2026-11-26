# Pre-registration v3, amendment 5: tuning of the bias oracle (#2093)

Committed before any tuning audio is synthesised, scored or inspected. Owner decision (26 Nov 2026): "Let's try
tuning further." This amendment fixes the tuning set, the sweep, the objective, the tie-break and the intelligibility
exclusion. Audio, line texts, transcripts, clip ids and per-clip rows stay private.

## Status of the data (stated before tuning)

- **Owner real recordings are not a blind test set after this amendment.** They were scored at β 3, 4 and 5 under
  v3 (amendment 2) and used to choose β 5. This tuning round uses the same owner script (all 50 lines), so the 36
  recorded lines' texts are in the tuning set as synthetic audio. The owner recordings are re-scored once, at the end,
  with the frozen config (step 4). That result is reuse-disclosed in the README.
- **The 14 gap-fill lines (R17–R30) were already scored** at β 3–5 under amendments 3–4. Their results were seen.
  They are in the tuning set because the owner asked for it; they are not blind.
- The tuning set therefore shares all 50 script texts with the owner test set. Only the voice and recording path
  differ (synthetic ICL clone vs owner microphone). Any tuned config must be judged on that basis.

## Tuning set (synthetic, private, `~/private-acoustic-fixtures/heldout-2093-tune/`)

1. **Full-clone synthetic audio of the 36 owner lines not in the gap set** (the 36 recorded lines, R01–R16 and
   R31–R50): Qwen3-TTS 1.7B Base, full voice-clone (ICL) mode, reference `clone_24k.wav` with `clone_24k.txt`,
   `x_vector_only_mode=False`, bf16, ROCm GPU. Two takes per line. Seed `9100 + 100·take + index`, index = position
   in `owner_script_lines.tsv`. A take over 5 s is excluded, not truncated or re-seeded (as amendment 4).
   Output 16 kHz mono PCM_16.
2. **The 14 gap lines (R17–R30)**: the existing clean takes in `heldout-2093-clone-gap/16k` (41 valid, as scored).
   Not regenerated.
3. **Noisy copies**: every clean take in 1 and 2 gets one babble-like copy at **10 dB SNR** only, generated with the
   generator and mixing code of `make_noisy.py` (amendment 3), seed `9000 + 10·seed + 10` of the source take. The
   5 dB copies of the gap set are not part of the tuning set.

Expected size: about 113 clean and 113 noisy clips (valid takes only), both sizes M and L, all three slots.
The exact count is reported after synthesis.

Labels: gold = the script line's place (`gold` empty for non-place lines). Class map from the existing
`owner-class-map.json` and `gap-class-map.json`. No new labels are made by hand.

## Intelligibility exclusion (before scoring)

- Every tuning clip (clean and noisy) is transcribed with faster-whisper `medium.en` before any oracle run.
- Word similarity = `difflib.SequenceMatcher` ratio between the lower-cased, punctuation-stripped word lists of the
  transcript and the script line.
- **Threshold 0.60.** A clip below 0.60 is excluded from scoring, whether gold or false-boost. Each clip is judged on
  its own score; a clean take's exclusion does not exclude its noisy copy, and the reverse.
- Counts excluded per class and per source are reported. Clip ids stay private.

## Scoring baseline

The frozen v3 oracle (`397329a5…`) and asset (`fd1b0904…`) are the baseline, run on the same clips. The baseline
config is the **current rule**: β 5, margin m 1.0, boundary check on, competitor runner-up counts in the margin test.

## Sweep (pre-declared; the whole grid is reported)

| Parameter | Values | Current |
|---|---|---|
| β | 3, 4, 5, 6, 7 | 5 |
| margin m (score units) | 0.5, 1.0, 1.5, 2.0 | 1.0 |
| boundary and word-count check | on, off | on |
| competitor runner-up in the margin test | counts (as now), ignored | counts |

80 configs. Each config is applied to the cached Viterbi rows of every clip; the decode is not re-run per config.
β above 5 is included here because v3 left β > 5 unchecked for false accepts; a β > 5 config must pass the same
safety criteria on the tuning set to be eligible.

The competitor-ignored variant removes a competitor from the margin test only. It never makes a competitor the
rewrite target, and the abstain rule (competitor top → no rewrite) is unchanged.

Scoring code change: `oracle.py` gains flags for margin, boundary on/off and competitor handling. Defaults reproduce
the current rule exactly; the baseline is re-run to confirm identical outputs before any sweep. The changed file is
frozen by hash in amendment 6.

## Objective and feasibility (pre-declared)

For each config, over the tuning set (clean and noisy, after exclusion), all three slots and both sizes:

- **Safety, hard constraints (must hold):** gold substitutions = 0 (an accept whose top is not the gold name) and
  non-identity false-boost rewrites = 0 (an accept on a non-gold line that changes the text). Identity rewrites
  (case or macron only) are not counted, as in v3.
- **Objective (among feasible configs):** maximise gold-correct accepts (accept with the gold name), summed over the
  six slot×size cells, clean and noisy together.
- **Tie-break (deterministic, smallest change from current):** (1) fewest parameters differing from the current rule;
  (2) smaller |β − 5|; (3) smaller |m − 1.0|; (4) boundary on before off; (5) competitor counts before ignored.
- **No feasible config:** the frozen config is the current rule, unchanged. The report says so. The baseline's own
  tuning-set substitution count is reported whether or not it is zero.

The sweep reports all 80 configs (aggregates only, in the README) so the chosen one is visible against the rest.

## Reporting (tuning set, aggregates only)

- Baseline: the same tables as v3 (gold outcomes, misses by cause: margin, boundary, word_count, competitor
  abstain, impostor top, veto, below threshold), Wilson 95% on every count, per-class bounds for zero counts.
- Miss breakdown by cause for the baseline, before sweeping.

## Step 4 (the owner's real recordings), fixed here

- Score once with the frozen config and the v3 scoring code, in the six slot×size cells. No further sweep.
- Report against the baseline with the same criteria tables, Wilson 95% and per-class bounds, and β 3/4/5
  sensitivity. Pre-registered v3 criteria 1–6 apply unchanged.
- The real-recording result is reported as reuse-disclosed. A fresh owner recording of new lines (about 10 lines) is
  recommended as the clean check; it is not done in this round.

## Not in this amendment

- Changes to the gate, the lexicon, the respellings, the asset, the candidate sets or the impostor list.
- Any flag change, any production change, any claim about S21 microphone performance or p95 timing.
