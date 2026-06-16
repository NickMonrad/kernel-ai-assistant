# Release AAB Audit — v0.1.0 (versionCode 1)

## Build Metadata

| Property | Value |
|---|---|
| Build command | `./gradlew clean :app:bundleRelease -PversionCode=1` |
| Commit SHA | `059122dc` (`docs(#1263): add release attribution sign-off snapshot`) |
| Application ID | `com.kernel.ai` |
| Version code | `1` |
| Version name | `0.1.0` |
| Build type | `release` (minify + shrink resources) |
| AAB path | `app/build/outputs/bundle/release/app-release.aab` |
| AAB size | 227 MB; ~238 MB extracted for inspection |
| SHA-256 | `a090acd03a2e045dde659cee4540376c5533a05c00d4843f2f7c6ce2152764be` |
| Gradle | `9.1.0` |
| AGP | `9.0.1` |
| Kotlin | `2.3.21` |

## SDK Version Compliance

| SDK | Value | Play Requirement | Status |
|---|---|---|---|
| compileSdk | `36` (Android 16) | — | ✅ |
| targetSdk | `36` (Android 16) | ≥ API 35 for new apps | ✅ Meet or exceed |
| minSdk | `35` (Android 15) | — | ✅ |

**Play requirement**: New mobile apps submitted after August 2024 must target API 35+. At API 36, this build exceeds the current minimum.

## AAB Content Inspection

### Model Files

| Category | Found in AAB? | Intentionally bundled? | Comment |
|---|---|---|---|
| `.litertlm` (Gemma) | ❌ No | N/A — downloaded on demand | Safety: no 3.4 GB LLM in the AAB |
| `.tflite` (MiniLM) | ✅ `minilm-l6-v2-int8.tflite` (22.8 MB) | ✅ | Zero-shot intent classifier, required at startup |
| `.tflite` (EmbeddingGemma) | ❌ No | N/A — pushed via ADB | Not auto-downloaded in release |
| `.onnx` (wakeword) | ✅ 3 files (~2.6 MB total) | ✅ | openWakeWord detection pipeline |
| `.onnx` (TTS voice packs) | ❌ No | N/A — downloaded on demand | Safety: no 114 MB CoriHigh/Semaine in AAB |
| `.gguf` — any | ❌ No | N/A | No GGUF format used |
| Vosk STT model | ✅ bundled (~73 MB) | ✅ | Offline STT engine |
| `DebugProbesKt.bin` | ✅ (1.7 KB) | ❌ — Kotlin stdlib artifact | Harmless, present in all Kotlin Android apps |

**Assessment**: No accidental model bundling. All intentionally bundled assets serve runtime requirements (intent classification, wakeword detection, offline STT).

### Debug / Test Artifacts

| Item | Found? | Status |
|---|---|---|
| LeakCanary | ❌ Not found | ✅ `debugImplementation` only, excluded by R8 |
| Test classes | ❌ Not found | ✅ Test source sets excluded in release |
| `com.kernel.ai.debug` references | ❌ Not found | ✅ Release uses `com.kernel.ai` |
| Debug probes | ✅ `DebugProbesKt.bin` (1.7 KB) | ⚠️ Kotlin stdlib artifact, harmless |

### Native Libraries (ABI coverage)

| ABI | Present? | Key libraries |
|---|---|---|
| `arm64-v8a` | ✅ | sherpa-onnx-\*, libonnxruntime.so, libtensorflowlite_jni.so, libLiteRt.so, liblitertlm_jni.so, libvosk.so, libkernelvec.so, libmediapipe_tasks_jni.so, libc++_shared.so, libjnidispatch.so |
| `armeabi-v7a` | ✅ | Fewer libs — sherpa-onnx-\*, libonnxruntime.so, libtensorflowlite_jni.so, libvosk.so, libmediapipe_tasks_jni.so, libjnidispatch.so |
| `x86_64` | ✅ | Same as arm64-v8a (for emulator) |
| `x86` | ✅ | Subset (no libc++_shared, no litertlm, no kernelvec) |

All 4 target ABIs are covered. `arm64-v8a` has the most complete set including Sherpa-ONNX, ONNX Runtime, TFLite, LiteRT-LM, and Vosk.

### Extract of notable native library details

- **Sherpa-ONNX**: 3 shared libs per ABI (c-api, cxx-api, jni)
- **ONNX Runtime**: 1-2 shared libs per ABI (libonnxruntime.so, libonnxruntime4j_jni.so); large (18 MB armeabi-v7a variant)
- **TFLite**: libtensorflowlite_jni.so per ABI
- **LiteRT-LM**: liblitertlm_jni.so (arm64-v8a + x86_64)
- **LiteRT**: libLiteRt.so, libLiteRtClGlAccelerator.so (arm64-v8a + x86_64)
- **Vosk**: libvosk.so (arm64-v8a, armeabi-v7a, x86_64, x86)
- **MediaPipe**: libmediapipe_tasks_jni.so (arm64-v8a, armeabi-v7a, x86_64, x86)
- **JNA**: libjnidispatch.so (all 4 ABIs)
- **kernelvec**: libkernelvec.so (arm64-v8a + x86_64) — the sqlite-vec extension

## Download Size Considerations

The raw AAB is 227 MB. Key factors:

- 4 ABIs (arm64-v8a, armeabi-v7a, x86_64, x86) each carry native libs
- Play Store delivers per-device splits (arm64-only device gets ~50-70 MB of native libs, not 4×)
- Vosk model asset (~73 MB) is bundled
- R8 minification + resource shrinking applied
- Play Store compression reduces download size further

**Estimated per-device download size**: likely 100-130 MB on arm64 devices (Play Console pre-launch report will give exact figures).

## Signing Status

| Item | Status |
|---|---|
| Release AAB signed? | ❌ No — build uses unsigned release |
| Debug build signed? | ✅ Uses `keystore/debug.keystore` |
| Play App Signing configured? | ❌ Not yet — requires Play Console setup |
| Signing docs | ✅ `docs/PLAY_RELEASE_BUILD.md` |

## Remaining Steps Before Play Store Upload

1. **Play Console account setup** — tracked in #1264
2. **Closed-testing requirement** — 12 testers × 14 days if personal account post-Nov 2023
3. **Release signing** — generate upload key, configure `keystore.properties`, add signing config to `build.gradle.kts`
4. **Privacy policy** — tracked in #1260
5. **Store listing assets** — tracked in #1262
6. **Data Safety declarations** — tracked in #1260
7. **Pre-launch report** — run via Play Console after upload
8. **SBOM/licence dependency evidence** — tracked in #1263

## Inspector Notes

- AAB was inspected by unzipping and reviewing file listing (`unzip -l`)
- Dependency tree generated via `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`
- Build performed from clean checkout on branch `feature/1259-release-artefact`
- No keystore or signing credentials created during this audit
