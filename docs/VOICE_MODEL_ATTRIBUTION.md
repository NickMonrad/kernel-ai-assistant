# Voice, speech, and activation model attribution

This document supplements `docs/LEGAL_AND_ATTRIBUTION.md` for #868. It captures the concrete app-side source locations for voice, speech-to-text, text-to-speech, and voice-activation assets.

## Voice activation assets

| Asset family | App use | Source / lineage | Licence position | Release action |
|---|---|---|---|---|
| Hey Jandal ONNX activation assets | Debug-only assets used for local voice-activation development | openWakeWord v0.5.1 shared Stage 1/2 exports; Jandal-generated Stage 3 classifier | Stage 1/2 file-specific terms remain unresolved; Stage 3 provenance is documented in `training/wakeword/README.md` | All three models are excluded from v0.1 release artifacts; enable release wake-word only after authoritative terms or replacement are recorded. |

## Sherpa-ONNX STT models

The app catalogue downloads the Sherpa STT ONNX files from Hugging Face `csukuangfj/*` model repositories. The runtime specs in `SherpaSttModelSpec.kt` define whether each model is streaming/online or offline/final-only.

| Engine | App runtime shape | App download source | Files used by app | Licence position | Release action |
|---|---|---|---|---|---|
| Zipformer (removed in #1982) | Streaming / online ONNX recogniser | `csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21` | encoder, decoder, joiner, tokens | Hugging Face page lists Apache-2.0 and identifies the upstream model/training lineage | Record source/model-card link in release notices if exposed. |
| SenseVoice | Offline / final-only ONNX recogniser | `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` | model, tokens | Converted from `FunAudioLLM/SenseVoice`; upstream repo points to FunASR licensing. FunASR code is MIT, but FunASR model weights use the separate FunASR Model Open Source License Agreement. | Record both the ONNX export source and upstream SenseVoice/FunASR model licence before release. |
| Whisper tiny.en | Offline / final-only ONNX recogniser | `csukuangfj/sherpa-onnx-whisper-tiny.en` | encoder, decoder, tokens | Upstream `openai/whisper-tiny.en` model card lists Apache-2.0; `csukuangfj` ONNX export page is sparse but code comments identify Apache-2.0 | Record both the ONNX export source and upstream `openai/whisper-tiny.en` Apache-2.0 source before release. |
| Paraformer | Streaming / online ONNX recogniser | `csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en` | encoder, decoder, tokens | Hugging Face export page lists Apache-2.0 and identifies the ModelScope/DAMO source. The broader FunASR code repo is MIT, but FunASR model weights use the separate FunASR Model Open Source License Agreement. | Record the HF export source, ModelScope source URL, and FunASR model licence terms; do not simplify the model asset to MIT-only. |

## Sherpa-ONNX TTS voice packs

The app downloads TTS voice packs from Sherpa-ONNX GitHub release assets under the `tts-models` release path.

| Voice family | App download source | Approx. size | Licence position | Release action |
|---|---|---:|---|---|
| Piper/VITS voices | Sherpa-ONNX GitHub `tts-models/<asset>.tar.bz2` release assets | ~64-116 MB each | Upstream `rhasspy/piper-voices` makes no licence grant over voice weights; the repository's `mit` tag is not a per-voice clearance. Per-voice dataset and base-model lineage decides. | All Piper/VITS voices, including CoriHigh, are debug/internal only for v0.1 under the #1263 exclusion rule. The other catalogue entries are reviewed in [docs/legal/piper-voice-review.md](legal/piper-voice-review.md). None is releasable as downloaded. Each Sherpa archive was weight-compared against upstream, and each contains the older pre-retrain weights. Ten of them were fine-tuned from Lessac (research-only data) or Ryan (non-commercial data). LessacHigh and RyanHigh are themselves trained from scratch on those restricted datasets, so no retrain can clear them. Jenny, Alba, Aru, VCTK and Joe have upstream retrains on clean bases. They could be reconsidered only after Jandal downloads those retrained weights, repackaged for Sherpa, with their attribution added to `NOTICE`. Semaine, Amy, Alan, Lessac, Ryan, Southern English Female and Northern English Male have no clean path. |
| Semaine Piper/VITS | Sherpa-ONNX GitHub `tts-models/vits-piper-en_GB-semaine-medium.tar.bz2` | ~70 MB | Non-commercial: the [DFKI SEMAINE dataset](https://github.com/marytts/dfki-semaine-data/blob/cbeb97b9bb0deecf4355220fcfba280a7b30983a/README.md) is CC BY-NC-SA 4.0 | Excluded from release builds under #1258; retained for debug/internal use. CoriHigh remains available for debug/internal development; its model card identifies LibriVox as its dataset. |
| Cori high-quality (en_GB-cori-high) | Jandal package: [Sherpa-ONNX release asset](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-cori-high.tar.bz2); upstream files: [`rhasspy/piper-voices` at revision `5041a7a49ae5b3a0f30e6837769eb03fdbb231bd`](https://huggingface.co/rhasspy/piper-voices/tree/5041a7a49ae5b3a0f30e6837769eb03fdbb231bd/en/en_GB/cori/high) | ~116 MB | The pinned [model card](https://huggingface.co/rhasspy/piper-voices/blob/5041a7a49ae5b3a0f30e6837769eb03fdbb231bd/en/en_GB/cori/high/MODEL_CARD) records a UK English female voice "Trained from scratch" on LibriVox, `License: public domain`. The trainer's page, [brycebeattie.com/files/tts](https://brycebeattie.com/files/tts/), lists Cori (high) as "License: public domain" and adds "Feel free to use these for any legal and ethical purpose". The repository's `license: mit` tag is not a grant over voice weights (see [piper-voice-review.md](legal/piper-voice-review.md)), so it is not a basis for clearance. | Excluded from v0.1 release with the Sherpa/Piper/eSpeak runtime path. By value, the archive's ONNX initializers equal 100% of the parameters in the upstream `.onnx` at the pinned revision (LFS oid `470b4dd634c98f8a4850d7626ffc3dfc90774628eeef6605a6dd8f88f30a5903`, unchanged through `e929968`); only Sherpa metadata differs. #1474 (closed) accepted the eSpeak-ng GPL posture, but source-to-binary provenance for the archive's `espeak-ng-data` is not established. |
| Kokoro (experimental) | `software-mansion/Kokoro-82M-ExecuTorch` at `v0.11.0` on Hugging Face (ExecuTorch FP32 export, optional on-demand download) | ~351 MB | Hugging Face card lists Apache-2.0 for the export; Apache-2.0 upstream Kokoro model and misaki lexicon | Release-visible as an optional on-demand download (#2117). The optional Brown Corpus-derived tagger is for non-commercial use only, not cleared for commercial use, and treated as optional by Phonemis. The pack is not bundled in the APK. |

## SenseVoice source notes

Jandal does not download `FunAudioLLM/SenseVoice` directly. The app downloads the Sherpa-ONNX `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` ONNX export files.

The ONNX export lineage points back to `FunAudioLLM/SenseVoice`. That repository's `LICENSE` file refers to FunASR licensing. FunASR has an MIT `LICENSE` for the codebase, but it also has a separate `MODEL_LICENSE` covering model weights and derivatives. Release attribution should not describe the SenseVoice model asset as simple MIT-only.

For release attribution, cite:

- app package source: `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`;
- upstream model/source lineage: `FunAudioLLM/SenseVoice` / `modelscope/FunASR`;
- licence terms: FunASR model licence for weights plus MIT for the FunASR codebase where relevant.

## Paraformer source notes

Jandal does not download the ModelScope/DAMO source directly. The app downloads the Sherpa-ONNX `csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en` ONNX export files.

The Hugging Face export page lists Apache-2.0 and states the ONNX files are converted from `https://www.modelscope.cn/models/damo/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8404-online/summary`.

The broader upstream FunASR repository has an MIT `LICENSE` for code, but also has a separate `MODEL_LICENSE` for model weights and derivatives. Because Jandal uses converted model weights, release attribution should record the HF Apache-2.0 export page, the ModelScope source URL, and the FunASR model-weight licence terms. Do not simplify the Paraformer model asset to MIT-only.

For release attribution, cite:

- app package source: `csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en`, Apache-2.0 on the HF export page;
- upstream model source: `damo/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8404-online` on ModelScope;
- upstream code/toolkit licence: FunASR MIT;
- model-weight licence terms: FunASR `MODEL_LICENSE` where applicable.

## Kokoro source notes

The release Kokoro pack is downloaded on demand from the `software-mansion/Kokoro-82M-ExecuTorch` Hugging Face repository at tag `v0.11.0`, not from Sherpa-ONNX. The Hugging Face card lists Apache-2.0. It is an ExecuTorch export of `hexgrad/Kokoro-82M` with misaki phonemiser lexicons. It is not bundled in the APK.

`KOKORO_TAGS` (`phonemizer/en-us/tags.json`) is an optional part-of-speech tagger derived from the Brown Corpus. The Brown Corpus is for non-commercial use only; the tagger is not cleared for commercial use. Phonemis treats the tagger as optional.

For release attribution, cite:

- app package source: `software-mansion/Kokoro-82M-ExecuTorch` at `v0.11.0`, Apache-2.0;
- upstream model lineage: `hexgrad/Kokoro-82M`, Apache-2.0.

## Whisper source notes

Jandal does not download `openai/whisper-tiny` or `openai/whisper-tiny.en` directly. The app downloads the Sherpa-ONNX `csukuangfj/sherpa-onnx-whisper-tiny.en` ONNX export files.

For release attribution, cite both:

- app package source: `csukuangfj/sherpa-onnx-whisper-tiny.en`;
- upstream model lineage: `openai/whisper-tiny.en`, Apache-2.0.

Use `openai/whisper-tiny.en`, not multilingual `openai/whisper-tiny`, because Jandal's app-side model selection is the English-only `tiny.en` ONNX export.

## asr4all source notes

Offline STT (#1977, #2005): M is the default size and L is an optional size. Jandal downloads the unmodified FUTO ExecuTorch INT8 (XNNPACK) export files (`executorch/xnnpack_int8/asr_encoder.pte` and its `metadata.json`, with `config.json`, `release_geom.json` and `preprocessor_config.json`) from FUTO's Hugging Face repositories, `futo-org/asr4all-m` (M, about 60 MB) and `futo-org/asr4all-l` (L, about 101 MB), and runs them with ExecuTorch through the shared runtime module (#2003). The ONNX Runtime route (#1978, #1987) was superseded on 21 Nov 2026 (#2007).

Licence: FUTO Model Weights License (Restricted Use) 1.0, in each repository's `LICENSE`. The two copies are byte-identical (checked 18 Nov 2026). The text is bundled at `app/src/main/assets/legal/FUTO-Model-Weights-License-1.0.txt` and shown offline on the Open-source licences screen, which also lists asr4all in `NOTICE`.

Release obligations:

- Commercial use is permitted. No attribution is required for model outputs.
- The Limitations section prohibits surveillance, profiling and discrimination by voice, intelligence gathering, monitoring people in custody, targeting protected activity, and circumvention. Jandal's Terms of Use (section 5) bind users to these Limitations, as the licence requires for hosted or embedded services.
- Recipients must receive the terms. The app bundles them and the Terms of Use link to them.
- Do not modify, re-quantise, or rehost the weights without first adding a modification notice and the FUTO terms alongside the files.
- The licence prohibits using the name, trademarks, service marks, or logos of FUTO for any purpose, including endorsing or promoting Derivative Models or suggesting a relationship with FUTO beyond licensor and licensee. Jandal's own rule is stricter: FUTO's name appears only as the licence's own title ("FUTO Model Weights License (Restricted Use) 1.0"), and nowhere else in user-facing UI, marketing copy, or the NOTICE text, except (a) the verbatim licence text, which must be reproduced unaltered, and (b) the pinned source URLs in NOTICE, which are factual locators for the licence's canonical, revision-pinned source. The bundled licence file name `legal/FUTO-Model-Weights-License-1.0.txt` is kept because `LegalPrivacyScreen.kt` loads the asset by that name; it is not shown to users, and renaming it would be a code change, not a licence requirement.

Provenance of the Kotlin engine (#2004, `core/voice/src/main/java/com/kernel/ai/core/voice/asr4all/`): a clean-room implementation (#1999 option 2). FUTO's Python reference drivers (`executorch/asr4all_executorch.py`, `onnx/asr4all_onnx.py`) have no licence we have found, so the engine was written by a fresh agent that did not read them, #1987's ONNX engine or its threads, or any other forbidden material listed in #2004. It was built only from the model card, `config.json`, `release_geom.json`, `preprocessor_config.json`, the tokenizer files, `benchmarks.json`, `executorch/xnnpack_int8/metadata.json`, and the observed I/O of `asr_encoder.pte` run as a black box, at `futo-org/asr4all-s` revision `87a6b5d093723fe37e9f69ad26307a3b95a31af0`, `futo-org/asr4all-m` revision `7d6e9145109a5bece4f05831d046e93d27e38a99` and `futo-org/asr4all-l` revision `79d1c23bc8f1174cee576b40bbaa0118bd95abed`. The implementer's record (identity, every input with its SHA-256, the observed method I/O and the decisions taken from it) is `docs/evidence/2004/CLEANROOM.md`. No code is copied from `futo-org/voice-input`, which uses the non-commercial FUTO Source First licence.

**AC1 parity accepted (gates #1980; tracked in #1999).** The verifier's round-2 run (#2011 comment 7056733835) matched all 504 final texts (S/M/L × six tiers × 28 clips) and differed in partial text on three rows, one event each: `7976-105575-0014` S `stream_c16r4`, `6241-61943-0027` M `stream_c16r4` and `6241-61943-0027` M `stream_c32r8`. The owner accepted these as practical parity on 21 Nov 2026 (#2011 comment 7057135985). Record: `docs/evidence/2004/CLEANROOM.md` ("AC1 round 2 verdict and acceptance") and `docs/evidence/2004/engine-round2/`. The release review for #1980 still applies. A written licence from FUTO covering the reference drivers would also be recorded here if received.

## Silero VAD (utterance endpointing, #2078)

| Asset | App use | Source | Licence position | Checksum |
|---|---|---|---|---|
| `core/voice/src/vad/assets/silero_vad.onnx` (643,854 bytes) | Speech/silence decision per 32 ms frame in the asr4all mic reader (`SileroVad.kt`), run on onnxruntime-android | k2-fsa export, `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx`; the model is Silero VAD v4, 16 kHz branch only, from `snakers4/silero-vad` | MIT (`snakers4/silero-vad` LICENSE, Copyright Silero Team). The k2-fsa export carries the same terms; no Sherpa runtime code is used on this path. | sha256 `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |

Release action: the licence entry is in `NOTICE` and `app/src/main/assets/legal/NOTICE.txt` (kept identical), which ships with the release build. Re-check both if the model file changes.

## Code references

- `core/inference/src/main/java/com/kernel/ai/core/inference/download/KernelModel.kt` records the Hugging Face model URLs for STT ONNX assets.
- `core/voice/src/main/java/com/kernel/ai/core/voice/SherpaSttModelSpec.kt` records required ONNX/token file groups and online/offline runtime shape.
- `core/voice/src/main/java/com/kernel/ai/core/voice/kokoro/KokoroSetup.kt` lists the Kokoro ExecuTorch pack files and the approximate on-demand download size.
- `core/voice/src/main/java/com/kernel/ai/core/voice/SherpaPiperVoice.kt` records Piper/VITS voice pack asset names, sizes, download keys, and the `releaseVisible` field used by `entriesForBuild()` to filter release vs debug/internal catalogues.
