# asr4all test data (#2004)

- `m/` and `s/`: unmodified `config.json`, `release_geom.json`, `preprocessor_config.json` and
  `executorch/xnnpack_int8/metadata.json` from `futo-org/asr4all-m` revision
  `7d6e9145109a5bece4f05831d046e93d27e38a99` and `futo-org/asr4all-s` revision
  `87a6b5d093723fe37e9f69ad26307a3b95a31af0` (SHA-256 in `docs/evidence/2004/CLEANROOM.md`). They
  are model configuration covered by the FUTO Model Weights License (Restricted Use) 1.0,
  `app/src/main/assets/legal/FUTO-Model-Weights-License-1.0.txt`. No weights or `.pte` files are
  committed.
- `fixtures/1272-128104-0000_m_stream_c16r4*`: outputs recorded by running the M export's
  `stream_c16r4`, `pcec_c16` and `pcec_c16_flush` methods (ExecuTorch 1.5.1 Python runtime, x86-64)
  on LibriSpeech dev-clean utterance 1272-128104-0000 (CC BY 4.0, via
  `hf-internal-testing/librispeech_asr_dummy` revision `5be91486e11a2d616f4ec5db8d3fd248585ac07a`).
  `*_logits.f32` holds the encoder's `[160, 257]` float32 little-endian logits; the JSON holds the
  greedy CTC tokens and the PCEC output slots.
