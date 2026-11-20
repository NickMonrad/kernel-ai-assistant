# Clean-room record: asr4all ExecuTorch engine (#2004)

## Implementer

- Agent: OMP coding agent (Claude, `anthropic/claude-opus-5-5`) under Paseo.
- Paseo agent id `5db98e9b-d260-4968-a212-6ab56239c90b`, OMP session `01a1faa3-46e8-726a-9cfd-4bc552df42a5`,
  workspace `wks_079e763c547d1c43`, branch `feat/2004-asr4all-executorch-cleanroom`.
- Fresh session for this issue, with no earlier Build 8 or asr4all context. No long-term memory
  about asr4all was injected into the session, and no memory recall was used.

## Statement

I did not read, download, open, print, search or import any file outside the allowed list below.
In particular I did not read any script or code file in the `futo-org/asr4all-*` repositories
(`executorch/asr4all_executorch.py`, `onnx/asr4all_onnx.py`, `modeling_asr4all.py` or their
history), anything in `futo-org/voice-input` or transcribe.cpp, PR #1987 or branch
`feature/asr4all-engine`, PR #2002 or its branch and evidence, the #1988 spike, PR #2001 or
`docs/audits/build8-sol-audit.md`, `docs/evidence/1978/`, any `build8-*` worktree or branch, the
listed `/var/tmp` and `/tmp` paths, other agents' worktrees or logs, or any third-party write-up of
asr4all decoding. The only GitHub issue bodies read were #2004, #2003, #2007 and #1999 (bodies
only, no comments). No PR for #2003 existed when I searched (`gh pr list --search 2003`, empty),
so no #2003 code was read.

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
tensors were not inspected.

Non-asr4all material used: ExecuTorch's public Python runtime API, this repository's code,
public speech data for black-box measurement (below), and general knowledge of CTC decoding.

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
   run's first frame (0 → 0, 1 → 1, 2–3 → 2, …, capped at bucket 8); the first token uses the
   blackout bucket 9. Float input: 0. Padding positions: id `pad_in`, pause 0, zero hidden, 0.
4. Streaming calls take full rungs; partials use the flush method on the current state without
   committing it, and omit the closing-mark slot; the final text includes it.
5. Text: CTC-collapse all slots, drop blanks, apply `cap`/`allcaps` to the next piece/word,
   attach marks to the preceding text (marks before the first word are dropped), then
   sentence-initial capitals after `.`/`?`, as the model card describes.

Items 3 and 5 are the least certain; they were chosen by black-box measurement against public
references (see below). Parity results from the verifier decide whether they stand.

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
