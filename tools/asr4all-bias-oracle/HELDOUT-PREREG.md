# #2093 held-out test: pre-registration

Committed before any held-out audio is synthesised or decoded. Held-out line texts, audio, transcripts and per-clip results are private and are not in git; this file commits their hashes once they exist.

## Frozen decision code and parameters

- Decision code: `oracle.py` `evaluate()` with the step-2 rule (no prior). Sha256 of the file is in the table below.
- β (primary): 4.0. Sensitivity: β 3.0 and 5.0, reported but not used for pass/fail.
- Margin: `MARGIN = 1.0` against the runner-up of any kind.
- Veto: exact folded slot match to a competitor or impostor blocks rewrite.
- Boundary: span must end at a greedy word boundary; word count at most place words + 1.
- Outcomes: `accept`, `suggest` (rewritable top fails margin/boundary/word count; "Did you mean X?"), `abstain_competitor`, `impostor_top`, `below_threshold`, `veto`.
- Gate: v2 (`EXACT_ONLY` for `long` and `take`; `two` is not a `to` alias after take/get/go).
- Respelling rules: `lexicon.py` folding and respelling as committed.

## Frozen assets (sha256)

| Asset | sha256 |
|---|---|
| `tools/asr4all-bias-oracle/oracle.py` | `b2f9b56127d0ff8a577da7b822a1337c843627be2fd40286b7df96c0a6304521` |
| `tools/asr4all-bias-oracle/lexicon.py` | `c2e74f265f9a8c4bb78f1494c824892e7b935798a2565c6e0ea82a568fc2ef25` |
| `tools/asr4all-bias-oracle/build_place_candidates.py` | `7aa3824c535db72ba98b239f9edcdd8b415813c0c7f78b53c201d29640e08d65` |
| `tools/asr4all-bias-oracle/data/glossary_places.txt` | `2b192b453498688b619dcc1f1b1a86c286d4ef9bc246f7c0a5d0bd716bc7c07f` |
| Place asset with AU competitors (`nz_au_places.json`) | `f625c1a76e4ba9fd8b488bef2baaca79acf00987a6be63b94a6816412ae50500` |
| NZ-only place asset (`nz_places_rw.json`, comparison only) | `69be8242052b09d77e709c373944ba475a4185b21475f0e81cf65dafd80ac602` |
| AU source (`cga-populated-places.json`, comparison) | `f4a7cd0ca30ff020adf335d54417fd8d3d68e5e7686bb3d5eb48da5c4cfb1565` |

Licence status of the AU source is unresolved: the published CGA record says "© Commonwealth of Australia (Geoscience Australia) 2025", not CC BY 4.0. The asset is for internal evaluation only.

## Synthesis setup

- Model: `Qwen/Qwen3-TTS-12Hz-1.7B-Base` (local cache), ROCm GPU.
- Reference: `~/jandal-voice/reference/clone_24k.wav`, sha256 `daee1f65d402a0a9c60857a5d6a3afc048d092fa9e8526e5e4a305b185694466`. Mono 24 kHz PCM_16, 51.95 s.
- Deviation from the existing `generate.py`: that script uses a transcript-aligned 12 s crop with `x_vector_only_mode=False`. This reference has no transcript, so cloning uses `x_vector_only_mode=True`.
- Output: 16 kHz mono PCM_16 WAV for the oracle; 48 kHz copies optional.

## Pass criteria (primary, β 4)

Per class, with Wilson 95% intervals:

1. Gold places: 0 substitutions (accepted to a wrong name).
2. False-boost foreign cities, POIs and contact/address lines: 0 rewrites.
3. Competitor places (AU and NZ suburbs/localities): 0 rewrites.
4. Already-correct NZ places: no change beyond macrons and case.
5. Gate traps: no rewrite.
6. Scoring p95 reported (PC, per clip). The S21 ≤ 50 ms target is not tested by this run.

Known trap, pre-registered as a report-only item: "Hamilton" is a rewritable NZ City and a foreign city name; a rewrite there counts as a foreign-city rewrite and fails criterion 2.

With 0/N in a class, the 95% upper bound is about 11% at N = 30; the result bounds error, it does not establish safety.

## Held-out line set

Line texts are not committed. The set is fixed by the category counts below and by the file hash, which is committed in the next commit before any synthesis.

- Gold: 30 lines; 7 "navigate to", 6 "take me to", 5 "weather in", 12 bare-name slot answers. Non-glossary towns: at least 4 (27 of 30 gold lines name a non-glossary town). Reserved names not used: Whangārei, Tauranga, Taupō, Whakatāne, Porirua, Ōtaki, Kaikōura, Waipukurau, Ōamaru, Ngāruawāhia.
- False-boost: 31 lines: foreign cities (6), AU competitor cities (6), POIs (4), contact name and street address (3), suburbs/localities that must stay unchanged (4), already-correct NZ places (3), gate traps (5).
