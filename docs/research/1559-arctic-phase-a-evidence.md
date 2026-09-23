# Issue #1559 — Arctic Embed M v1.5 Phase A evidence

**Scope:** conversion/quality gate only. No production embedding path, model catalogue, vector schema, retrieval threshold, first-run UX, or stored index was changed. This report does not authorize Phase B.

## Provenance and artifact

- Upstream: [`Snowflake/snowflake-arctic-embed-m-v1.5`](https://huggingface.co/Snowflake/snowflake-arctic-embed-m-v1.5/tree/e58a8f756156a1293d763f17e3aae643474e9b8a), immutable revision `e58a8f756156a1293d763f17e3aae643474e9b8a`; Apache-2.0.
- Reference pipeline: uncased 30,522-token WordPiece; 512 tokens; query-only prefix `Represent this sentence for searching relevant passages: `; CLS pooling; L2 normalization; 768 dimensions.
- Vocabulary: 231,508 bytes; SHA-256 `07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3`.
- Converter: LiteRT Torch 0.9.4; AI Edge Quantizer 0.9.0; symmetric, channel-wise, 8-bit weight-only recipe in `tools/arctic_phase_a/dynamic_wi8_afp32_recipe.json`.
- Candidate: 113,850,784 bytes (108.58 MiB); SHA-256 `17c2211fbd759e769b3030837d8259a86a2f7603a0f64222fde14474e4c3054d`.
- Tensor contract: two `int32 [1,512]` inputs (`input_ids`, `attention_mask`); `float32 [1,768]` normalized embedding output. This matches the existing Android call shape (`IntArray` inputs).
- Existing catalogue estimates EmbeddingGemma at 171,000,000 bytes plus its 4,500,000-byte SentencePiece file. Arctic's TFLite plus vocabulary totals 114,082,292 bytes, about 35.0% below those current estimates. The current figures are catalogue estimates, not device download measurements.
- Final graph includes `BATCH_MATMUL`, `GELU`, `EMBEDDING_LOOKUP`, `FULLY_CONNECTED`, and standard tensor ops. TFLite Interpreter 2.17.0 loaded and ran it on both tested devices without an unsupported-op or allocation failure.

The first PT2E quantization route emitted an artifact that failed TFLite 2.17.0 allocation at `EMBEDDING_LOOKUP` (`zero_point == 0` assertion). That artifact was rejected. The final candidate uses the checked-in symmetric channel-wise weight-only recipe and passed both device runs.

The successful conversion completed without a blocking warning. It did emit dependency deprecations from PyTorch's Enum constant registration and `torch.export`'s `LeafSpec` check; the exporter reports these as future-incompatibility warnings. The Hugging Face client warned that the download was unauthenticated (expected: the pinned upstream artifact is public). LiteRT Torch estimated 89.519G arithmetic ops / 44.759G MACs. No ONNX Runtime was added.

## Reference fidelity and retrieval

The reproducible runner in `tools/arctic_phase_a/run.py` evaluates the pinned SentenceTransformers/PyTorch reference and final LiteRT artifact on 18 representative documents and 14 queries, including New Zealand terms, paraphrases, short conversational queries, episodic facts, and hard negatives. It uses direct cosine for this experiment only; no production sqlite-vec behavior or threshold was changed.

- 32 reference/candidate vector pairs: mean cosine **0.99504**, minimum **0.99182**, 5th percentile **0.99238**; zero non-finite outputs.
- 14 retrieval queries: candidate/reference top-1 agreement **14/14**; mean top-5 set overlap **4.64/5**.
- Hand-labelled relevant document was top-1 for **13/14** queries with both reference and candidate. The one miss is shared by both; candidate did not worsen it.
- Query-prefix probe (`Where is home now?`): prefixed and unprefixed top-1 remained `home_near` for both reference and candidate; prefixed-vs-unprefixed vector cosine was 0.51468 reference and 0.52459 candidate. The probe records direction change without asserting an arbitrary threshold.
- All converted vectors were finite and had norms approximately 1.0.

These results establish close conversion parity on this fixture set; they do not establish general retrieval quality or calibrated production thresholds.

## Physical-device measurements

The test-only `ArcticLiteRtDeviceBenchmarkTest` ran the candidate through the app's TFLite Interpreter 2.17.0, CPU, four threads. Five reference fixtures were used, with 20 steady-state timings. Both instrumentation runs passed (1 test, 0 failures each). Timings below are observed values, not pass/fail thresholds.

| Metric | S23 Ultra (SM-S918B, API 36) | S21 (SM-G991B, API 35) |
|---|---:|---:|
| Load + allocate | 248.17 ms | 258.42 ms |
| First embedding | 779.52 ms | 741.17 ms |
| Steady median | 794.90 ms | 851.16 ms |
| Steady p90 | 800.96 ms | 1,009.68 ms |
| PSS baseline / loaded / peak | 137.57 / 338.73 / 347.00 MiB | 162.28 / 365.54 / 374.59 MiB |
| RSS high-water baseline / loaded / peak | 312.79 / 515.18 / 525.02 MiB | 340.88 / 546.80 / 557.48 MiB |
| Device/reference cosine mean / minimum | 0.99591 / 0.99512 | 0.99585 / 0.99476 |
| Output norm min–max | 0.99999955–1.00000063 | 0.99999935–1.00000004 |
| Crash / test result | none / passed | none / passed |

PSS is sampled via `Debug.MemoryInfo`; RSS values are `/proc/self/status` `VmHWM` high-water marks. The S21 p90 is higher than S23 Ultra; the observed S21 maximum was about 1.01 s. The candidate ran successfully on both devices.

## Phase A gate status and limits

Conversion compatibility, reference-vector parity, retrieval-ranking parity, and candidate-only S23 Ultra/S21 runtime measurements are now evidenced. The current EmbeddingGemma model and tokenizer were not present in the isolated debug test installations, so these runs do **not** provide a same-device EmbeddingGemma latency or memory baseline. Consequently, the measurements cannot establish a comparative “no material memory/reliability regression” claim. No threshold was invented to turn these candidate-only numbers into a pass.

**Recommendation:** stop at Phase A as issue #1559 requires. Keep production unchanged. Treat conversion and fidelity as supported by the evidence above, but leave the cross-model device-regression gate explicitly unresolved until an authorized EmbeddingGemma baseline or an owner decision is available. No Phase B work or #1329 validation was started.

## Reproduction

See [`tools/arctic_phase_a/README.md`](../../tools/arctic_phase_a/README.md) for the pinned Python environment, conversion, scoring, and physical-device fixture workflow. Generated `.tflite` binaries, downloaded weights, and device outputs remain local-only; no model binary is committed.
