# Legal, licence, and attribution review

This document supports #868 and the Play Store launch gate. It is a practical engineering checklist, not legal advice.

## Current position

PR #1793 generates release-variant AboutLibraries data and CycloneDX SBOMs. On the last verified release build (`9edad299`), all 249 resolved release modules had licence metadata, the nine packaged native library names were mapped, the six pinned notices matched the unsigned AAB, and `:app:verifyReleaseAttributionMetadata` passed. The build excludes Sherpa, Vosk, JNA, Piper, Inflect and wake-word payloads. Final acceptance still requires successful checks on the updated PR head and release-build device validation.

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
| ONNX Runtime Android | `com.microsoft.onnxruntime:onnxruntime-android` | MIT | Include MIT notice if bundled. |
| ExecuTorch Android | `org.pytorch:executorch-android` 1.5.1 (via `:core:executorch`) | BSD-3-Clause, with bundled XNNPACK/cpuinfo/pthreadpool notices | Listed in `NOTICE`; include runtime notices from generated notice/SBOM. |
| fbjni / SoLoader nativeloader | `com.facebook.fbjni:fbjni` 0.7.0, `com.facebook.soloader:nativeloader` 0.10.5 (ExecuTorch runtime dependencies) | Apache-2.0 | Listed in `NOTICE`. |
| LiteRT Interpreter API | `com.google.ai.edge.litert:litert` | Apache-2.0 plus bundled third-party notices | Include runtime notices from generated notice/SBOM. |
| UIAutomator / AndroidX Test | `androidx.test:*` | Apache-2.0 | Test-only; exclude unless bundled in release. |
| JUnit / JUnit Platform | `org.junit.*` | Eclipse Public License 2.0 | Test-only; exclude from app notices unless bundled. |
| MockK | `io.mockk:mockk` | Apache-2.0 | Test-only; exclude unless bundled. |
| LeakCanary | `com.squareup.leakcanary:*` | Apache-2.0 | Debug-only; exclude from release notices unless bundled. |

### Native/source assets

| Component / asset | Use in Jandal | Licence / terms | Release treatment |
|---|---|---|---|
| sqlite-vec | Local vector search extension in `core/memory/src/main/cpp` | Dual MIT OR Apache-2.0 upstream | Include chosen licence text/notice if source/native library is bundled. |
| SQLite amalgamation | SQLite C source referenced by native build | Public domain / blessing text upstream | Confirm bundling; include SQLite public-domain notice/blessing if bundled. |
| Sherpa-ONNX AAR/runtime | Local STT/TTS development runtime described in `scripts/sherpa-onnx-v1.13.0-inflect-build.md` | Sherpa's project licence does not establish the complete custom AAR's component terms; the build reuses Piper/eSpeak functionality | Debug/internal only. The custom AAR and its Sherpa/Piper/eSpeak runtime path are excluded from v0.1 release under the #1263 owner rule. |
| Wake-word ONNX model assets | Debug wake-word detector/verifier development assets | Stage 1/2 export terms unresolved; Stage 3 is Jandal-generated from local training data in `training/wakeword/README.md` | All three wake-word assets are excluded from v0.1 release; debug-only while upstream model terms remain unresolved. |
| Local model binaries under `models/` | Dev/test local model cache | Not committed / not shipped from source | Keep gitignored; release artefact still needs audit. |

### Downloaded or bundled model assets

| Asset/model | Current role | Licence / terms | Launch treatment |
|---|---|---|---|
| Gemma-4 E-2B LiteRT-LM | Required launch-compatible chat tier | Hugging Face page lists `apache-2.0` | Document model card/source and include Apache-2.0 notice if redistributed. |
| Gemma-4 E-4B LiteRT-LM | Optional flagship chat tier | Hugging Face page lists `apache-2.0` | Document model card/source and include Apache-2.0 notice if redistributed. |
| Arctic Embed M v1.5 + WordPiece vocabulary | Required embedding/RAG model and tokenizer. | `Snowflake/snowflake-arctic-embed-m-v1.5` at pinned source revision `e58a8f756156a1293d763f17e3aae643474e9b8a`, Apache-2.0. | Temporarily hosted from the public rolling GitHub release `model-arctic-embed-m-v1.5-current`. The validated model SHA-256 is `17c2211fbd759e769b3030837d8259a86a2f7603a0f64222fde14474e4c3054d` (113,850,784 bytes); vocabulary SHA-256 is `07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3` (231,508 bytes). No Hugging Face auth or licence acceptance is required. GitHub release immutability is not a #1559 requirement; long-term community publication is tracked in #1563. |
| FunctionGemma / mobile actions model | Optional/experimental mobile-actions model | Hugging Face page lists `gemma`; gated terms must be accepted | Keep optional/experimental; document terms if exposed. |
| MiniLM-L6 intent classifier | Bundled/fallback intent classifier asset | Exact pre-converted INT8 TFLite asset from `Nihal2000/all-MiniLM-L6-v2-quant.tflite` commit `ad6c27c81b2c156925c1fb038d533f52f94ccd77`; repository records Apache-2.0 and lineage from `sentence-transformers/all-MiniLM-L6-v2` | Bundled file SHA-256 `0aac5b0b76be23ab94f065a7fab6e0daead5e57f6ff7d55e19a2641d6a81f276`, matching the upstream file identity recorded by PR #409. |

### Speech-to-text models/assets

| Engine/model | Role | Licence / terms | Launch treatment |
|---|---|---|---|
| Android native STT | Release speech-input path | Android platform/system service; offline behaviour device-dependent | Release-visible; do not claim guaranteed offline recognition. |
| Sherpa Zipformer STT | Optional/default Sherpa STT model | Sherpa model source/licence must be verified per model | Document source/licence before shipping/download exposure. |
| Sherpa SenseVoice STT | Optional Sherpa STT model | Model licence must be verified per model | Document source/licence before shipping/download exposure. |
| Sherpa Whisper tiny.en STT | Optional Sherpa STT model | Code comments indicate Apache-2.0; verify upstream model page | Document source/licence before shipping/download exposure. |
| Sherpa Paraformer STT | Optional Sherpa STT model | Code comments indicate Apache-2.0; verify upstream model page | Document source/licence before shipping/download exposure. |
| asr4all STT (FUTO, 28M and 56M) | Planned default offline STT model (#1977/#1980); ONNX Runtime | FUTO Model Weights License (Restricted Use) 1.0 (pinned LICENSE: [`futo-org/asr4all-s` @ `87a6b5d0`](https://huggingface.co/futo-org/asr4all-s/blob/87a6b5d093723fe37e9f69ad26307a3b95a31af0/LICENSE), [`futo-org/asr4all-m` @ `7d6e9145`](https://huggingface.co/futo-org/asr4all-m/blob/7d6e9145109a5bece4f05831d046e93d27e38a99/LICENSE); byte-identical text). `asr4all-l-plus` has a different licence and is not covered by this row. Commercial use permitted; Limitations prohibit surveillance, profiling/discrimination, intelligence gathering, custody monitoring, targeting protected activity, and circumvention. Full text bundled in app (`legal/FUTO-Model-Weights-License-1.0.txt`) and listed in `NOTICE`. Terms of Use clause binds users to the Limitations. | Unmodified weights downloaded from FUTO's Hugging Face repos; no model-card derivation notice needed. Any modification or rehosting requires a modification notice and the FUTO terms alongside the files. ONNX Runtime port blocker: see the asr4all section of Launch-blocking decisions. |

### Text-to-speech voices/assets

| Engine/voice asset | Role | Licence / terms | Launch treatment |
|---|---|---|---|
| Android TTS | Release speech-output path | Android platform/system service | Sole v0.1 release TTS engine; voice availability depends on the installed Android engine. |
| Piper runtime/project | Debug-only Piper voice playback | MIT | Not shipped or selectable in v0.1 release. Per-voice dataset provenance remains separate from the project licence. |
| rhasspy/piper-voices repository | Source of debug-only Piper ONNX voices | Hugging Face page lists `mit` for the repository, but upstream states it makes no licence grant over voice models | No Piper voices are release-visible in v0.1. The `mit` tag is not a per-voice clearance; the catalogue was reviewed in `docs/legal/piper-voice-review.md`. |
| en_GB-cori-high Piper voice | Debug-only British English female voice | Upstream model card: trained from scratch on LibriVox (public domain); the trainer lists the voice as public domain | Excluded from v0.1 release, along with all other Piper voice packs and the Sherpa runtime. Source details are recorded for development, not release clearance. |
| Kokoro experimental voice pack | Debug-only/post-launch TTS path for v0.1 | Exact app asset is Sherpa-ONNX `tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2`; upstream Kokoro 82M lineage is recorded as Apache-2.0 | Not release-visible for v0.1 under #1014; this trace is not release sign-off. |

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

The earlier #1268 voice allowlist substituted en_GB-cori-high for Semaine. PR #1793 applies the stricter #1263 v0.1 release rule: **all Piper voices, including CoriHigh and Semaine, and their Sherpa runtime are debug/internal only and excluded from release**. Android TTS is the release speech-output path. This does not delete the development assets or resolve their future distribution terms.

### asr4all (#1979, #1980, #1987) - open blocker

- The asr4all weights (`futo-org/asr4all-s`, `futo-org/asr4all-m`) are licensed under the FUTO Model Weights License (Restricted Use) 1.0. That licence does not cover FUTO's Python reference driver `onnx/asr4all_onnx.py`, which #1987's Kotlin ONNX Runtime engine is a port of.
- Blocker: a written licence from FUTO for the reference driver (owner is contacting FUTO), or a clean-room rewrite verified by parity. Tracked in #1999. Until one is recorded in `VOICE_MODEL_ATTRIBUTION.md`, #1987 and #1980 must not merge or ship the asr4all default. #1998 (Terms acceptance) also blocks #1980.

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
- [x] #1268: Semaine is excluded from release. All Piper voices, including CoriHigh, are excluded from v0.1 release; this allowlist is not full #1263/#1474 release sign-off.
- [x] #1263: release-attribution snapshot added in `docs/RELEASE_ATTRIBUTION_SIGNOFF.md`.
- [x] Wake-word training/provenance path is traceable through `training/wakeword/README.md` and `NOTICE`.
- [x] Release runtime dependency evidence and CycloneDX SBOM generated from `releaseRuntimeClasspath` and checked against the AAB; see `docs/RELEASE_ATTRIBUTION_SIGNOFF.md`. The earlier dependency review remains in `docs/release-audit/DEPENDENCY_EVIDENCE.md`.
- [x] Vosk model provenance: no longer applicable. Vosk, its bundled model and its JNA dependency were removed under #1008.
- [x] Nick confirmed ownership/consent for the real recordings used in the generated Hey Jandal Stage 3 wake-word model in [#1263 comment 6415038577](https://github.com/NickMonrad/kernel-ai-assistant/issues/1263#issuecomment-6415038577).
- [ ] #1263: Both offline in-app disclosure views are implemented through #1260 Legal & Privacy; exact-head release-build device, accessibility and theme validation remain outstanding.
- [ ] Play Store privacy/data disclosures match the actual release build.
- [ ] Confirm #868's closed state still reflects the final release scope after the SBOM/AAB audit.
