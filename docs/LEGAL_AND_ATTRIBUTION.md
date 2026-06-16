# Legal, licence, and attribution review

This document supports #868 and the Play Store launch gate. It is a practical engineering checklist, not legal advice.

## Current position

This PR now records the known licence position for the components we can identify from the repo and public upstream metadata. Final release still needs an automated release-variant notice/SBOM pass to catch transitive dependencies and confirm exactly what is bundled in the APK/AAB.

The current release-attribution snapshot for #1263 is [`RELEASE_ATTRIBUTION_SIGNOFF.md`](RELEASE_ATTRIBUTION_SIGNOFF.md). Use that document as the short-form launch sign-off view, and use this document as the detailed inventory.

## Source licence

Jandal AI source code is distributed under the Apache License 2.0. See [`../LICENSE`](../LICENSE).

The repository also includes a [`../NOTICE`](../NOTICE) file for attribution notices that should travel with source or binary distributions where applicable.

## Launch principle

Before Play Store release, every shipped or downloadable third-party component should be in one of these states:

1. **Bundled in the APK/source distribution** - licence and required notice are included in `NOTICE`, app documentation, or an in-app notices screen.
2. **Downloaded at runtime** - user-facing documentation explains the upstream provider and that the asset remains subject to its upstream licence/model card/terms.
3. **Development-only** - not listed as an end-user runtime component, but documented if required for reproducible development or testing.
4. **Research / not shipped** - clearly marked as future/research so it is not accidentally represented as launch capability.

## Confirmed attribution inventory

### Project/source-code notices

| Component | Use in Jandal | Licence | Status / required notice |
|---|---|---|---|
| Jandal AI source code | App source | Apache-2.0 | Project `LICENSE` present. |
| Google AI Edge Gallery | Adapted LiteRT-LM inference, model download, and chat streaming patterns | Apache-2.0 | Listed in `NOTICE`; keep attribution. |
| Google LiteRT-LM | Android LiteRT-LM library | Apache-2.0 | Listed in `NOTICE`; keep attribution. |

### Direct Android/Kotlin/runtime dependencies

These direct dependencies are declared in `gradle/libs.versions.toml`. Release notices must still be generated from the resolved release variant, but the expected licence class for the direct dependency is recorded here.

| Component / family | Repo dependency examples | Licence / terms | Release treatment |
|---|---|---|---|
| Android Gradle Plugin | `com.android.application`, `com.android.library` | Apache-2.0 / Android SDK terms as build tooling | Build-time only; no end-user notice unless bundled. |
| Kotlin Gradle/plugin/tooling | Kotlin Compose plugin, KSP | Apache-2.0 | Build-time only; no end-user notice unless bundled. |
| Jetpack Compose / Material / UI | `androidx.compose.*` | Apache-2.0 | Include in generated runtime notices where bundled. |
| AndroidX Core/Activity/Navigation/Lifecycle/DataStore/Browser | `androidx.*` | Apache-2.0 | Include in generated runtime notices where bundled. |
| Room | `androidx.room:*` | Apache-2.0 | Runtime modules in generated notices; compiler/test modules dev-only. |
| WorkManager / Hilt Work | `androidx.work:*`, `androidx.hilt:*` | Apache-2.0 | Runtime modules in generated notices. |
| Glance widgets | `androidx.glance:*` | Apache-2.0 | Include in generated runtime notices. |
| Hilt / Dagger | `com.google.dagger:hilt-android` | Apache-2.0 | Include runtime library notice; compiler is build-time only. |
| Kotlin coroutines | `kotlinx-coroutines-android` | Apache-2.0 | Include runtime notice. |
| OkHttp | `com.squareup.okhttp3:okhttp` | Apache-2.0 | Include runtime notice; also disclose network-backed skills using it. |
| AppAuth Android | `net.openid:appauth` | Apache-2.0 | Include runtime notice; document Hugging Face OAuth usage. |
| AndroidX Security Crypto | `androidx.security:security-crypto` | Apache-2.0 | Include runtime notice if bundled. |
| Apache Commons Compress | `org.apache.commons:commons-compress` | Apache-2.0 | Include runtime notice if bundled; used for archive extraction. |
| Google Play Services Location | `com.google.android.gms:play-services-location` | Google Play services / SDK terms, not normal OSS | Include privacy disclosure for location use; generated OSS notice may not apply. |
| Coil | `io.coil-kt.coil3:coil-compose` | Apache-2.0 | Include runtime notice if bundled. |
| Vosk Android | `com.alphacephei:vosk-android` | Apache-2.0 | Include runtime notice; model files still need their own source/licence if bundled/downloaded. |
| ONNX Runtime Android | `com.microsoft.onnxruntime:onnxruntime-android` | MIT | Include MIT notice if bundled. |
| TensorFlow Lite / GPU | `org.tensorflow:tensorflow-lite*` | Apache-2.0 plus bundled third-party notices | Include runtime notices from generated notice/SBOM. |
| MediaPipe Tasks Text | `com.google.mediapipe:tasks-text` | Apache-2.0 | Include runtime notice if bundled. |
| UIAutomator / AndroidX Test | `androidx.test:*` | Apache-2.0 | Test-only; exclude unless bundled in release. |
| JUnit / JUnit Platform | `org.junit.*` | Eclipse Public License 2.0 | Test-only; exclude from app notices unless bundled. |
| MockK | `io.mockk:mockk` | Apache-2.0 | Test-only; exclude unless bundled. |
| LeakCanary | `com.squareup.leakcanary:*` | Apache-2.0 | Debug-only; exclude from release notices unless bundled. |

### Native/source assets

| Component / asset | Use in Jandal | Licence / terms | Release treatment |
|---|---|---|---|
| sqlite-vec | Local vector search extension in `core/memory/src/main/cpp` | Dual MIT OR Apache-2.0 upstream | Include chosen licence text/notice if source/native library is bundled. |
| SQLite amalgamation | SQLite C source referenced by native build | Public domain / blessing text upstream | Confirm bundling; include SQLite public-domain notice/blessing if bundled. |
| Sherpa-ONNX AAR/runtime | Local STT/TTS runtime when AAR is packaged | Apache-2.0 | Confirm whether packaged in release; include notice if bundled. |
| Wake-word ONNX model assets | Bundled wake-word detector/verifier assets | openWakeWord Apache-2.0 lineage for Stage 1/2; Stage 3 `hey_jandal.onnx` generated from local training data recorded in `training/wakeword/README.md` | Keep openWakeWord notice; before final release, maintainer should confirm real-recording ownership/consent for the generated Stage 3 model. |
| Local model binaries under `models/` | Dev/test local model cache | Not committed / not shipped from source | Keep gitignored; release artefact still needs audit. |

### Downloaded or bundled model assets

| Asset/model | Current role | Licence / terms | Launch treatment |
|---|---|---|---|
| Gemma-4 E-2B LiteRT-LM | Required launch-compatible chat tier | Hugging Face page lists `apache-2.0` | Document model card/source and include Apache-2.0 notice if redistributed. |
| Gemma-4 E-4B LiteRT-LM | Optional flagship chat tier | Hugging Face page lists `apache-2.0` | Document model card/source and include Apache-2.0 notice if redistributed. |
| EmbeddingGemma 300M generic | Embedding/RAG model | Hugging Face page lists `gemma`; gated terms must be accepted | Keep gated-model language; do not present as Apache-2.0. |
| EmbeddingGemma 300M SM8550 | Qualcomm-optimised EmbeddingGemma variant | Hugging Face page lists `gemma`; gated terms must be accepted | Deprecated/hidden from generic model management; if exposed, document terms. |
| FunctionGemma / mobile actions model | Optional/experimental mobile-actions model | Hugging Face page lists `gemma`; gated terms must be accepted | Keep optional/experimental; document terms if exposed. |
| MiniLM-L6 intent classifier | Bundled/fallback intent classifier asset | `sentence-transformers/all-MiniLM-L6-v2` page lists `apache-2.0` | Confirm exact converted asset provenance and include Apache-2.0 notice if bundled. |

### Speech-to-text models/assets

| Engine/model | Role | Licence / terms | Launch treatment |
|---|---|---|---|
| Android native STT | Platform speech recognition path | Android platform/system service; offline behaviour device-dependent | Do not describe as guaranteed offline; include privacy/platform dependency wording. |
| Vosk Android runtime | Offline STT library | Apache-2.0 | Runtime notice required if bundled. |
| Vosk model files | Offline STT model data if bundled/downloaded | Must verify per model file | Add per-model source/licence once final Vosk model is confirmed. |
| Sherpa Zipformer STT | Optional/default Sherpa STT model | Sherpa model source/licence must be verified per model | Document source/licence before shipping/download exposure. |
| Sherpa SenseVoice STT | Optional Sherpa STT model | Model licence must be verified per model | Document source/licence before shipping/download exposure. |
| Sherpa Whisper tiny.en STT | Optional Sherpa STT model | Code comments indicate Apache-2.0; verify upstream model page | Document source/licence before shipping/download exposure. |
| Sherpa Paraformer STT | Optional Sherpa STT model | Code comments indicate Apache-2.0; verify upstream model page | Document source/licence before shipping/download exposure. |

### Text-to-speech voices/assets

| Engine/voice asset | Role | Licence / terms | Launch treatment |
|---|---|---|---|
| Android TTS | Platform TTS fallback | Android platform/system service | Safe fallback; document platform dependency. |
| Piper runtime/project | Source project for Piper voices | MIT | Include MIT notice if Piper runtime/code is bundled. |
| rhasspy/piper-voices repository | Source for many Piper ONNX voice files | Hugging Face page lists `mit` for repository | Repository-level MIT is not enough for all dataset provenance; keep per-voice review. |
| en_GB-cori-high Piper voice | Release-safe British English female voice | Sourced from `rhasspy/piper-voices` (MIT-licensed repo). Upstream model card: dataset = LibriVox (public domain). | Release-visible based on the documented upstream licence/provenance reviewed for #1268. |
| Kokoro experimental voice pack | Experimental TTS path | Exact app asset is Sherpa-ONNX `tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2`; upstream Kokoro 82M lineage is Apache-2.0 | Consider licence trace acceptable if release-exposed, but keep product wording experimental/conservative. |

## External services and data sources

These are not all OSS attribution items, but they must be reflected in Play Store privacy/data disclosures and user-facing docs:

| Service/source | Use | Required launch disclosure |
|---|---|---|
| Weather/forecast provider | Weather skill | Identify provider and network use. |
| Frankfurter / ECB exchange rates | Currency conversion | Identify provider/source if enabled. |
| Wikipedia | Wikipedia lookup skill | State that lookup calls external web source. |
| Maps/navigation intents | Opens maps/navigation apps | State that action may leave Jandal/open another app. |
| Email/SMS/calendar/contacts flows | Android intents/platform APIs and permissions | Match permission prompts and Play Store data disclosure. |
| Hugging Face OAuth/downloads | Gated model access/downloads | State account/sign-in and upstream licence acceptance requirement. |

## Launch-blocking decisions

### #1258 / #1268 - Semaine voice pack

Decision implemented in #1268:
- Semaine is hidden from release builds (releaseVisible=false).
- Retained for debug/internal research and future licence review.
- Replacement: en_GB-cori-high voice pack (~116 MB, `rhasspy/piper-voices` MIT repository, upstream model card lists LibriVox public-domain dataset).

Semaine is not deleted. If compatible rights are later obtained, Semaine can be re-enabled by setting releaseVisible=true.

## Suggested README / Play Store wording

Use concise wording such as:

> Jandal runs chat and memory locally on your device where supported. Some optional models, speech engines, and voices are downloaded separately and may require accepting the provider's licence or model terms. Jandal does not sell user data or send chat/memory content to a cloud model by default.

Avoid promising that every feature is offline if a skill may call an external source, open another app, or depend on Android system services.

## Remaining release checklist

- [x] README has a concise feature overview rather than a long implementation changelog.
- [x] README includes the voice/STT/TTS tech stack.
- [x] README links to model setup and licence/attribution docs.
- [x] `models/README.md` documents approximate model sizes and gated-model requirements.
- [x] `NOTICE` includes source-code adaptation notices and points to this review for runtime/downloadable assets.
- [x] Direct dependency licence classes are recorded in this document.
- [x] #1268: Semaine hidden from release; CoriHigh promoted as release-safe British English alternative.
- [x] #1263: release-attribution snapshot added in `docs/RELEASE_ATTRIBUTION_SIGNOFF.md`.
- [x] Wake-word training/provenance path is traceable through `training/wakeword/README.md` and `NOTICE`.
- [ ] Generate release dependency notices/SBOM from the resolved release variant and compare against this table.
- [ ] Confirm exact bundled/downloaded Vosk model provenance if Vosk model files are exposed in the release build.
- [ ] Confirm maintainer ownership/consent for real recordings used in the generated Hey Jandal Stage 3 wake-word model.
- [ ] Decide whether an in-app open-source licences screen is required after the generated release notice/SBOM is reviewed.
- [ ] Play Store privacy/data disclosures match the actual release build.
- [ ] #868 is closed only after the final release scope is known and the checklist above has been reviewed.
