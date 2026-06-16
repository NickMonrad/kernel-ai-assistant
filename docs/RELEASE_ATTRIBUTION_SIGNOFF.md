# Release attribution sign-off snapshot

Parent issue: #1263  
Related: #868, #1257, #1258, #1268, #1014, #441

This document captures the release-attribution state after the Semaine release-visibility decision landed. It is a practical engineering sign-off aid, not legal advice.

## Status

This is a **pre-release-candidate attribution snapshot**. It verifies the release-exposed model/voice decisions that can be checked from source, but it does **not** replace the final release artefact audit in #1259.

Before Play Store publication, #1259 still needs to produce and inspect the actual release `.aab`, and #1263 still needs a generated dependency notice/SBOM from the resolved release variant.

## Release scope decisions captured

| Area | Decision | Status |
|---|---|---|
| Jandal source | Apache-2.0 repository licence plus `NOTICE` file. | Recorded. |
| Semaine Piper/VITS voice | Hidden from release builds; retained for debug/internal research. | Implemented by #1268 / PR #1269. |
| CoriHigh Piper/VITS voice | Release-visible British English fallback. Source: `rhasspy/piper-voices` repository listed as MIT; upstream model card lists LibriVox dataset with public-domain dataset licence. | Recorded as release-visible based on #1268 review. |
| Kokoro experimental voice | Can be considered release-visible from a licence perspective if exposed: Jandal downloads the Sherpa-ONNX `kokoro-int8-multi-lang-v1_0.tar.bz2` asset; upstream Kokoro 82M lineage is recorded as Apache-2.0. | Attribution recorded; keep experimental/product wording conservative. |
| Hey Jandal wake-word ONNX assets | Runtime uses openWakeWord-derived pipeline. Training README records that Stage 1/2 backbones come from openWakeWord and Stage 3 `hey_jandal.onnx` is trained from local positive clips. | Provenance now traceable in repo; final release should confirm owner consent for real recordings if any were used. |
| Runtime/downloader model files | Downloadable models and voices are not committed to the source repo unless explicitly bundled in a release artefact. | Final `.aab` inspection still required in #1259. |
| Play monetisation | First Play launch remains free-only; no Play Billing, paid app, IAP, or subscriptions. | Captured in #1264. |

## Release-exposed downloadable model and voice inventory

### Chat / embedding / local inference

| Asset | Release role | Source / terms status | Sign-off status |
|---|---|---|---|
| Gemma 4 E-2B LiteRT-LM | Required launch-compatible chat tier. | `litert-community/gemma-4-E2B-it-litert-lm`, repository page records Apache-2.0 in current docs. | OK for release inventory; verify exact model card in final sign-off notes. |
| Gemma 4 E-4B LiteRT-LM | Optional flagship chat tier. | `litert-community/gemma-4-E4B-it-litert-lm`, repository page records Apache-2.0 in current docs. | OK as optional release-exposed download. |
| EmbeddingGemma 300M + SentencePiece | Required embedding/RAG dependency when authenticated. | `litert-community/embeddinggemma-300m`, gated Gemma terms. | Keep gated-model language; do not describe as Apache-only. |
| EmbeddingGemma SM8550 variant | Deprecated/hidden in generic model management; may remain on existing devices. | Same gated Gemma terms as generic EmbeddingGemma. | Treat as deprecated / not promoted in release. |
| MiniLM-L6 intent classifier | Bundled/fallback classifier. | `sentence-transformers/all-MiniLM-L6-v2`, documented as Apache-2.0. | Confirm bundle presence during #1259 artefact audit. |
| FunctionGemma mobile actions | Optional/experimental if surfaced. | Gated Gemma terms. | Keep optional/experimental; do not promote in store copy unless current release UI exposes it intentionally. |

### Speech-to-text

| Asset | Release role | Source / terms status | Sign-off status |
|---|---|---|---|
| Android native STT | Platform fallback/option. | Android platform/system service. | Store/privacy copy must not promise guaranteed offline operation for this path. |
| Vosk Android runtime | Offline STT runtime. | Apache-2.0 runtime dependency. | Runtime notice required if bundled. |
| Vosk model files | Offline STT data if exposed. | Per-model provenance must be checked against final selected/downloaded model. | Keep as sign-off item until exact release model is known. |
| Sherpa Zipformer STT | Optional Sherpa STT model. | `csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21`, documented as Apache-2.0 / upstream lineage. | OK if release-exposed; cite exact HF export page. |
| Sherpa SenseVoice STT | Optional Sherpa STT model. | ONNX export from SenseVoice/FunASR. FunASR code is MIT; model weights use separate FunASR model licence. | Release docs must not describe as MIT-only. |
| Sherpa Whisper tiny.en STT | Optional Sherpa STT model. | `csukuangfj/sherpa-onnx-whisper-tiny.en` plus upstream `openai/whisper-tiny.en`, Apache-2.0. | OK if release-exposed; cite both export and upstream model lineage. |
| Sherpa Paraformer STT | Optional Sherpa STT model. | HF export lists Apache-2.0; upstream ModelScope/FunASR lineage and model-weight terms should be recorded. | Release docs must not simplify to MIT-only. |

### Text-to-speech

| Asset | Release role | Source / terms status | Sign-off status |
|---|---|---|---|
| Android TTS | Platform fallback. | Android platform/system service. | OK; describe as platform fallback. |
| Piper/VITS voice packs | User-selectable Sherpa/Piper voices. | Sherpa-ONNX release assets; per-voice dataset provenance still matters. | Release-visible voices must retain per-voice provenance in docs. |
| Semaine | Debug/internal only. | Potential non-commercial/share-alike risk. | Hidden from release; not a Play launch blocker after #1268. |
| CoriHigh | Release-visible replacement for Semaine. | `rhasspy/piper-voices` MIT repo; model card lists LibriVox public-domain dataset. | OK for release-visible catalogue based on #1268. |
| Kokoro experimental | Optional/experimental Sherpa Kokoro TTS. | Exact app asset is Sherpa-ONNX `kokoro-int8-multi-lang-v1_0.tar.bz2`; upstream Kokoro 82M lineage recorded as Apache-2.0. | OK from current attribution trace; avoid overclaiming quality/stability in store copy. |

## Wake-word asset provenance

The bundled Hey Jandal wake-word path has three ONNX stages:

1. `melspectrogram.onnx` — openWakeWord Stage 1 backbone.
2. `embedding_model.onnx` — openWakeWord Stage 2 backbone.
3. `hey_jandal.onnx` — Jandal-specific Stage 3 classifier.

`training/wakeword/README.md` records that Stage 3 training used 954 positive clips:

- 174 from local real recordings, expanded from 29 originals through augmentation;
- 780 generated from Piper TTS across 11 voices and 13 phonetic variants.

Release treatment:

- keep openWakeWord Apache-2.0 attribution in `NOTICE`;
- keep the training README as the source-of-truth provenance for the generated model;
- before final Play release, the maintainer should confirm that the local real recordings are owned/consented for use in the bundled wake-word model.

## In-app open-source notices decision

For first launch, an in-app open-source licences screen is **deferred** unless the generated release dependency notice/SBOM or bundled SDK notices show a licence that requires in-app reproduction.

Required first-launch minimum:

- repository `LICENSE` and `NOTICE` remain present;
- `docs/LEGAL_AND_ATTRIBUTION.md`, `docs/VOICE_MODEL_ATTRIBUTION.md`, and this file remain linked from release/internal launch notes;
- Play Store copy does not claim ownership of upstream models/voices;
- final release artefact audit confirms no unexpected bundled model files or debug-only dependencies.

## Final release artefact audit still required

The following items cannot be completed from static source docs alone:

- generate release dependency notices or SBOM from the resolved release variant;
- inspect the release `.aab` contents for bundled models, debug artefacts, and native ABIs;
- confirm exact runtime dependency set after Gradle resolution;
- confirm whether any licence notice needs to appear inside the app rather than only in repository/docs;
- record final versionCode/versionName, commit SHA, and release artefact hash/path.

Track those in #1259 and feed the result back into #1263 before closing the launch gate.
