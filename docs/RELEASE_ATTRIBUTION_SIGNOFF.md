# Release attribution sign-off snapshot

Parent issue: #1263  
Related: #868, #1257, #1258, #1268, #1014, #441

This document captures the release-attribution state after the Semaine release-visibility decision landed. It is a practical engineering sign-off aid, not legal advice.

## Status

The current release attribution work generates third-party licence data from the RELEASE variant with AboutLibraries and a CycloneDX SBOM from `releaseRuntimeClasspath`. The pre-existing release wiring uses the custom Inflect-enabled Sherpa AAR; #1474 tracks unresolved Piper/eSpeak provenance, component terms, notices, corresponding-source path, and candidate reconciliation. Generated metadata is not legal approval.

## Pre-release-candidate status

This remains a **pre-release-candidate attribution snapshot**, not a signed release candidate or Play acceptance. Candidate-linked SBOM/notices and exact release artefact reconciliation remain required; the verification task reports unresolved local/native metadata rather than asserting clearance.

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

The Legal & Privacy route includes the generated Open-source licences screen, which preserves the existing `app/src/main/assets/legal/NOTICE.txt` text and adds AboutLibraries data generated for the RELEASE variant. A verification task compares metadata against `releaseRuntimeClasspath` and fails for missing module licences, unreviewed local/native records, or native-library inventory mismatches.

The offline Models, voices & wake word screen and Data & Privacy Summary navigation are implemented. The summary explicitly says its content is owned by #1260 and is not yet available. The asset screen draws model/STT/voice entries from the app's download catalogues, includes bundled/native/platform assets, marks on-demand and debug-only entries, and renders unknown attribution values as “Under review”.

These screens do not resolve owner/legal decisions. Release disclosures remain blocked on approved metadata for the custom Sherpa/eSpeak/Piper components, local AAR and bundled native libraries, bundled model assets and all exposed/downloadable models or voices, and recipient-facing notice/source completeness.
Other existing release obligations remain:

- repository `LICENSE` and `NOTICE` remain present;
- release/internal launch notes should link the detailed attribution docs;
- Play Store copy must not claim ownership of upstream models or voices;
- final release-artefact audit must confirm bundled model files and debug-only dependencies.

## Historical evidence and current blockers

The #1259/#1273 dependency tree and AAB audit are historical. This work adds a RELEASE-filtered AboutLibraries export, CycloneDX SBOM, and `verifyReleaseAttributionMetadata` check against `releaseRuntimeClasspath`, the local/native metadata file, and the current release AAB. The exact candidate dependency verification still fails closed while owner-review fields are unresolved.

## Remaining before closing #1263

- [x] Generate RELEASE-variant licence records and a CycloneDX SBOM from the resolved runtime configuration; preserve the original NOTICE text in the in-app screen.
- [x] Inspect the current unsigned release AAB native libraries and assets; record the inventory and Sherpa/eSpeak runtime findings below.
- [ ] Owner approval: decide distribution terms, licence/notice obligations, and recipient-facing corresponding-source delivery for the modified Sherpa/eSpeak/Piper AAR and native payload. #1474 tracks engineering provenance but does not constitute legal clearance.
- [ ] Fill and approve exact licence/source/notice metadata for the local Sherpa AAR, its packaged JNI binary, and every native library listed below. `app/release-attribution-metadata.json` intentionally marks unresolved fields rather than inferring them.
- [ ] Owner/legal review of bundled Vosk model provenance/terms, MiniLM converted-file provenance, tokenizer linkage, openWakeWord asset revisions, Hey Jandal model terms, and the release-exposed/downloadable model and voice catalogue. Specific unresolved model-family terms are called out in the in-app attribution entries.
- [x] Implement the offline Models, voices & wake word view from the live model/STT/voice catalogues, including source, terms, lineage, release state, and “Under review” fallbacks.
- [x] Add Data & Privacy Summary navigation and a placeholder stating that the content is owned by #1260 and is not yet available.
- [ ] Collect visual/offline/accessibility and colour-mode evidence on a device. Phones were not used for this engineering work; no screenshot evidence is claimed.
- [x] Semaine is excluded from release visibility under #1258/#1268; CoriHigh is the sole release-visible Piper catalogue voice. #1258 is closed after the implementation and docs decision.
- [x] Kokoro is identified as debug-only/post-launch for v0.1, with Sherpa pack and upstream Kokoro lineage shown.
- [x] Maintainer confirmed consent/ownership of Stage 3 human wake-word recordings in [#1263 comment 6415038577](https://github.com/NickMonrad/kernel-ai-assistant/issues/1263#issuecomment-6415038577).
- [ ] Update #1014 and #441 with completed candidate-linked evidence; #868 being closed does not close this release sign-off.

## Release AAB and Sherpa/eSpeak investigation

Inspection of `app/build/outputs/bundle/release/app-release.aab` after `:app:bundleRelease` found these packaged native libraries:

| ABI | Libraries |
|---|---|
| `arm64-v8a`, `x86_64` | `libLiteRt.so`, `libLiteRtOpenClAccelerator.so`, `libandroidx.graphics.path.so`, `libc++_shared.so`, `libdatastore_shared_counter.so`, `libjnidispatch.so`, `libkernelvec.so`, `liblitertlm_jni.so`, `libonnxruntime.so`, `libonnxruntime4j_jni.so`, `libsherpa-onnx-jni.so`, `libvosk.so` |
| `armeabi-v7a`, `x86` | `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, `libjnidispatch.so`, `libonnxruntime.so`, `libonnxruntime4j_jni.so`, `libsherpa-onnx-jni.so`, `libvosk.so` |

The bundle includes `base/assets/vosk-model/**`, the MiniLM TFLite model and intent vectors, tokenizer vocabulary, all three wake-word ONNX files, and the original `legal/NOTICE.txt`. It does not include the CoriHigh or Inflect ONNX model downloads, a standalone eSpeak `.so`, or `espeak-ng-data`. The AAR itself contains `libsherpa-onnx-jni.so` for four ABIs; app packaging excludes its separate C and C++ API `.so` files.

Sherpa is required at runtime for both release TTS paths: the CoriHigh controller reflects `OfflineTts`, loads `libsherpa-onnx-jni.so` and `libonnxruntime.so`, and configures the downloaded voice pack's `espeak-ng-data`; Inflect's custom JNI phonemizer is built into the Inflect-enabled Sherpa JNI library and also requires its downloaded eSpeak data directory. Both voice packs/data are downloaded on demand, not bundled. Without Sherpa JNI/ORT, CoriHigh is unavailable and Inflect cannot phonemize; the controllers can return unavailable so Android TTS fallback can take over. Without the downloaded eSpeak data, both release paths fail initialization/availability. Removing Sherpa would therefore disable these engine paths, not the Android TTS fallback.

The AAR build manifest records pinned Sherpa/eSpeak/Piper source inputs and hashes, but no recipient-facing source/notice bundle. This investigation answers runtime/package content only; release distribution and notice compliance remain owner/legal decisions.
