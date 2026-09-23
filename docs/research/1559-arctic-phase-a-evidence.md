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

## Physical-device comparison

The generic baseline is the current production `KernelModel.EMBEDDING_GEMMA_300M` artifact, not the deprecated SM8550-specific variant. It was downloaded from `litert-community/embeddinggemma-300m` at immutable revision `29888fcee3216acadc7e844906e5fe0d79a61875`: `embeddinggemma-300M_seq512_mixed-precision.tflite` (179,132,472 bytes; SHA-256 `ad09e81557203cb0e177abf9bf8727dfe138a7d394aa0f70f0b2ed16432e121a`) and `sentencepiece.model` (4,683,319 bytes; SHA-256 `d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7`). Both files remained local/device-test inputs and are not committed.

The Arctic candidate is 113,850,784 bytes (SHA-256 `17c2211fbd759e769b3030837d8259a86a2f7603a0f64222fde14474e4c3054d`); its WordPiece vocabulary is 231,508 bytes. Model plus tokenizer assets are 114,082,292 bytes for Arctic versus 183,815,791 bytes for EmbeddingGemma: Arctic is 69,733,499 bytes (37.9%) smaller.

All four device invocations passed (one candidate test and one baseline test on each phone). Both used the same five text cases, TFLite Interpreter 2.17.0, CPU, four threads, one cold/first inference, and 20 steady timings. The production `SentencePieceTokenizer` generated EmbeddingGemma's input IDs before timing; Arctic's existing fixture contains its pretokenized WordPiece inputs. Input preparation is excluded from latency in both. The baseline uses one `int32 [1,512]` token-ID input and a `float32 [1,768]` output; the existing candidate uses two inputs and the same output shape. The baseline benchmark also performs the production Kotlin L2 normalization after inference.

### S23 Ultra (SM-S918B, API 36)

| Metric | Arctic candidate | EmbeddingGemma | Arctic − EmbeddingGemma |
|---|---:|---:|---:|
| TFLite model bytes | 113,850,784 | 179,132,472 | −65,281,688 |
| Model + tokenizer bytes | 114,082,292 | 183,815,791 | −69,733,499 (−37.9%) |
| Load + allocate | 287.03 ms | 226.68 ms | +60.35 ms |
| First embedding | 796.03 ms | 1,245.67 ms | −449.65 ms |
| Steady median | 756.18 ms | 1,168.31 ms | −412.13 ms (35.3% faster) |
| Steady p90 | 781.50 ms | 1,198.07 ms | −416.57 ms (34.8% faster) |
| PSS baseline / loaded / peak | 137.78 / 336.79 / 346.90 MiB | 223.36 / 399.60 / 406.06 MiB | Raw totals are not directly comparable |
| PSS added over each process baseline, loaded / peak | 199.01 / 209.12 MiB | 176.24 / 182.70 MiB | +22.77 / +26.42 MiB |
| RSS high-water baseline / loaded / peak | 311.59 / 511.98 / 524.31 MiB | 399.13 / 579.30 / 590.53 MiB | Raw totals are not directly comparable |
| RSS high-water added over each process baseline, loaded / peak | 200.39 / 212.72 MiB | 180.18 / 191.40 MiB | +20.21 / +21.32 MiB |
| Crash / instrumentation result | none / passed | none / passed | — |

### S21 (SM-G991B, API 35)

| Metric | Arctic candidate | EmbeddingGemma | Arctic − EmbeddingGemma |
|---|---:|---:|---:|
| TFLite model bytes | 113,850,784 | 179,132,472 | −65,281,688 |
| Model + tokenizer bytes | 114,082,292 | 183,815,791 | −69,733,499 (−37.9%) |
| Load + allocate | 294.03 ms | 389.22 ms | −95.19 ms |
| First embedding | 1,029.15 ms | 1,243.33 ms | −214.18 ms |
| Steady median | 863.71 ms | 1,173.90 ms | −310.20 ms (26.4% faster) |
| Steady p90 | 1,023.23 ms | 1,497.08 ms | −473.85 ms (31.7% faster) |
| PSS baseline / loaded / peak | 165.84 / 342.68 / 352.61 MiB | 222.98 / 408.09 / 408.09 MiB | Raw totals are not directly comparable |
| PSS added over each process baseline, loaded / peak | 176.84 / 186.77 MiB | 185.11 / 185.11 MiB | −8.26 / +1.66 MiB |
| RSS high-water baseline / loaded / peak | 339.00 / 517.46 / 530.65 MiB | 403.28 / 588.88 / 624.95 MiB | Raw totals are not directly comparable |
| RSS high-water added over each process baseline, loaded / peak | 178.45 / 191.64 MiB | 185.60 / 221.67 MiB | −7.14 / −30.02 MiB |
| Crash / instrumentation result | none / passed | none / passed | — |

PSS is sampled with `Debug.MemoryInfo`; RSS values are `/proc/self/status` `VmHWM` high-water marks. Each model was run in a separate instrumentation invocation/process. The EmbeddingGemma baseline process includes its actual SentencePiece tokenizer in the baseline/loaded/peak snapshots; the candidate fixture process has pretokenized WordPiece inputs and does not instantiate the future candidate tokenizer. Therefore raw absolute process totals are shown but not used as a cross-model total-app comparison. “Added over baseline” isolates each test process's model/interpreter growth; it is the comparable runtime-memory signal for this Phase A harness.

## Phase A gate status and limits

**Phase A verdict: PASS.** The converted candidate retains its previously measured reference fidelity and retrieval-ranking parity, runs without crashes on both target phones, and is 26.4–35.3% faster at steady median inference and 31.7–34.8% faster at p90 on these matched five-text runs. Its model-plus-tokenizer artifact bundle is 37.9% smaller. The largest observed runtime-memory increase is on S23 Ultra: +26.42 MiB peak PSS and +21.32 MiB RSS high-water over EmbeddingGemma's per-process increase. S21 peak PSS is +1.66 MiB and peak RSS is 30.02 MiB lower. These measured differences do not indicate a material reliability or target-device memory regression for the S23 Ultra or the 8-GB S21 profile; this is a profile-level assessment, not a newly invented numeric gate.

This closes the comparative Phase A evidence gap only. No production model path, index, schema, thresholds, UX, or stored data changed. Do not start Phase B or #1329 validation as part of this remediation. The device comparison does not measure a full production Arctic tokenizer integration, batch re-index time, or upgrade/re-index behavior; those remain outside Phase A and this task.

## Reproduction

See [`tools/arctic_phase_a/README.md`](../../tools/arctic_phase_a/README.md) for the pinned Python environment, conversion, scoring, and physical-device fixture workflow. Generated `.tflite` binaries, downloaded weights, and device outputs remain local-only; no model binary is committed.
