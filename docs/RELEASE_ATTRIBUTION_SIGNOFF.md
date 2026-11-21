# Release attribution sign-off snapshot

Parent issue: #1263  
Related: #868, #1257, #1258, #1268, #1014, #441, #1474

This is the current engineering release-attribution snapshot for Jandal v0.1. It is not legal advice and is not a Play release approval.

## v0.1 release scope

The owner rule recorded on #1263 is fail-closed: any Sherpa/Piper/eSpeak voice or component whose distribution, corresponding-source, attribution, or notice obligations remain inadequately evidenced is excluded from v0.1.

The release build therefore uses:

- Android native speech recognition for voice input;
- Android text-to-speech for spoken output;
- no wake-word model assets are included in the v0.1 release; the Hey Jandal detector is unavailable and its models remain debug-only;
- no custom Sherpa/Piper/eSpeak AAR, Sherpa STT runtime, Piper voice pack, Inflect Micro runtime, or Kokoro runtime.

Debug builds retain the full Sherpa/Inflect/Piper/Kokoro development paths.

## Generated release evidence

PR #1793 adds reproducible release evidence from the resolved release variant:

- AboutLibraries release dependency data for the offline Open-source licences view;
- CycloneDX JSON and XML SBOMs from `releaseRuntimeClasspath`;
- `:app:verifyReleaseAttributionMetadata`, which checks licence coverage for the resolved release modules and matches local binaries, packaged native libraries and notice files against reviewed metadata;
- `app/release-attribution-metadata.json` for explicit source/licence mappings and SHA-256 pins for the bundled notices.

The verifier runs against the exact AAB produced by `bundleRelease`. CI evidence for the final PR head must be green before this snapshot is treated as review-ready.

## Release-exposed assets

| Area | v0.1 status | Attribution position |
|---|---|---|
| Gemma 4 E-2B / E-4B LiteRT-LM | Download on demand | Named upstream LiteRT-LM repositories; Apache-2.0 metadata recorded. |
| Arctic Embed M v1.5 + vocabulary | Download/bundled vocabulary | Snowflake source revision `e58a8f756156a1293d763f17e3aae643474e9b8a`; Apache-2.0; pinned vocabulary identity recorded. |
| MiniLM-L6 intent classifier | Bundled | Exact pre-converted asset from `Nihal2000/all-MiniLM-L6-v2-quant.tflite` commit `ad6c27c81b2c156925c1fb038d533f52f94ccd77`; Apache-2.0; bundled SHA-256 `0aac5b0b76be23ab94f065a7fab6e0daead5e57f6ff7d55e19a2641d6a81f276`; upstream lineage is `sentence-transformers/all-MiniLM-L6-v2`. |
| Android native STT | Release-visible | Android platform/provider terms; offline availability is device/provider dependent. |
| Sherpa STT engines/models | Debug/internal only | Excluded from v0.1 with the unresolved Sherpa runtime distribution path. |
| Android TTS | Release-visible | Android platform/installed-engine terms. |
| Piper/Cori/Semaine voices | Debug/internal only | Excluded from v0.1. Cori provenance remains documented but is not release clearance. |
| Inflect Micro | Debug/internal only | Upstream ONNX model is documented; runtime depends on the excluded custom Sherpa AAR. |
| Kokoro | Debug/post-launch only | Sherpa package and upstream Kokoro lineage remain documented. |
| openWakeWord Stage 1/2 and Hey Jandal Stage 3 | Debug-only; excluded from v0.1 | The Stage 1/2 export terms are unresolved. All three assets are excluded from release artifacts; the attribution view and verifier require this exclusion. The Stage 3 classifier remains Jandal-generated with provenance recorded in `training/wakeword/README.md`. |

Vosk has been removed and is not part of the current release.

## In-app legal surfaces

Settings/About → Legal & Privacy provides:

- Privacy Policy;
- Terms of Use;
- offline Open-source licences generated from the resolved release variant plus the bundled notice text;
- offline Models, voices & wake word attribution with source, terms/lineage, and release status;
- Data & Privacy Summary navigation owned by #1260.

The app's existing About surface displays version name and version code.

## Native release inventory

The release AAB is checked against `app/release-attribution-metadata.json`. Expressions identify the named components' governing licences; packaged vendor notices carry additional third-party terms and copyright text.

| Packaged libraries | Source and notice evidence |
|---|---|
| `libLiteRt.so`, `libLiteRtOpenClAccelerator.so` | `com.google.ai.edge.litert:litert:2.1.0` Android AAR supplies both JNI files and declares Apache-2.0 in the POM. The AAR's `LICENSE`, including its Caffe notice, is bundled. |
| `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so` | AndroidX `graphics-path:1.0.1` and `datastore-core-android:1.1.4` AARs supply these JNI files; their published POMs declare Apache-2.0. |
| `libc++_shared.so` | Both packaged ABIs match `llvm-strip --strip-unneeded` output from NDK `28.2.13676358` byte-for-byte. Its `clang_source_info.md` identifies LLVM base revision [`3b5e7c83`](https://github.com/llvm/llvm-project/commit/3b5e7c83a6e226d5bd7ed2e9b67449b64812074c); the libc++ and libc++abi texts include the LLVM exception and legacy notices. |
| `libkernelvec.so` | Jandal Apache-2.0 JNI, vendored [sqlite-vec 0.1.9](https://github.com/asg017/sqlite-vec/tree/v0.1.9) (MIT OR Apache-2.0; Jandal supplies the MIT notice), and [SQLite 3.49.2](https://www.sqlite.org/copyright.html) (public domain). |
| `liblitertlm_jni.so` | `com.google.ai.edge.litertlm:litertlm-android:0.19.1` supplies the JNI file, Apache-2.0 licence and `THIRD_PARTY_NOTICE.txt`; the vendor notice is bundled. |
| `libonnxruntime.so`, `libonnxruntime4j_jni.so` | Maven `com.microsoft.onnxruntime:onnxruntime-android:1.24.3` declares MIT. All eight JNI entries match those in `runtimes/android/native/onnxruntime.aar` from [Microsoft.ML.OnnxRuntime 1.24.3](https://www.nuget.org/packages/Microsoft.ML.OnnxRuntime/1.24.3). The official package's `LICENSE` and `ThirdPartyNotices.txt` are bundled. |
| `libexecutorch.so` | `org.pytorch:executorch-android:1.5.1` AAR (the only copy; `:app:verifyExecuTorchSingleVersion` enforces one declaration). Licence expression `BSD-3-Clause AND Apache-2.0 AND MIT AND BSD-3-Clause WITH PCRE2-exception`. Bundled: the ExecuTorch [LICENSE](https://github.com/pytorch/executorch/blob/v1.5.1/LICENSE) (`executorch-1.5.1-licence.txt`) and third-party notices for statically linked XNNPACK, cpuinfo, pthreadpool, pocketfft, SentencePiece, protobuf-lite, Darts-clone, RE2, Abseil, PCRE2 and nlohmann/json (`executorch-1.5.1-third-party-notices.txt`). |
| `libfbjni.so` | `com.facebook.fbjni:fbjni:0.7.0` AAR, Apache-2.0; licence bundled as `fbjni-0.7.0-licence.txt`. |

Vendor notice text is retained with LF line endings. The original Inflect AAR, Sherpa JNI, Vosk and JNA must not be packaged. The TTS-free Sherpa AAR in this branch is not a release dependency: the [Whisper ONNX export repository](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/tree/d026532c022fa99fd789d6b32446a1df7b6bfc43) identifies no licence for the exact exported files. Its [export recipe](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html) establishes lineage, not an express grant for that export.

## Remaining release acceptance

The attribution verifier does not package or accept any wake-word model assets in v0.1. The openWakeWord Stage 1/2 export terms remain unresolved, so the shared Gradle asset source is excluded from release while debug builds retain the feature for development. Re-enable release wake-word assets only after an authoritative license determination or an acceptable replacement is recorded.

Do not mark #1793 ready until exact-head CI and the release attribution gate pass.

The following remain release-acceptance activities outside this code repair and must not be claimed without evidence:

- release-build offline navigation/display validation on a device;
- TalkBack/accessibility validation;
- light, dark and system-colour validation;
- final #1014 and #441 status updates tied to the selected release candidate.

