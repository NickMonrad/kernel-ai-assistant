# Release attribution sign-off snapshot

Parent issue: #1263  
Related: #868, #1257, #1258, #1268, #1014, #441

This document captures the release-attribution state after the Semaine release-visibility decision landed. It is a practical engineering sign-off aid, not legal advice.

## Status

The release AAB audit and runtime dependency comparison recorded by #1259 / PR #1273 are historical, not current release acceptance. The current release wiring uses the custom Inflect-enabled Sherpa AAR; #1474 tracks the unresolved Piper/eSpeak provenance, component terms, notices, corresponding-source path, and candidate reconciliation. Formal SBOM (CycloneDX/SPDX) and generated third-party notices have not been produced.

## Pre-release-candidate status

This remains a **pre-release-candidate attribution snapshot**, not a signed release candidate or Play acceptance. The historical AAB inspection does not close #1474; no candidate-linked SBOM/notices or exact release-artefact reconciliation are recorded here.

## Release scope decisions captured

| Area | Decision | Status |
|---|---|---|
| Jandal source | Apache-2.0 repository licence plus `NOTICE` file. | Recorded. |
| Semaine Piper/VITS voice | Hidden from release builds; retained for debug/internal research. | Implemented by #1268 / PR #1269. |
| CoriHigh Piper/VITS voice | Release-visible British English fallback. Source: `rhasspy/piper-voices` repository listed as MIT; upstream model card lists LibriVox dataset with public-domain dataset licence. | Recorded as release-visible based on #1268 review. |
| Kokoro experimental voice | Can be considered release-visible from a licence perspective if exposed: Jandal downloads the Sherpa-ONNX `kokoro-int8-multi-lang-v1_0.tar.bz2` asset; upstream Kokoro 82M lineage is recorded as Apache-2.0. | Attribution recorded; keep experimental/product wording conservative. |
| Hey Jandal wake-word ONNX assets | Runtime uses openWakeWord-derived pipeline. Training README records that Stage 1/2 backbones come from openWakeWord and Stage 3 `hey_jandal.onnx` is trained from local positive clips. | Nick (maintainer) confirmed in [#1263 comment 6415038577](https://github.com/NickMonrad/kernel-ai-assistant/issues/1263#issuecomment-6415038577) that the real recordings are of him and his wife, made with consent for this use; additional synthetic audio was generated with Piper TTS, and no other human recordings were used. |
| Runtime/downloader model files | Downloadable models and voices are not committed to the source repo unless explicitly bundled in a release artefact. | Final `.aab` inspection still required in #1259. |
| Play monetisation | First Play launch remains free-only; no Play Billing, paid app, IAP, or subscriptions. | Captured in #1264. |

## Release-exposed downloadable model and voice inventory

### Chat / embedding / local inference

| Asset | Release role | Source / terms status | Sign-off status |
|---|---|---|---|
| Gemma 4 E-2B LiteRT-LM | Required launch-compatible chat tier. | `litert-community/gemma-4-E2B-it-litert-lm`, repository page records Apache-2.0 in current docs. | OK for release inventory; verify exact model card in final sign-off notes. |
| Gemma 4 E-4B LiteRT-LM | Optional flagship chat tier. | `litert-community/gemma-4-E4B-it-litert-lm`, repository page records Apache-2.0 in current docs. | OK as optional release-exposed download. |
| Arctic Embed M v1.5 + WordPiece vocabulary | Required embedding/RAG model and tokenizer. | `Snowflake/snowflake-arctic-embed-m-v1.5`, source revision `e58a8f756156a1293d763f17e3aae643474e9b8a`, Apache-2.0. | Public rolling GitHub release `model-arctic-embed-m-v1.5-current`; validated asset sizes and SHA-256 digests are recorded in the Phase B report. Anonymous download is supported. GitHub release immutability is not a #1559 gate; #1563 tracks non-blocking litert-community publication. |
| MiniLM-L6 intent classifier | Bundled/fallback classifier. | `sentence-transformers/all-MiniLM-L6-v2`, documented as Apache-2.0. | Confirm bundle presence during #1259 artefact audit. |
| FunctionGemma mobile actions | Optional/experimental if surfaced. | Gated Gemma terms. | Keep optional/experimental; do not promote in store copy unless current release UI exposes it intentionally. |

### Speech-to-text

| Asset | Release role | Source / terms status | Sign-off status |
|---|---|---|---|
| Android native STT | Platform fallback/option. | Android platform/system service. | Store/privacy copy must not promise guaranteed offline operation for this path. |
| Vosk runtime and model | Removed under #1008. | Not shipped. | No sign-off needed. |
| Sherpa Zipformer STT | Optional Sherpa STT model. | `csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21`, documented as Apache-2.0 / upstream lineage. | OK if release-exposed; cite exact HF export page. |
| Sherpa SenseVoice STT | Optional Sherpa STT model. | ONNX export from SenseVoice/FunASR. FunASR code is MIT; model weights use separate FunASR model licence. | Release docs must not describe as MIT-only. |
| Sherpa Whisper tiny.en STT | Optional Sherpa STT model. | `csukuangfj/sherpa-onnx-whisper-tiny.en` plus upstream `openai/whisper-tiny.en`, Apache-2.0. | OK if release-exposed; cite both export and upstream model lineage. |
| Sherpa Paraformer STT | Optional Sherpa STT model. | HF export lists Apache-2.0; upstream ModelScope/FunASR lineage and model-weight terms should be recorded. | Release docs must not simplify to MIT-only. |

### Text-to-speech

| Asset | Release role | Source / terms status | Sign-off status |
|---|---|---|---|
| Android TTS | Platform fallback. | Android platform/system service. | OK; describe as platform fallback. |
| Piper/VITS voice packs | User-selectable Sherpa/Piper voices. | Sherpa-ONNX release assets; per-voice dataset provenance still matters. | Only CoriHigh is release-visible in v0.1; other catalogue entries are excluded pending per-voice source, dataset, and terms review. |
| Semaine | Debug/internal only. | Potential non-commercial/share-alike risk. | Excluded from release under #1258; not a release-visible voice. |
| CoriHigh | Release-visible replacement for Semaine. | `rhasspy/piper-voices` MIT repo; model card identifies LibriVox as the dataset. | Sole release-visible Piper voice for v0.1 based on the recorded source and provenance. |
| Kokoro experimental | Debug-only/post-launch Sherpa Kokoro TTS. | Exact app asset is Sherpa-ONNX `kokoro-int8-multi-lang-v1_0.tar.bz2`; upstream Kokoro 82M lineage recorded as Apache-2.0. | Not release-visible for v0.1 under #1014; no release-signoff conclusion. |

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
- Nick (maintainer) confirmed in [#1263 comment 6415038577](https://github.com/NickMonrad/kernel-ai-assistant/issues/1263#issuecomment-6415038577) that the real recordings are of him and his wife, made with consent for this use; additional synthetic audio was generated with Piper TTS, and no other human recordings were used.

## In-app disclosures required by #1263

The Legal & Privacy route and offline Open-source licences screen are present in the current source. The screen displays `app/src/main/assets/legal/NOTICE.txt`; it does not generate per-component licence records from `releaseRuntimeClasspath`, and the NOTICE file is not a complete, verified release dependency notice set.

The required Models, voices & wake word view and Data & Privacy Summary navigation are not present. Privacy Policy and Terms of Use links are present. Do not treat the current legal surface as complete #1263 acceptance.

Release disclosures remain blocked on reviewed metadata for every resolved release dependency and exposed/downloadable asset, required verbatim notices, and the release artifact/source reconciliation described below. Existing `verifyReleaseDependencyInventory` validates that graph metadata is explicitly unknown; it does not prove notice coverage or licensing.

Other existing release obligations remain:

- repository `LICENSE` and `NOTICE` remain present;
- release/internal launch notes should link the detailed attribution docs;
- Play Store copy must not claim ownership of upstream models or voices;
- final release-artefact audit must confirm bundled model files and debug-only dependencies.

## Historical evidence and current blockers

The #1259/#1273 release dependency tree, gap report, and AAB audit are historical and do not describe the current release variant. The current `generateReleaseDependencyInventory` task derives the graph from `releaseRuntimeClasspath`, but explicitly emits unknown licence, copyright, notice, and source metadata; its verifier checks that unresolved state rather than notice completeness.

## Remaining before closing #1263

- [ ] Generate or verify a complete notice set against the resolved release dependency graph, including all transitive/runtime and local file dependencies; preserve required notice text. No formal SBOM or completed generated notice set exists.
- [ ] Reconcile the current release AAB/APK and packaged native/model/data payloads with that graph and notice set.
- [ ] Resolve reviewed source/licence/notice metadata for MiniLM conversion, tokenizer, wake-word upstream assets, and every release-visible or downloadable model/voice. Existing records identify specific unknown provenance and terms; do not infer clearance from generic project licences.
- [ ] Record Sherpa/eSpeak/Piper native and `espeak-ng-data` source/notice mapping plus a release-linked corresponding-source/build path. #1474 is closed, but the factual evidence in its comments does not establish complete source-to-binary provenance or legal sufficiency; current owner direction excludes inadequately evidenced components from v0.1.
- [ ] Implement and validate the offline Models, voices & wake word view, with source, licence/terms, relevant lineage, and release status for every exposed asset.
- [ ] Add Data & Privacy Summary navigation to Legal & Privacy; Privacy Policy, Terms of Use, and Open-source licences navigation currently exist.
- [ ] Run required release-build offline/accessibility/colour-mode checks after the missing view exists; device evidence remains uncollected for this worktree.
- [x] Semaine is excluded from release visibility under #1258/#1268; CoriHigh is the sole release-visible Piper catalogue entry.
- [x] Kokoro is documented as debug-only/post-launch for v0.1, with Sherpa package asset and upstream Kokoro lineage recorded.
- [x] Maintainer confirmed consent/ownership of Stage 3 human wake-word recordings in [#1263 comment 6415038577](https://github.com/NickMonrad/kernel-ai-assistant/issues/1263#issuecomment-6415038577).
- [ ] Update #1014 and #441 with completed, candidate-linked release evidence; #868 is closed, but that does not close this release sign-off.
