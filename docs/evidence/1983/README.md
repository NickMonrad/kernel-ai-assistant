# #1983: asr4all vocabulary biasing, licence and source gate

Status: **stopped at the licence/source gate** (#1983 step 1). No port, no engine change, no device run.

## 1. Licence check (transcribe.cpp)

| Item | Result |
|---|---|
| Repository | `https://gitlab.futo.org/keyboard/transcribe.cpp` |
| Pinned commit | `1a4754486bfdc7285d30450b444d3960ec41ec5f` (`main` head, "Add asr4all-s", 2026-09-07) |
| Top-level licence | `LICENSE` at the pinned commit: MIT, "Copyright (c) 2026 The transcribe.cpp authors" |
| Exclusions | None for `src/arch/asr4all/` or `include/transcribe/asr4all.h`. No LICENSE file in those paths. |
| Vendored third-party | `ggml` (MIT) and `miniz` (MIT) only, per `THIRD-PARTY-LICENSES.md` at the pinned commit. Neither is in the asr4all path. |

Licence result: MIT, no exclusion covering asr4all. Passes.

## 2. Source check (does transcribe.cpp contain the biasing?)

The issue premise is that transcribe.cpp's JNI passes a `custom_dictionary` to the asr4all stream extension. At the pinned commit it does not:

- `include/transcribe/asr4all.h` (public asr4all extension): `transcribe_asr4all_stream_ext` has only `commit_frames`, `lookahead_frames`, `right_ctx_frames`. No dictionary, word list or boost field.
- `include/transcribe.h` (public API): the only bias-related text is the Whisper decoding prompt ("prompt to bias decoding. Today: whisper").
- `src/arch/asr4all/`: `asr4all.h`, `model.cpp`, `pcec.cpp`, `stream.cpp`, `encoder.cpp`, `frontend.cpp`, `weights.cpp`, `capabilities.cpp`. Case-insensitive search for dictionary, hotword, spotter, boost, keyword, lexicon, bias-word and custom-dict returns no asr4all match. The only "bias" hits are conv/attention bias terms.
- `src/transcribe-session.h`: no dictionary field.
- Decoding is plain greedy CTC (argmax, run collapse, blank drop) plus the PCEC token refiner. There is no word-spotting or prefix-boost step to port.

Source result: **the feature to port is not in transcribe.cpp at the pinned commit. Step 1 stop condition met.**

Not checked: branch `asr4all-updated-models` on the same repo (unprotected, head `2763a3e2`, "Move lang_token out of loop"). It is not the pinned `main`, so it is not a licence source for this port. Not read.

## 3. Derivation check (#1999 condition)

`docs/porting/families/asr4all.md` in transcribe.cpp says the C++ port targets the transformers reference, using the checkpoint's `modeling_asr4all.py` (`trust_remote_code`), not FUTO's driver scripts. The repository alone cannot show that this code is free of material derived from the unlicensed Python drivers (#1999). Moot for now because there is nothing to port, but it would need a written statement from transcribe.cpp's authors before any future port.

## 4. Clean-room contamination

This session read transcribe.cpp's asr4all sources listed above (`src/arch/asr4all/*`, `include/transcribe/asr4all.h`, `docs/porting/families/asr4all.md`). Per the #2004 forbidden list, this session must not implement or verify the clean-room ExecuTorch asr4all engine (`core/voice/.../asr4all/`) in future. It did not read FUTO driver scripts, `futo-org/voice-input`, PR #1987, PR #2002, #1988, or `docs/evidence/1978/`.

## 5. Decision needed (owner)

1. **Recommended:** stop #1983 as a port and raise glossary post-correction as a separate issue. #1983 explicitly allows this if step 1 stops the port.
2. Alternative: a from-scratch biasing design on the ExecuTorch engine. This is not a port, so acceptance criterion 1 (parity with transcribe.cpp) cannot be met. It needs its own design approval first. Hook points if approved:
   - `CtcStreamDecoder.accept` / `argmax`: bias per-frame logits before the greedy argmax.
   - `PcecStream.add` / `encode`: keep flags for biased tokens in the PCEC lattice.
3. Wake-verifier rerun (#2012, "biased towards Jandal") depends on biasing existing. It is blocked until 1 or 2 is decided.

Vocabulary and measurement sources, if option 2 is chosen: `docs/evidence/stt-eval/phrases.json` and `breadth-phrases.json`. Owner audio stays private, never in git.
