# Release Runtime Dependency Evidence

Generated during #1259 / PR #1273. Updated to reflect accurate SBOM/licence evidence status.

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
| Speech (TTS) | Sherpa-ONNX (AAR) | Apache-2.0 |
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
- GAP: Does not list individual runtime library dependencies — but cross-references `docs/LEGAL_AND_ATTRIBUTION.md`.
- VERDICT: Sufficient for first launch. `NOTICE` is intended for source-code adaptation and bundled notice obligations, not a full dependency manifest. The legal attribution docs provide the comprehensive view.

### `docs/LEGAL_AND_ATTRIBUTION.md`
- Covers all major dependency categories. Lists licence classes in a table.
- GAP: Minor transitive deps not individually listed (Tink, Gson, Okio, JSpecify). All are Apache-2.0 or MIT.
- VERDICT: Adequate for first launch.

### `docs/VOICE_MODEL_ATTRIBUTION.md`
- Covers downloadable voice packs (CoriHigh, Kokoro, Semaine provenance).
- VERDICT: Covers all release-visible voices. Semaine is correctly documented as hidden from release.

### `docs/RELEASE_ATTRIBUTION_SIGNOFF.md`
- Cross-references all other docs. Lists remaining items before #1263 can close.
- VERDICT: Honest about remaining gaps (Vosk provenance, wake-word consent, in-app notices decision).

## Formal SBOM Status

- **Machine-generated SBOM** (CycloneDX / SPDX): **Not generated.** No plugin added for this purpose.
- **Generated third-party notices**: Not generated as a standalone file.
- **Current approach**: Manual dependency tree inspection + comparison against authored attribution docs.

To close #1263, one of the following is needed:
1. Add a compatible SBOM generation plugin (CycloneDX Gradle plugin or similar) and commit the output, OR
2. Document that the project uses manual dependency review with the existing attribution docs as the durable evidence, and explicitly accept that approach.

This documentation file serves as the durable, reviewable evidence of the current dependency review state.
