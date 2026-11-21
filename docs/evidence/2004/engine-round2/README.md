# Engine outputs for AC1 round 2 (#2004)

Engine-side rows for the verifier to re-run AC1. They contain the engine's outputs only, no
reference outputs.

- `engine.jsonl` (SHA-256 `c6eb8a99beebfab5d2d37bfb373781fe467dd7b6524b85546901a51fc5b11576`): 504 rows,
  for S, M and L × all six tiers (`stream_c16r4`, `stream_c32r8`, `stream_c64r16`,
  `stream_c128r24`, `stream_c256r32`, `stream_c512r8`) × the verifier's 28 clips. The
  `stream_c16r4`/`stream_c32r8` rows are the 168-row matrix of the verifier's round 1; the other
  four tiers cover the rest of #2004's "every streaming configuration".
- `harness-config.json`: the `ASR4ALL_HARNESS_CONFIG` used, with local paths replaced by
  placeholders. Clips: 100 ms chunks (1600 samples), every row run twice (identical), and the
  final cross-checked with 37 ms chunks (identical).
- Clips: rebuilt from the verifier's published recipe. Each row's `wav_sha256` matches the
  verifier's list.
- Runtime: `tools/asr4all-cleanroom/et_bridge.py` on `executorch` 1.5.1, torch 2.14.1+cpu, Python
  3.12.13, x86-64 (Ryzen). Models at the pinned revisions in `../CLEANROOM.md`.
- Engine: the fix commit "fix(#2004): PCEC inputs and live text match the verifier's reference
  outputs", rebased onto #2010 head `98e28b06` with the `TensorDim` API update. The 504 rows were
  produced after that rebase. Its `stream_c16r4`/`stream_c32r8` rows are byte-identical to the
  same fix run before the rebase.

Row fields are described in `tools/asr4all-cleanroom/README.md`. Live text now holds back the
latest token until the next one starts and includes the end-of-input slot, so `partials` is
the live text after each 1600-sample chunk, recorded whenever it changed.
