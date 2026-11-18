# Voice, speech, and activation model attribution

This document supplements `docs/LEGAL_AND_ATTRIBUTION.md` for #868. It captures the concrete app-side source locations for voice, speech-to-text, text-to-speech, and voice-activation assets.

## Voice activation assets

| Asset family | App use | Source / lineage | Licence position | Release action |
|---|---|---|---|---|
| Hey Jandal ONNX activation assets | Bundled app assets used for local voice activation | Derived from the openWakeWord ecosystem | openWakeWord is Apache-2.0 | Keep openWakeWord attribution in `NOTICE`; record local generated asset provenance before Play Store release. |

## Sherpa-ONNX STT models

The app catalogue downloads the Sherpa STT ONNX files from Hugging Face `csukuangfj/*` model repositories. The runtime specs in `SherpaSttModelSpec.kt` define whether each model is streaming/online or offline/final-only.

| Engine | App runtime shape | App download source | Files used by app | Licence position | Release action |
|---|---|---|---|---|---|
| Zipformer | Streaming / online ONNX recogniser | `csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21` | encoder, decoder, joiner, tokens | Hugging Face page lists Apache-2.0 and identifies the upstream model/training lineage | Record source/model-card link in release notices if exposed. |
| SenseVoice | Offline / final-only ONNX recogniser | `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` | model, tokens | Converted from `FunAudioLLM/SenseVoice`; upstream repo points to FunASR licensing. FunASR code is MIT, but FunASR model weights use the separate FunASR Model Open Source License Agreement. | Record both the ONNX export source and upstream SenseVoice/FunASR model licence before release. |
| Whisper tiny.en | Offline / final-only ONNX recogniser | `csukuangfj/sherpa-onnx-whisper-tiny.en` | encoder, decoder, tokens | Upstream `openai/whisper-tiny.en` model card lists Apache-2.0; `csukuangfj` ONNX export page is sparse but code comments identify Apache-2.0 | Record both the ONNX export source and upstream `openai/whisper-tiny.en` Apache-2.0 source before release. |
| Paraformer | Streaming / online ONNX recogniser | `csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en` | encoder, decoder, tokens | Hugging Face export page lists Apache-2.0 and identifies the ModelScope/DAMO source. The broader FunASR code repo is MIT, but FunASR model weights use the separate FunASR Model Open Source License Agreement. | Record the HF export source, ModelScope source URL, and FunASR model licence terms; do not simplify the model asset to MIT-only. |

## Sherpa-ONNX TTS voice packs

The app downloads TTS voice packs from Sherpa-ONNX GitHub release assets under the `tts-models` release path.

| Voice family | App download source | Approx. size | Licence position | Release action |
|---|---|---:|---|---|
| Piper/VITS voices | Sherpa-ONNX GitHub `tts-models/<asset>.tar.bz2` release assets | ~64-116 MB each | Piper project/repository-level licence is permissive, but per-voice/dataset provenance still matters | All Piper/VITS voices, including CoriHigh, are debug/internal only for v0.1 under the #1263 exclusion rule. The other catalogue entries were reviewed in [PIPER_VOICE_LICENCE_REVIEW.md](release-audit/PIPER_VOICE_LICENCE_REVIEW.md) and all remain HOLD (debug/internal only): nine are fine-tuned from the research-only Lessac voice or the CC BY-NC-SA Ryan voice, and the rest have non-commercial, all-rights-reserved or untraceable terms. |
| Semaine Piper/VITS | Sherpa-ONNX GitHub `tts-models/vits-piper-en_GB-semaine-medium.tar.bz2` | ~70 MB | Potential non-commercial licence path | Excluded from release builds under #1258; retained for debug/internal use. CoriHigh remains available for debug/internal development; its model card identifies LibriVox as its dataset. |
| Cori high-quality (en_GB-cori-high) | Jandal package: [Sherpa-ONNX release asset](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-cori-high.tar.bz2); upstream files: [`rhasspy/piper-voices` at revision `5041a7a49ae5b3a0f30e6837769eb03fdbb231bd`](https://huggingface.co/rhasspy/piper-voices/tree/5041a7a49ae5b3a0f30e6837769eb03fdbb231bd/en/en_GB/cori/high) | ~116 MB | The pinned repository [README](https://huggingface.co/rhasspy/piper-voices/blob/5041a7a49ae5b3a0f30e6837769eb03fdbb231bd/README.md) declares repository metadata `license: mit`. The pinned [model card](https://huggingface.co/rhasspy/piper-voices/blob/5041a7a49ae5b3a0f30e6837769eb03fdbb231bd/en/en_GB/cori/high/MODEL_CARD) describes a UK English female voice and records LibriVox as the dataset with `License: public domain`; it does not name a separate copyright holder for the voice weights. | Excluded from v0.1 release with the Sherpa/Piper/eSpeak runtime path. These URLs document the upstream reference and Jandal download source, not a hash/content match between them. The downloaded archive and its `espeak-ng-data` provenance remain unresolved under #1474. |
| Kokoro experimental | Sherpa-ONNX GitHub `tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2` | ~130 MB | Kokoro upstream `hexgrad/Kokoro-82M` is Apache-2.0; NVIDIA's ONNX optimisation card is also Apache-2.0 and points back to `hexgrad/Kokoro-82M` | Not release-visible for v0.1; #1014 scopes Kokoro to debug/post-launch. This attribution trace is not a release-signoff conclusion. |

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

Jandal does not download `nvidia/kokoro-82M-onnx-opt` directly. The app downloads the Sherpa-ONNX `kokoro-int8-multi-lang-v1_0.tar.bz2` release asset. The NVIDIA card is still useful attribution evidence because it points to the same upstream Kokoro 82M model family and records Apache-2.0 terms for the ONNX optimisation.

For release attribution, cite both:

- app package source: Sherpa-ONNX `tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2`;
- upstream model lineage: `hexgrad/Kokoro-82M` / `nvidia/kokoro-82M-onnx-opt`, Apache-2.0.

## Whisper source notes

Jandal does not download `openai/whisper-tiny` or `openai/whisper-tiny.en` directly. The app downloads the Sherpa-ONNX `csukuangfj/sherpa-onnx-whisper-tiny.en` ONNX export files.

For release attribution, cite both:

- app package source: `csukuangfj/sherpa-onnx-whisper-tiny.en`;
- upstream model lineage: `openai/whisper-tiny.en`, Apache-2.0.

Use `openai/whisper-tiny.en`, not multilingual `openai/whisper-tiny`, because Jandal's app-side model selection is the English-only `tiny.en` ONNX export.

## asr4all source notes

Planned offline STT default (#1977, #1980). Jandal will download the unmodified FUTO ONNX export files from FUTO's Hugging Face repositories, `futo-org/asr4all-s` (28M) and `futo-org/asr4all-m` (56M), and run them with ONNX Runtime.

Licence: FUTO Model Weights License (Restricted Use) 1.0, in each repository's `LICENSE`. The two copies are byte-identical (checked 18 Nov 2026). The text is bundled at `app/src/main/assets/legal/FUTO-Model-Weights-License-1.0.txt` and shown offline on the Open-source licences screen, which also lists asr4all in `NOTICE`.

Release obligations:

- Commercial use is permitted. No attribution is required for model outputs.
- The Limitations section prohibits surveillance, profiling and discrimination by voice, intelligence gathering, monitoring people in custody, targeting protected activity, and circumvention. Jandal's Terms of Use (section 5) bind users to these Limitations, as the licence requires for hosted or embedded services.
- Recipients must receive the terms. The app bundles them and the Terms of Use link to them.
- Do not modify, re-quantise, or rehost the weights without first adding a modification notice and the FUTO terms alongside the files.
- The licence prohibits using the name, trademarks, service marks, or logos of FUTO for any purpose, including endorsing or promoting Derivative Models or suggesting a relationship with FUTO beyond licensor and licensee. Jandal's own rule is stricter: FUTO's name appears only as the licence's own title ("FUTO Model Weights License (Restricted Use) 1.0"), and nowhere else in user-facing UI, marketing copy, or the NOTICE text, except (a) the verbatim licence text, which must be reproduced unaltered, and (b) the pinned source URLs in NOTICE, which are factual locators for the licence's canonical, revision-pinned source. The bundled licence file name `legal/FUTO-Model-Weights-License-1.0.txt` is kept because `LegalPrivacyScreen.kt` loads the asset by that name; it is not shown to users, and renaming it would be a code change, not a licence requirement.

Provenance of the planned Kotlin engine (#1987): it is a port of FUTO's Python reference driver `onnx/asr4all_onnx.py`, pinned at `futo-org/asr4all-s` revision `87a6b5d093723fe37e9f69ad26307a3b95a31af0` (the same file blob exists at `futo-org/asr4all-m` revision `7d6e9145109a5bece4f05831d046e93d27e38a99`). The weights licence covers the weights only. The reference driver has no licence we have found, so the port is not cleared for release. No code is copied from `futo-org/voice-input`, which uses the non-commercial FUTO Source First licence.

**Open blocker (gates #1987 and #1980; tracked in #1999):** before either merges or the asr4all default ships, one of the following must be recorded here:

1. a written licence from FUTO covering the reference driver (owner is contacting FUTO; record the grant, its scope, and the date here when received); or
2. a clean-room rewrite of the ONNX Runtime pipeline that does not derive from the Python driver, verified by parity against the official ONNX bundles. The implementer must not have read `onnx/asr4all_onnx.py`, nor #1987's Kotlin engine or its review threads, since that engine is a port of the same file; they work only from `onnx/metadata.json`, the model card/README and observed model I/O, and parity is checked against reference outputs (method and results recorded in the PR).

**Merge sequencing (owner-agreed):** #1986 (licence notices and terms) merges first, then #1987 (engine), then #1793 is rebased onto that. #1987 and #1980 must not merge ahead of this blocker being resolved.

## Code references

- `core/inference/src/main/java/com/kernel/ai/core/inference/download/KernelModel.kt` records the Hugging Face model URLs for STT ONNX assets.
- `core/voice/src/main/java/com/kernel/ai/core/voice/SherpaSttModelSpec.kt` records required ONNX/token file groups and online/offline runtime shape.
- `core/voice/src/main/java/com/kernel/ai/core/voice/SherpaKokoroVoice.kt` records the Kokoro asset name, download key, approximate size, speaker count, and Sherpa-ONNX release URL pattern.
- `core/voice/src/main/java/com/kernel/ai/core/voice/SherpaPiperVoice.kt` records Piper/VITS voice pack asset names, sizes, download keys, and the `releaseVisible` field used by `entriesForBuild()` to filter release vs debug/internal catalogues.
