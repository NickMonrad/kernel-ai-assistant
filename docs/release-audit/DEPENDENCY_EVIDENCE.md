# Release Runtime Dependency Evidence

Historical baseline generated during #1259 / PR #1273; it is not a current-main release SBOM or final-candidate compliance audit. The custom Inflect-enabled Sherpa AAR and Piper/eSpeak scope are tracked by #1474.

## Command Run

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Full output at `build/reports/release-audit/releaseRuntimeClasspath.txt` (gitignored; 1448 lines).

## Dependency Source

All release runtime dependencies come from:

1. **Maven coordinates** declared in `gradle/libs.versions.toml` and `app/build.gradle.kts`:
   - Jetpack / AndroidX libraries (Android Jetpack)
   - Kotlin stdlib, coroutines, serialisation
   - Dagger / Hilt (compile-time; no runtime classpath presence in some cases)
   - LiteRT-LM, TensorFlow Lite, MediaPipe Tasks
   - Sherpa-ONNX (AAR bundle)
   - Vosk Android
   - ONNX Runtime Android
   - Coil (image loading)
   - OkHttp / Okio
   - AppAuth, Tink, Gson
   - Apache Commons Compress
   - sqlite-vec native extension
   - Firebase encoders (data-binding layer)
   - Google Play Services (location, base, tasks)

2. **Bundled native libraries** (assets/libs within the AAB):
   - Sherpa-ONNX, ONNX Runtime, TFLite, LiteRT-LM, MediaPipe, Vosk
   - sqlite-vec (kernelvec), JNA

3. **Bundled model assets** (within the AAB):
   - `minilm-l6-v2-int8.tflite` (intent classifier, 22.8 MB)
   - 3 wake-word ONNX files (~2.6 MB total)
   - Vosk STT model (~73 MB)

## Direct Runtime Dependency Highlights

| Category | Key Dependencies | Licence |
|---|---|---|
| Language runtime | Kotlin stdlib, kotlinx-coroutines, kotlinx-serialization | Apache-2.0 |
| Android framework | AndroidX Activity, Core KTX, Lifecycle, Navigation, Compose, DataStore, WorkManager, Room, Security Crypto, Glance | Apache-2.0 |
| DI | Hilt / Dagger (compile-time annotation processing; not at release runtime) | Apache-2.0 |
| ML inference | LiteRT-LM, TensorFlow Lite, MediaPipe Tasks | Apache-2.0 |
| ONNX runtime | ONNX Runtime Android | MIT |
| Speech (STT) | Vosk Android | Apache-2.0 |
| Speech (TTS) | Sherpa-ONNX custom v1.13.0 Inflect AAR (`third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort-inflect.aar`) | Sherpa's project licence does not establish the complete AAR's component terms; Piper/eSpeak provenance, eSpeak data, corresponding source and notices remain open in #1474. |
| Networking | OkHttp, Okio | Apache-2.0 |
| Image loading | Coil (Compose Multiplatform) | Apache-2.0 |
| Auth | AppAuth, Tink | Apache-2.0 |
| JSON | Gson, Kotlinx Serialization | Apache-2.0 |
| Compression | Apache Commons Compress | Apache-2.0 |
| Vector DB | sqlite-vec (with native extension) | MIT / Apache-2.0 |
| Google services | Play Services (location, base, tasks, basement) | Proprietary (Android SDK terms) |
| Google Firebase | Firebase Encoders | Apache-2.0 |
| JNA | Java Native Access (jnidispatch native lib) | Apache-2.0 / LGPL-2.1 |

## Comparison with Existing Attribution Docs

### `NOTICE`
- CURRENT: Covers 2 adapted source projects (Google AI Edge Gallery, LiteRT-LM) plus openWakeWord.
- GAP: This historical notice review does not inventory the custom AAR's Piper/eSpeak component provenance or eSpeak data; no generated third-party notice is recorded.
- VERDICT: Not sufficient to close #1474 or establish the required notices for the current release payload.

### `docs/LEGAL_AND_ATTRIBUTION.md`
- The table covers major categories in the historical inventory but predates the current custom Inflect-enabled Sherpa AAR wired into `:app`.
- GAP: It does not record exact Piper/eSpeak source revisions, the voice-pack `espeak-ng-data` distribution, or release-linked corresponding source; #1474 tracks these.
- VERDICT: Not current or complete release evidence; do not use it to claim the complete bundle is permissive or Apache-2.0-only.

### `docs/VOICE_MODEL_ATTRIBUTION.md`
- Covers downloadable voice packs (CoriHigh, Kokoro, Semaine provenance).
- VERDICT: Covers all release-visible voices. Semaine is correctly documented as hidden from release.

### `docs/RELEASE_ATTRIBUTION_SIGNOFF.md`
- Cross-references all other docs. Lists remaining items before #1263 can close.
- VERDICT: Honest about remaining gaps (Vosk provenance, wake-word consent, in-app notices decision).

## Formal SBOM Status

- **Machine-generated SBOM** (CycloneDX / SPDX): **Not generated.** No plugin added for this purpose.
- **Generated third-party notices**: Not generated as a standalone file.
- **Historical approach**: At the time of this report, manual dependency tree inspection + comparison against authored attribution docs.

This historical report alone cannot close #1263 or #1474. #1263 still needs its SBOM-versus-manual-review decision; #1474 additionally requires exact candidate provenance, source/notice evidence, and reconciliation.

This file preserves the #1259 / PR #1273 review and is not current-main or candidate-linked compliance evidence.
