# Clean-room record: asr4all ExecuTorch engine (#2004)

## Implementer

- Agent: OMP coding agent (Claude, `anthropic/claude-opus-5-5`) under Paseo.
- Paseo agent id `5db98e9b-d260-4968-a212-6ab56239c90b`, OMP session `01a1faa3-46e8-726a-9cfd-4bc552df42a5`,
  workspace `wks_079e763c547d1c43`, branch `feat/2004-asr4all-executorch-cleanroom`.
- Fresh session for this issue, with no earlier Build 8 or asr4all context. No long-term memory
  about asr4all was injected into the session, and no memory recall was used.

## Statement

I did not read, download, open, print, search or import any asr4all-specific material outside the
allowed list below, and nothing on the #2004 forbidden list.
In particular I did not read any script or code file in the `futo-org/asr4all-*` repositories
(`executorch/asr4all_executorch.py`, `onnx/asr4all_onnx.py`, `modeling_asr4all.py` or their
history), anything in `futo-org/voice-input` or transcribe.cpp, PR #1987 or branch
`feature/asr4all-engine`, PR #2002 or its branch and evidence, the #1988 spike, PR #2001 or
`docs/audits/build8-sol-audit.md`, `docs/evidence/1978/`, any `build8-*` worktree or branch, the
listed `/var/tmp` and `/tmp` paths, other agents' worktrees or logs, or any third-party write-up of
asr4all decoding. The only GitHub issue bodies read were #2004, #2003, #2007 and #1999 (bodies
only, no comments). When I first searched, no PR for #2003 existed (`gh pr list --search 2003`,
empty). After the operator pointed me to it, the only #2003 material read was the diff of draft PR
#2010, at two heads (listed below); I read no PR comments or review threads, and no other branch.
The only other PR comment read is the verifier's AC1 result on #2011, with the operator's
permission (see "AC1 round 1"). It contains only observable outputs. To find it, I filtered the
PR's comments by that heading locally, so no other comment's text was displayed.

Incidental: a `ps` listing I ran to check my own Gradle process also showed another agent's Gradle
command line, whose working directory was a worktree named `feat-core-executorch-runtime`. No file
in that worktree was read; nothing from it informed the engine.

Model files were fetched one by one by exact path
(`https://huggingface.co/<repo>/resolve/<revision>/<path>`); no repository snapshot or file
listing download was used. The model API's revision lookup (`/api/models/<repo>`) was used to
read the `sha` field only.

## Allowed inputs consulted

Revisions (pinned, `main` on 21 Nov 2026):

| repository | revision |
|---|---|
| `futo-org/asr4all-s` | `87a6b5d093723fe37e9f69ad26307a3b95a31af0` |
| `futo-org/asr4all-m` | `7d6e9145109a5bece4f05831d046e93d27e38a99` |
| `futo-org/asr4all-l` | `79d1c23bc8f1174cee576b40bbaa0118bd95abed` |

SHA-256 of every file downloaded (all on the allowed list):

| file | S | M | L |
|---|---|---|---|
| `README.md` | `27fbfa92ec8551feaa1e91a96469c7cb4b299851756600d968b03c2fda056aef` | `7a1d4d9c55d0a3228058aad0f309170309dd5acf0d57891e2e356dbf74907a99` | `bf4f4326a1d207e0d20471dfcfb976e8c080737a88b12feb600a50c21b3b6344` |
| `config.json` | `859b5ad5d9f7861cb544babf54bc10dca29e0dd4b3f254f496224b62e6789605` | `f20e34e046dc817b436ce4d2a5231307f51e7f048b5c04fda263cba53ca60e63` | `6ec76f6203e2880d396203d2ece606d851a0c6ce334cf1e4546e49cb062ad13e` |
| `release_geom.json` | `09d2cd1d5eeb64c61afda0603b74819d7b21179cb32f608ae766a7241321a6de` | `c1eeb3a2eee048f2061e0d4e63a8f65bcf3c00668582b2821847e42096ba3c5c` | `698091d2a3741195fcc9205518610da93a3c222169448687a395979b74cb0554` |
| `preprocessor_config.json` | `be01923661cc9c8149623163524d3ecde4552d879f79041f77b8db8be72d1caa` | same | same |
| `tokenizer_config.json` | `11b33de5e20ffaf87a9d6927efb03a28655658b05789c5b97e7e9cf605d1b8b5` | same | same |
| `vocab.json` | `6e0856cbf4a69940defa8c1c6601b992ca22375b856c04d925ed06b944996971` | same | same |
| `merges.txt` | `f3aac3b19b7303c32c43479fa208479e5b6b1895c47b09fa449997e1e9391eac` | same | same |
| `benchmarks.json` | `451af82839de8ab831b36cf0f0cbb18c6f83f629f142c41669a27b1b3be659b3` | same | same |
| `executorch/xnnpack_int8/metadata.json` | `ef52d88b6ce61f967015e64ec5a9d6b9d5c22a6367c802f1a2a05d5f03b8d1f8` | `298e1c287b31892c9a04c340e2b1e266ba2ced83bdc08f76d0d68b62129ea606` | `f528bafa98cdb47847699e2dfd15a693c9ac45f5b3311fc315dc5e2c2341a0e0` |
| `executorch/xnnpack_int8/asr_encoder.pte` | `7d5679688ca57a7e42d4b42e093b7a29b85d595f454d41cf0aa5f95e7c8c27ec` | `d08648b914a90669986f943011099fb518783ca0bebb3fd9a4ded53ad5067763` | `2670068ab08a2a40d76e6cb79d492ce6879d7165c5f6a8defbf2f4994a49f760` |

What was read: the M `README.md` in full (S and L cards were downloaded but not read); all
three sizes' `config.json`, `release_geom.json` and `metadata.json` (structure and values, via
`jq`); `preprocessor_config.json`, `tokenizer_config.json`, `benchmarks.json`; the first entries
of `vocab.json` and `merges.txt`. The `.pte` files were only loaded and executed through the
official `executorch` Python package (1.5.1, with torch 2.14.1+cpu, Python 3.12.13, x86-64) as a
black-box loader: method names, input/output counts, shapes and dtypes from its `MethodMeta`, and
the values the methods returned for inputs I chose. The program's instructions and constant
tensors were not inspected. Since the move to `:core:executorch`, the engine also reads each
method's declared input/output tensor metadata (dtype, sizes, dynamism) from the `.pte`
flatbuffer at open (`PteMethodTable`). That is the same information as `MethodMeta`, read
through ExecuTorch's public schema. Instructions, delegate payloads and constants are still not
read.

Non-asr4all material used: ExecuTorch's public Python runtime API, this repository's code,
public speech data for black-box measurement (below), and general knowledge of CTC decoding.
Also the following:

| input | revision | SHA-256 |
|---|---|---|
| #2003 shared runtime: diff of draft PR #2010 (`gh pr diff 2010`), branch `feat/core-executorch-runtime` | head `dceae496cabf5798e2aefb716ee3880b2bfd47e6` | `97373a3d0c5c3cabb90ab33f85afa0ad8184f766a2f7044d04d1f6040324620a` (diff text) |
| #2003 shared runtime: diff of draft PR #2010 after its rebase onto `main` (licence/guard fixes; `TensorSpec` now takes `TensorDim`, new `ExecuTorchClosedException`) | head `98e28b069e0ee0724e47ec3689c00e3eac147184` | `9655db56e967e043b9fde4f910f604fc08b6af404a49d1000a23a79e5ea7f77e` (diff text) |
| LibriSpeech `dev-clean.tar.gz` (OpenSLR 12), to rebuild the verifier's clips | — | `76f87d090650617fca0cac8f88b9416e0ebf80350acb97b343a85fa903728ab3` |
| ExecuTorch `schema/program.fbs` | `pytorch/executorch` tag `v1.5.1` | `da7b809b497abea70e813cb23f39e46b6c76e2501d66ef3e91d4f9e1c9cfd62f` |
| ExecuTorch `schema/scalar_type.fbs` | `pytorch/executorch` tag `v1.5.1` | `a4c83c25ee7da8eedf61f04fe3df979e5866035763f0ee8d3ed68463e3baad8f` |
| ExecuTorch `schema/extended_header.h` | `pytorch/executorch` tag `v1.5.1` | `e93e15f096dd16b46e9459e6816da0e017357ac1fb7b25086f8810e09e74a92d` |

The #2010 diff contains no asr4all logic. Its `docs/architecture/executorch-runtime.md` states that
the 1.5.1 Android runtime aborts the process on an input shape the program rejects. That is why
the engine compares its config-derived signatures with the `.pte`'s declared ones before any
method runs.

## Observed method I/O

Identical method set in all three exports: `stream_c16r4`, `stream_c32r8`, `stream_c64r16`,
`stream_c128r24`, `stream_c256r32`, `stream_c512r8`, `pcec_c16`, `pcec_c16_flush`, `pcec_c64`,
`pcec_c64_flush`, `vad`, `vad_dft`.

Encoder tiers (`W` = `release_geom.json` `window_samples`, `C` = commit frames,
`Nf`/`Na` = `filter_cache_numel`/`attn_cache_numel`, `D` = `config.json` `d_model`):

| method | inputs | outputs |
|---|---|---|
| `stream_cCrR` | f32 `[1, W]`, f32 `[Nf]`, f32 `[Na]` | f32 `[1, C, 257]`, f32 `[Nf]`, f32 `[Na]`, f32 `[1, C, D]` |

| size | `D` | `Nf` | `Na` | `W` for c16r4 / c32r8 / c64r16 / c128r24 / c256r32 / c512r8 |
|---|---|---|---|---|
| S | 384 | 1006080 | 329090 | 22400 / 42880 / 83840 / 145280 / 247680 / 350080 |
| M | 512 | 1728000 | 548354 | 24960 / 48000 / 94080 / 160640 / 268160 / 355200 |
| L | 640 | 2545920 | 822402 | 27520 / 53120 / 104320 / 176000 / 288640 / 360320 |

PCEC (rung `R` = 16 or 64; identical across sizes except `D`):

| method | inputs | outputs |
|---|---|---|
| `pcec_cR` | i64 `[1, R]`, i64 `[1, R]`, f32 `[1, R, D]`, f32 `[1, R]`, f32 `[1, 166976]`, i64 `[1]` | i64 `[1, 2R]`, f32 `[1, 166976]`, i64 `[1]` |
| `pcec_cR_flush` | i64 `[1, R]`, i64 `[1, R]`, f32 `[1, R, D]`, f32 `[1, R]`, i64 `[]`, f32 `[1, 166976]`, i64 `[1]` | i64 `[1, 2(R + 16) + 1]` (65 / 161) |

VAD (not used by the engine): `vad`, `vad_dft`: f32 `[1, 16000]` → f32 `[1, 21]`.

Behaviour observed by running the methods (inputs of my choosing):

- `attn_cache` ends with two counters that read `16.0, 16.0` after one `stream_c16r4` call from
  zero caches; zero-initialised caches decode clean text, so caches start at zero.
- `stream_c16r4` on a public LibriSpeech clip, with windows `audio[k·hop : k·hop + W]`
  (`hop = C × samples_per_frame`, zero-padded at the end) and greedy CTC (argmax, collapse
  repeats, drop blank 256), returns the reference transcript word for word.
- `pcec_*` input 0 is an embedding over `in_vocab` = 258 ids (id 258 is rejected); input 1 is an
  embedding of 10 rows (`pause_buckets` 9 + 1; id 10 is rejected). Input 3 is a float: values
  near 0 leave word output unchanged, negative values make the head rewrite words heavily.
- Fed collapsed CTC tokens (not frames), the head returns two slots per token, 16 tokens
  behind (`attn_right` 8 × 2 superblocks): slot 2 echoes the token in the PCEC vocabulary, slot 1
  carries insertions and control ids (`cap` before a word, `comma`/`period`/`question` after the
  previous word). Adjacent identical slots across positions are CTC repeats. The flush output
  covers the 16 held-back tokens plus the `n` new ones (two slots each), plus one slot for the
  closing mark; slots beyond that are padding.
- `seen` advances by `2R` per streaming call.

## Decisions made from these observations

Recorded in code comments (`PcecFeatures`, `PcecStream`, `Asr4AllEngine`) and listed here so a
parity failure can be traced:

1. Encoder windows start at sample 0, advance by the commit hop, zero-pad the last window, and
   run until the window start passes the end of the audio. Caches start at zero.
2. Greedy CTC over all frames, including padding frames.
3. PCEC tokens are CTC runs. Hidden input: mean of the encoder hidden rows over the run.
   Pause input: log2 bucket of the blank frames between the previous run's last frame and this
   run's first frame (0 → 0, 1 → 1, 2–3 → 2, …, capped at bucket 8). **Superseded:** the first token
   uses this rule over the leading blank frames, not blackout bucket 9 as first written; the code
   does the same. Float input: 0. Padding positions: id `pad_in`, pause 0, zero hidden, 0.
4. Streaming calls take full rungs. Live text and final text use the same flush call on the current
   state without committing it, and both include the end-of-input slot (where the head places a
   closing mark). **Superseded:** the first version said partials omit that slot; `PcecStream.text()`
   does not, and the round-2 engine rows were produced by that code.
5. Text: CTC-collapse all slots, drop blanks, apply `cap` to the next piece that has a letter
   and `allcaps` to the next word, attach marks to the preceding text without a space (marks
   before the first word are dropped), keep single spaces, then sentence-initial capitals after
   `.`/`?`, as the model card describes.
6. Runtime (since the rebase onto #2010): every method runs through `:core:executorch`'s
   `ExecuTorchModel.run` with a `MethodSignature` built from the config files. At open, those
   signatures are compared with the ones declared in the `.pte` before the runtime runs anything,
   and PCEC token ids and pause buckets are range-checked before every call. This is because the
   1.5.1 Android runtime aborts the process on inputs the program rejects (#2010's runtime
   document; I observed a native embedding-range check fail on such inputs with the Python
   runtime).

Items 3 and 5 were the least certain; they were chosen by black-box measurement against public
references (see below). The verifier's AC1 run (next section) showed item 3 and parts of items
2 and 4 to be wrong; they were fixed as recorded there. Items 1, 5 and 6 are unchanged.

## AC1 round 1: verifier result and fixes (21 Nov 2026)

**Input.** One PR comment on #2011, the verifier's "AC1 parity verification (#2004): NOT PARITY"
([comment 7054718096](https://github.com/NickMonrad/kernel-ai-assistant/pull/2011#issuecomment-7054718096),
posted 2026-11-21T01:01:43Z; SHA-256 of its body text
`fc3706c034a2fad8a41b708bb55a1b9bc372dd112baa2a0c5552c4f7677896ae`). The operator allowed me to read that
one comment. I read all of it.

It contains only observable outputs: the pass criteria, the setup (versions, model hashes, clip
recipe and WAV hashes), aggregate agreement, a per-row pass/fail table, the reference and engine
final text for the 43 mismatching rows, and the first differing partial event for 141 rows. It
names the reference driver and its SHA-256. It says the live text was read with "its pending tail
included (its documented endpoint option)", which is a usage setting, not an internal. Nothing in
it describes how the reference computes its outputs. Result: finals 125/168, M+L dev-clean 77/96,
partial sequences 27/168, on engine head `fd49f01`.

**Reproduction.** I rebuilt the verifier's 28 clips from its published recipe. The sources were
LibriSpeech `dev-clean.tar.gz` (SHA-256 `76f87d09…728ab3`, same as the verifier's) decoded with
`ffmpeg -ac 1 -ar 16000 -c:a pcm_s16le -bitexact`, plus the four generated edge clips. All 28 WAVs
match the verifier's SHA-256 values byte for byte.

**Method.**
- I wrote a Python mirror of the Kotlin engine (same windowing, CTC, PCEC calls and rendering)
  on the official `executorch` 1.5.1 runtime, with each tier's encoder outputs cached per clip.
  Before use, I checked it against the Kotlin engine: identical finals, partials and partial
  offsets on all 27 host-harness rows, and it reproduces every engine text in the verifier's
  comment (43/43 finals, 141/141 partial events).
- I took the reference final for each row to be the verifier's text where it reported a
  mismatch, and the engine's (unchanged) output where it reported a match. Then I scored
  candidate input encodings against all 168 rows. For partials, the check is the verifier's first
  differing event, plus the events before it, which the verifier's table shows were equal.
- The model's own outputs decided between hypotheses. No reference internals were available or
  used.

Hypotheses tested (finals agreeing with the reference, of 168):

| change | finals |
|---|---|
| baseline (decisions as above) | 125 |
| decode only real-audio frames at the end (ceil or floor) | 125 |
| float input = token log-probability (start/mean/max/min/sum), probability, logit, margin, entropy, run length, pause or start time in seconds, blank probability | 14–126 |
| pause = gap *after* the token (last token: blackout, 0, 8 or trailing silence) | 97–98 |
| pause gap start-to-start / without the −1 / first token 0 / first token = leading silence | 121–128 |
| hidden = first frame / last frame / max / sum / peak-probability frame of the run | 124–130 |
| hidden = mean from the previous token's end to this run's end | 92 |
| **hidden = mean from this token's first frame up to the next token's first frame** (run plus following blanks; last token to end of the decoded frames) | **163** |
| … with the last token ending at the last real-audio frame, ceil(samples/640) | 166 |
| … plus shifting the span end by ±1 frame | 147–152 |
| … plus float inputs above | 140–164 |
| … plus first token's pause = log2 bucket of the frames before it (instead of blackout 9) | 166 (different 2 rows) |
| … plus last token / end-of-input decoding to floor(samples/640) frames | **168** |

The two rows left before the final step (`1993-147965-0005`, S, both tiers) were not a numeric
effect: float32 and float64 mean variants (numpy, torch, sequential) gave the same text. They
were decided by where the last token's span ends: floor(138560/640) = 216 matches,
ceil = 217 does not.

Partials (first-difference agreement of 168):

| change | partials |
|---|---|
| baseline (flush pending + open token, no end slot) | 30 |
| include the end-of-input slot | 38 |
| hold back the open token | 85 |
| hold back the latest token, open or closed, and include the end slot | 147 |
| with the hidden, pause and end-of-input fixes above (token handed over when the next token starts) | 167 |

The latest token being held back follows from the hidden fix: a token's span, and so its input,
is not known until the next token starts.

**Fixes made** (commit "fix(#2004): PCEC inputs and live text match the verifier's reference
outputs"):

- Decision 2, revised: at end of input, only frames wholly covered by real audio
  (floor(samples/640)) are decoded. Windows after that are not run.
- Decision 3, revised:
  - The hidden input is the mean of the encoder hidden rows from the token's first frame up to,
    not including, the next token's first frame. For the last token, the span runs to the last
    decoded frame.
  - The first token's pause is the log2 bucket of the frames before it. Other pauses are unchanged.
  - The float input stays 0, and padding is unchanged. The blackout bucket is no longer produced.
- Decision 4, revised: a token is handed to PCEC when the next token starts, or at end of input.
  Live text is the flush over the handed-over tokens, including the end-of-input slot, so it
  never shows the latest token.

**Result** (Kotlin engine through the host harness, verifier's 28 clips, S/M/L × c16r4/c32r8, 100 ms
chunks, repeat 2, 37 ms chunk cross-check):
- Finals agree with the reference on 168/168 rows. For the 10 rows where the verifier showed only
  a word window (`6241-61943-0027` for S and L, and `edge_long_2277-149896`), the window matches.
- Partials agree on 167/168 at the verifier's first differing event. The exception is M c32r8
  `6241-61943-0027`, where the first event is `The men appeared.` and the reference has
  `The men appeared`; I found no input variant that changes it without breaking other rows.
- Partial events after each row's first difference were not visible to me, so only the verifier's
  re-run can confirm full partial-sequence parity.

## Black-box measurement data

Used only to choose between candidate input encodings by running the model:

- `hf-internal-testing/librispeech_asr_dummy` @ `5be91486e11a2d616f4ec5db8d3fd248585ac07a`
  (`clean/validation-00000-of-00001.parquet`, SHA-256
  `4e69a06fa5edc90921e5e7e39a7084881f8b3ed9c805c574f4f39c6fde27c603`): 73 LibriSpeech dev-clean clips.
- LibriSpeech `test-clean.tar.gz` (OpenSLR 12, SHA-256
  `39fde525e59672dc6d1551919b1478f724438a95aa55f874b576be21967e6c23`) with LibriSpeech-PC
  punctuated references (OpenSLR 145 `manifests.tar.gz`, SHA-256
  `96d4eae2222b29b66437a21959252419bcd4762e5042e71e023790171054d1c0`); 600 utterances, seed 2004.
- AMI IHM test shard `edinburghcstr/ami` @ `46f28f2503e2ec48f8867a84eef356c70476beab`
  (`ihm/test-00000-of-00004.parquet`, SHA-256
  `d95920dccc6924c15215239461bf5d1152fe07c9c61add073bd12da26dd602e0`); 400 utterances of ≥ 8 words.

Metrics: WER against the references, punctuation precision/recall against LibriSpeech-PC, and the
rate of output words absent from a LibriSpeech training-text word list (a proxy for the garbled
non-words the head is meant to repair).

Findings (M, `stream_c16r4`; raw greedy CTC WER 3.71 % on the 600 LibriSpeech utterances):

| question | result | chosen |
|---|---|---|
| PCEC input unit | frame-aligned inputs give repeated words; collapsed CTC tokens give clean text | tokens |
| pause bucket scale | log2 buckets: punctuation F1 0.817; linear (gap, capped at 8): 0.723; gap/2: 0.757; threshold-at-9 schemes: 0.787–0.790 | log2 |
| pause gap measure | blank frames after the previous run, or start-to-start distance: F1 0.817 vs 0.819 | blank frames |
| first token's pause | blackout 9, bucket of the leading silence, or 0: identical WER; 0 adds leading marks (85 vs 62 of 600 rows) | blackout |
| hidden pooling | WER on 300 utterances: mean over the run 3.76 %; start frame 3.78 %; last frame 3.81 %; up to the next token 3.75 %; from the previous token 4.75 % | run mean |
| hidden alignment | shifting the pooled frames by −2/−1/+1/+2 gives WER 35.1/7.7/4.2/7.7 % vs 4.2 % unshifted (150 utterances) | no shift |
| float input | 0: WER 3.65 %; token log-probability: 3.80 %; pause in seconds: 3.66 %; a constant −1: 41 %; pause in frames capped at 9: 22 % | 0 |

The float input is the main parity risk: the plausible encodings change the final text in 10–15 %
of rows against each other. About 10 % of rows get a mark before the first word under every
encoding tried; the renderer drops it.

## Harness checks run by the implementer

- S, M and L × all six tiers × 3 LibriSpeech dev-clean clips through the host harness: every
  combination opens, passes the open-time validation and the per-call shape checks, and gives
  identical partials and final text on a repeated run.
- M at `stream_c16r4` and `stream_c64r16`: final text identical with 100 ms and 37 ms chunks.
- After the rebase onto #2010 (seam replaced by `:core:executorch` types, `.pte` signature
  check added): the S/M/L × 6 tiers × 3 clips run passes again, and the M `stream_c16r4` and
  `stream_c64r16` rows (partials and finals) are byte-identical to the pre-rebase output.
- After the AC1 round-1 fixes and the rebase onto #2010 `98e28b06`: the verifier's 28 clips ×
  S/M/L × all six tiers (504 rows) all open, pass the shape checks, repeat identically and give
  the same final with 37 ms chunks. The rows are in `docs/evidence/2004/engine-round2/` for the
  verifier's round 2.
- Device results (S21) are in `docs/evidence/2004/README.md`. After the fixes, the AC2 test
  asserts the verifier's reference final for M c16r4 `84-121550-0015`, and the S21 matches it
  exactly.

## AC1 round 2 verdict and acceptance (#2011)

- Verifier round 2 (comment 7056733835): finals 504/504 across S/M/L × six tiers × 28 clips. Partial
  text differs on three rows, one event each: `7976-105575-0014` S `stream_c16r4` (event 3),
  `6241-61943-0027` M `stream_c16r4` (event 1), `6241-61943-0027` M `stream_c32r8` (event 0).
- Owner decision (comment 7057135985, 21 Nov 2026): AC1 parity accepted. The three partial-only
  differences are recorded here as known differences and accepted as practical parity.
- Engine rows: `docs/evidence/2004/engine-round2/`, 504 rows, SHA-256 of the rows file
  `c6eb8a99beebfab5d2d37bfb373781fe467dd7b6524b85546901a51fc5b11576`.
- The Python mirror used for the design (`sim.py`, `sim2.py`) lives outside this worktree at
  `~/.cache/cleanroom-2004/sim/` and is not committed here.

## Review follow-up (code review 7057262279)

- Fixed in code: PTE vector counts bounded before allocation; unbounded dynamic tensors rejected;
  encoder file name must be a plain name; config I/O errors typed; engine range checks overflow-safe;
  buffer read index; PythonBridgeBackend bounded wait; `et_bridge.py` exits after a failed request.
- The S4 licence copy and the smoke `.pte` are under `core/voice/src/test/resources/asr4all/`; the README there was corrected.
