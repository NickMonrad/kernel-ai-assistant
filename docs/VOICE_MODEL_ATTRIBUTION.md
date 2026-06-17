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
| Piper/VITS voices | Sherpa-ONNX GitHub `tts-models/<asset>.tar.bz2` release assets | ~64-116 MB each | Piper project/repository-level licence is permissive, but per-voice/dataset provenance still matters | Record source/licence for every release-exposed voice. |
| Semaine Piper/VITS | Sherpa-ONNX GitHub `tts-models/vits-piper-en_GB-semaine-medium.tar.bz2` | ~70 MB | Potential non-commercial licence path | #1268: Hidden from release builds; retained for debug/internal research. Replacement voice: en_GB-cori-high (training data public domain, model permissive MIT). |
| Cori high-quality (en_GB-cori-high) | Sherpa-ONNX GitHub `tts-models/vits-piper-en_GB-cori-high.tar.bz2` | ~116 MB | Sourced from `rhasspy/piper-voices` (MIT-licensed repo). Upstream model card: dataset = LibriVox (public domain). | Release-visible based on the documented upstream licence/provenance reviewed for #1268. |
| Kokoro experimental | Sherpa-ONNX GitHub `tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2` | ~130 MB | Kokoro upstream `hexgrad/Kokoro-82M` is Apache-2.0; NVIDIA's ONNX optimisation card is also Apache-2.0 and points back to `hexgrad/Kokoro-82M` | Kokoro can be considered likely permissive, but release docs should cite the upstream Kokoro model and the exact Sherpa-ONNX asset used by Jandal. |

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

## Code references

- `core/inference/src/main/java/com/kernel/ai/core/inference/download/KernelModel.kt` records the Hugging Face model URLs for STT ONNX assets.
- `core/voice/src/main/java/com/kernel/ai/core/voice/SherpaSttModelSpec.kt` records required ONNX/token file groups and online/offline runtime shape.
- `core/voice/src/main/java/com/kernel/ai/core/voice/SherpaKokoroVoice.kt` records the Kokoro asset name, download key, approximate size, speaker count, and Sherpa-ONNX release URL pattern.
- `core/voice/src/main/java/com/kernel/ai/core/voice/SherpaPiperVoice.kt` records Piper/VITS voice pack asset names, sizes, download keys, and the `releaseVisible` field used by `entriesForBuild()` to filter release vs debug/internal catalogues.
