# Issue #1559 — Phase B cosine retrieval calibration

**Decision:** select separate Arctic cosine cutoffs for message history, user core, episodic memory, agent identity by vibe, and Kiwi truth by vibe. The production thresholds below were fit only on calibration examples, with separate held-out queries. This evidence supports replacing the shared provisional `0.55` cutoff on these fixtures; it does not establish production-distribution quality.

## Reproducible evidence

- Evaluator: [`tools/arctic_phase_a/phase_b_eval.py`](../../tools/arctic_phase_a/phase_b_eval.py); labelled Phase B fixtures: [`phase_b_corpus.json`](../../tools/arctic_phase_a/phase_b_corpus.json); actual Kiwi source corpus: `nz_truth_memories.json` (144 `vector_text` documents).
- Runtime: LiteRT/TFLite host CPU, four threads, exact model artifacts; Kotlin tokenizer behavior ported for the evaluator. Arctic queries use the upstream retrieval prefix; documents are unprefixed; cosine distance is `1 - cosine_similarity`.
- Artifact SHA-256: Arctic TFLite `17c2211fbd759e769b3030837d8259a86a2f7603a0f64222fde14474e4c3054d`; vocabulary `07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3`; EmbeddingGemma comparison model `ad09e81557203cb0e177abf9bf8727dfe138a7d394aa0f70f0b2ed16432e121a`; SentencePiece model `d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7`.
- Fixture SHA-256 `50716db4e5c090b06482a73358759bfad5607345e9d397d5988cf9f6b78bfd16`; Kiwi corpus SHA-256 `162a3aa64ac5d4bab762ba468257d8136babb5205c633bf611fa6b1efbce161d`.
- Each cutoff is the highest observed calibration cosine threshold whose false-positive count does not exceed the corresponding EmbeddingGemma/L2 baseline. Evaluation reports threshold TP/FP, recall, MRR and ranked recall. Kiwi and identity use the same source truth corpus and labels but separate consumers, K values and fitted cutoffs.

## Selected thresholds and held-out comparison

`TP/FP` counts include all labeled query-document pairs returned at the consumer's configured K. `R` is threshold recall; `MRR` and `R@1/3/5` describe ranking. Percentages are rounded by the evaluator.

| Consumer (K) | EmbeddingGemma L2 baseline | Arctic cosine cutoff(s) | Calibration: EG TP/FP, R, MRR → Arctic TP/FP, R, MRR | Held-out: EG TP/FP, R, MRR → Arctic TP/FP, R, MRR |
|---|---|---|---|---|
| Message history (3) | `0.90` | `0.650` | `3/0, .500, 1.000` → `6/0, 1.000, 1.000` | `4/0, .667, 1.000` → `5/0, .833, 1.000` |
| User core (11 shared-table candidates) | `1.25` | `0.789` | `6/59, 1.000, .7778` → `6/59, 1.000, .8056` | `6/60, 1.000, .7738` → `6/60, 1.000, 1.000` |
| Episodic (3) | `1.10` | `0.666` | `6/9, 1.000, 1.000` → `6/9, 1.000, 1.000` | `6/6, 1.000, 1.000` → `6/5, 1.000, 1.000` |
| Kiwi (2) | vibe 1–5: `1.25`, `1.25`, `1.20`, `1.20`, `1.20` | vibe 1–5: `0.564`, `0.731`, `0.667`, `0.620`, `0.500` | `12/18, .800, .7116` → `14/14, .9333, .9417` | `6/14, .600, .5932` → `9/7, .900, .9333` |
| Identity (11) | vibe 1–5: `1.25`, `1.25`, `1.20`, `1.20`, `1.20` | vibe 1–5: `0.759`, `0.764`, `0.737`, `0.720`, `0.691` | `13/152, .8667, .7116` → `15/144, 1.000, .9417` | `8/100, .800, .5932` → `10/94, 1.000, .9333` |

Each Kiwi/identity aggregate covers three calibration queries and two held-out queries per vibe (15/10 total), with one relevant target per query. Message, core and episodic each used six calibration and six held-out queries over a 12-document synthetic fixture.

### Ranked retrieval on calibration and held-out queries

The bundles below are `MRR / R@1 / R@3 / R@5`; these ranking metrics are independent of the selected output cutoff. Both models rank the same query/document pairs within each consumer.

| Consumer | Split | EmbeddingGemma L2 | Arctic cosine |
|---|---|---|---|
| Message history | Calibration | `1.000 / 1.000 / 1.000 / 1.000` | `1.000 / 1.000 / 1.000 / 1.000` |
| Message history | Held-out | `1.000 / 1.000 / 1.000 / 1.000` | `1.000 / 1.000 / 1.000 / 1.000` |
| User core | Calibration | `.7778 / .6667 / .8333 / .8333` | `.8056 / .6667 / 1.000 / 1.000` |
| User core | Held-out | `.7738 / .6667 / .8333 / .8333` | `1.000 / 1.000 / 1.000 / 1.000` |
| Episodic | Calibration | `1.000 / 1.000 / 1.000 / 1.000` | `1.000 / 1.000 / 1.000 / 1.000` |
| Episodic | Held-out | `1.000 / 1.000 / 1.000 / 1.000` | `1.000 / 1.000 / 1.000 / 1.000` |
| Kiwi | Calibration | `.7116 / .6000 / .8000 / .8000` | `.9417 / .9333 / .9333 / .9333` |
| Kiwi | Held-out | `.5932 / .5000 / .6000 / .6000` | `.9333 / .9000 / 1.000 / 1.000` |
| Identity | Calibration | `.7116 / .6000 / .8000 / .8000` | `.9417 / .9333 / .9333 / .9333` |
| Identity | Held-out | `.5932 / .5000 / .6000 / .6000` | `.9333 / .9000 / 1.000 / 1.000` |

### Threshold precision and suppressed pairs

Precision/FPR are `TP / (TP + FP)` and `FP / all labeled negatives`; suppressed positives are relevant pairs not returned at the threshold, and unretrieved negatives are labeled negatives not returned. Counts are `EmbeddingGemma → Arctic`.

| Consumer | Split | Precision | FPR | Suppressed positives | Unretrieved negatives |
|---|---|---|---|---:|---:|
| Message history | Calibration | `1.000 → 1.000` | `.0000 → .0000` | `3 → 0 / 6` | `66 → 66 / 66` |
| Message history | Held-out | `1.000 → 1.000` | `.0000 → .0000` | `2 → 1 / 6` | `66 → 66 / 66` |
| User core | Calibration | `.0923 → .0923` | `.8939 → .8939` | `0 → 0 / 6` | `7 → 7 / 66` |
| User core | Held-out | `.0909 → .0909` | `.9091 → .9091` | `0 → 0 / 6` | `6 → 6 / 66` |
| Episodic | Calibration | `.4000 → .4000` | `.1364 → .1364` | `0 → 0 / 6` | `57 → 57 / 66` |
| Episodic | Held-out | `.5000 → .5455` | `.0909 → .0758` | `0 → 0 / 6` | `60 → 61 / 66` |
| Kiwi | Calibration | `.4000 → .5000` | `.0084 → .0065` | `3 → 1 / 15` | `2127 → 2131 / 2145` |
| Kiwi | Held-out | `.3000 → .5625` | `.0098 → .0049` | `4 → 1 / 10` | `1416 → 1423 / 1430` |
| Identity | Calibration | `.0788 → .0943` | `.0709 → .0671` | `2 → 0 / 15` | `1993 → 2001 / 2145` |
| Identity | Held-out | `.0741 → .0962` | `.0699 → .0657` | `2 → 0 / 10` | `1330 → 1336 / 1430` |

The Kiwi and identity results use 144 actual NZ truth documents and 25 labeled queries. The Kiwi evaluator uses K=2 (the HALF persona retrieval path); identity uses K=11 because it searches the shared core table with six user-core plus five identity candidates. Over the full labeled set, Arctic threshold recall was message 11/12, core 12/12, episodic 12/12, Kiwi 23/25, and identity 25/25. The user-core synthetic corpus produced many false positives for both models; Arctic matches that count and improves held-out ranking, but this does not establish acceptable precision on a production-size user-memory corpus.

## Phase A misses at the former shared `0.55` cutoff

The 18-document / 14-query cross-consumer fixture returned 9/14 positives and 0/238 negatives at Arctic `0.55`; the EmbeddingGemma L2 `0.90` baseline returned 4/14 and 0/238. Arctic ranking was MRR `.9643`, R@1 `13/14`, R@3 `14/14`, R@5 `14/14`; EmbeddingGemma was MRR `.8381`, R@1 `11/14`, R@3 `12/14`, R@5 `12/14`. The evaluator records each positive target's rank and distance in both model rankings:

| Query → labeled target | EmbeddingGemma rank / L2 distance | Arctic rank / cosine distance | Diagnosis at old Arctic `0.55` |
|---|---:|---:|---|
| `Where is home now?` → `home_near` | `#2 / 1.080001` | `#1 / 0.619387` | Threshold-only; Arctic target is rank 1 but above `0.55` |
| `What city did I move to?` → `home_near` | `#1 / 0.960442` | `#1 / 0.579141` | Threshold-only; Arctic target is rank 1 but above `0.55` |
| `What do I build for work?` → `work_near` | `#11 / 1.152336` | `#2 / 0.695591` | Ranking and threshold; `kiwi_work` ranks first at `.634617` |
| `Tell me about the trip I am planning.` → `travel` | `#1 / 1.025671` | `#1 / 0.640778` | Threshold-only; Arctic target is rank 1 but above `0.55` |
| `Why was I tired yesterday?` → `conversation` | `#1 / 0.854026` | `#1 / 0.572079` | Threshold-only; Arctic target is rank 1 but above `0.55` |

Four Arctic positives are ranked first and suppressed only by the old cutoff; the work query is also a rank-2 result behind a competing labeled negative. Each target is present in the fixture; these five cases show no label mismatch. The five misses are query-target pairs across four unique documents, not five unique memories. Phase B assigns separate message/core/episodic/identity/Kiwi limits; do not reuse the old shared `0.55` scorecard as the Phase B per-consumer evaluation.

## Phase B physical inference smoke

`app/src/androidTest/.../ArcticLiteRtDeviceBenchmarkTest.kt` passed on both connected devices with the pinned 113,850,784-byte Arctic artifact. The test uses TFLite Interpreter 2.17.0, CPU, four threads, and pretokenized reference cases; its embedding comparisons are against the upstream-reference vectors. It measures model inference, not a full production manager download or Room/vector-index migration.

| Device | Android build / SDK | Load + allocate | First embedding | Steady median / p90 (20 samples) | PSS baseline / loaded / peak MiB | RSS baseline / loaded / peak MiB | Min / mean reference cosine |
|---|---|---:|---:|---:|---|---|---|
| S21 (SM-G991B) | `AP3A.240905.015.A2.G991BXXSJHZC2` / 35 | 266.36 ms | 735.77 ms | 1377.48 / 1702.82 ms | 162.17 / 337.85 / 345.82 | 342.40 / 518.49 / 526.56 | `.994756` / `.995850` |
| S23 Ultra (SM-S918B) | `BP4A.251205.006.S918BXXSAFZH3` / 36 | 360.40 ms | 792.34 ms | 757.38 / 778.20 ms | 135.09 / 335.68 / 342.33 | 308.16 / 510.63 / 519.06 | `.995118` / `.995911` |

Both inference instrumentation runs completed without a crash; output norms remained approximately `1.0`. They measure model inference against upstream-reference vectors, not download-manager behavior or database migration.

## Phase B physical seeded re-index

`app/src/androidTest/.../ArcticPhaseBIndexMigrationTest.kt` ran the real `EmbeddingIndexMigration`, Room DAOs, sqlite-vec store and LiteRT engine on both devices. It staged the pinned model and vocabulary, seeded an old L2 index identity, stale vectors in all four tables, and 147 canonical Room records (one message, one core memory, one episodic memory, and all 144 Kiwi records). It timed the cold probe plus rebuild and sampled process PSS every 10 ms.

| Device | Re-embedded rows | Re-index time | PSS baseline / peak / increase MiB | Max exact-match cosine distance | Obsolete assets removed |
|---|---:|---:|---|---:|---|
| S21 (SM-G991B) | 147 | 182.262 s | 148.16 / 363.58 / 215.42 | `0.000` | yes |
| S23 Ultra (SM-S918B) | 147 | 114.154 s | 140.90 / 362.11 / 221.21 | `0.000` | yes |

Both seeded migration runs passed: the index identity advanced only after rebuild, all 147 records remained vectorized, nearest-row checks returned the canonical rows, and legacy EmbeddingGemma/tokenizer sentinel files were removed. This exercises the real migration against seeded old-index state, not an upgrade from a preserved pre-Phase-B installation/database. Interruption recovery was not induced on device; retry behavior is covered by unit tests.

## Phase B physical tokenless first-run download

`app/src/androidTest/.../ArcticPublicModelFirstRunTest.kt` deleted the Arctic model and vocabulary, confirmed the debug install had no Hugging Face access token, then initialized the real `ModelDownloadManager` and waited for both required Arctic files to finish downloading. The test verified exact byte lengths and pinned SHA-256 values.

| Device | HF token present | Model bytes / SHA-256 | Vocabulary bytes / SHA-256 |
|---|---|---|---|
| S21 (SM-G991B) | no | 113,850,784 / `17c2211fbd759e769b3030837d8259a86a2f7603a0f64222fde14474e4c3054d` | 231,508 / `07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3` |
| S23 Ultra (SM-S918B) | no | 113,850,784 / `17c2211fbd759e769b3030837d8259a86a2f7603a0f64222fde14474e4c3054d` | 231,508 / `07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3` |

Before manager initialization, one-byte Gemma sentinel files suppressed unrelated multi-gigabyte conversation-model auto-downloads; the test removed them afterward. This validates the Arctic first-run manager path without credentials on both devices, but not a completely empty app install or the unrelated LLM download paths. It also does not resolve the immutable-release gate below.

## Limits and release gate

The message/core/episodic data are small synthetic fixtures (six calibration and six held-out queries each); Kiwi and identity use only 25 labeled queries total despite the 144-document source. Cutoffs must therefore remain traceable to this evidence, and the scores must not be described as broad production validation. A larger representative user-memory dataset may change the core false-positive budget and thresholds.

The exact versioned Arctic model and vocabulary assets are publicly downloadable without a token, and their downloaded bytes match the pinned hashes. The GitHub prerelease currently reports `immutable=false`; only a repository administrator can enable immutable releases. Phase B release acceptance is **held** until the asset/tag immutability gate is satisfied. Do not claim the mutable release is immutable or merge on that basis.
