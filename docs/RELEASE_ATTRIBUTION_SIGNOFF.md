# Release attribution sign-off snapshot

Parent issue: #1263  
Related: #868, #1257, #1258, #1268, #1014, #441, #1474

This is the current engineering release-attribution snapshot for Jandal v0.1. It is not legal advice and is not a Play release approval.

## v0.1 release scope

The owner rule recorded on #1263 is fail-closed: any Sherpa/Piper/eSpeak voice or component whose distribution, corresponding-source, attribution, or notice obligations remain inadequately evidenced is excluded from v0.1.

The release build therefore uses:

- Android native speech recognition for voice input;
- Cori (Piper) and Android TTS for spoken output; Inflect Micro on flagship arm64 devices;
- openWakeWord Stage 1/2 and Hey Jandal Stage 3 wake-word assets, plus the Whisper tiny.en verifier downloaded with Hey Jandal; the wake-word assets are CC BY-NC-SA 4.0 per the owner decision on #2017;
- no Sherpa STT runtime or model, no Piper voice other than Cori, and no Kokoro runtime.

The custom Sherpa-ONNX v1.13.0 Inflect AAR's `libsherpa-onnx-jni.so` is packaged for Cori, Piper and Inflect Micro, with its GPL-bearing eSpeak-ng code and notices recorded in the native inventory below (#2016 restores this speech path after #1793 removed it). Debug builds retain the full Sherpa/Inflect/Piper/Kokoro development paths.

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
| Sherpa STT engines/models | SenseVoice and Paraformer: debug/internal only. Whisper tiny.en: release-visible as the wake verifier downloaded with Hey Jandal | SenseVoice/Paraformer stay excluded with the unresolved Sherpa runtime distribution path. Whisper's licence basis is the upstream attribution recorded in the Remaining release acceptance section. |
| Android TTS | Release-visible | Android platform/installed-engine terms. |
| Piper/Cori voices | Cori: release-visible, download on demand. Other Piper voices and Semaine: debug/internal only | Cori's basis is the trainer's public-domain statement and the LibriVox-derived dataset; the `rhasspy/piper-voices` MIT repository tag is not a grant (`docs/legal/piper-voice-review.md`). Other voices remain excluded: each one's Sherpa archive contains weights derived from Lessac or Ryan, so none of them is releasable as downloaded. |
| Inflect Micro | Release-visible on flagship arm64 devices; download on demand | Upstream `owensong/Inflect-Micro-v2-ONNX` is Apache-2.0 (upstream repository). The runtime is the packaged custom Sherpa-ONNX JNI. |
| Kokoro | Debug/post-launch only | Sherpa package and upstream Kokoro lineage remain documented. |
| openWakeWord Stage 1/2 and Hey Jandal Stage 3 | Release-visible under CC BY-NC-SA 4.0 (owner decision, #2017) | Upstream v0.5.1 models are stated as CC BY-NC-SA 4.0 in the upstream README; the Stage 1/2 files are unmodified. The Stage 3 classifier is Jandal-generated with provenance in `training/wakeword/README.md`. Draft PR #2020 records every file-level verdict as "unclear"; the owner decision overrides that review. |

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
| `libexecutorch.so` | `org.pytorch:executorch-android:1.5.1` AAR (the only copy; `:app:verifyExecuTorchSingleVersion` enforces one declaration). Licence expression `BSD-3-Clause AND Apache-2.0 AND MIT AND MPL-2.0 AND BSD-3-Clause WITH PCRE2-exception`. Bundled: the ExecuTorch [LICENSE](https://github.com/pytorch/executorch/blob/v1.5.1/LICENSE) (`executorch-1.5.1-licence.txt`) and third-party notices for statically linked XNNPACK, cpuinfo, pthreadpool, pocketfft, SentencePiece, protobuf-lite, Darts-clone, RE2, Abseil, PCRE2, nlohmann/json and Eigen BLAS (MPL-2.0, [libeigen/eigen @ 72944340](https://gitlab.com/libeigen/eigen/-/commit/729443409942a1816ddf74b95224003b83f4925c), 3.4.90, the pin ExecuTorch v1.5.1 uses) (`executorch-1.5.1-third-party-notices.txt`). |
| `libfbjni.so` | `com.facebook.fbjni:fbjni:0.7.0` AAR, Apache-2.0; licence bundled as `fbjni-0.7.0-licence.txt`. |
| `libsherpa-onnx-jni.so` (arm64-v8a, armeabi-v7a, x86, x86_64) | Packaged from `third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort-inflect.aar` (SHA-256 `46a8e806833066bc657d8508e66e35dae8b1a04dff207ebd8538517d58b6567d`). Apache-2.0 Sherpa-ONNX code with eSpeak-ng GPL-3.0-or-later statically linked; piper-phonemize MIT; uni-algo public domain. Corresponding source: `third_party/sherpa-onnx/sherpa-onnx-v1.13.0-inflect-source-inputs.tar.gz` (SHA-256 `bd1649beeaa3baaf4eaf7c0a6b8b67e2a6d3c743bb0d96aa49348f61f4b4d645`) with `scripts/sherpa-onnx-v1.13.0-inflect-source-package.md` and `scripts/sherpa-onnx-v1.13.0-inflect-build.md`. The combined notice is `app/src/main/assets/legal/sherpa-onnx-1.13.0-inflect-third-party-notices.txt`. The eSpeak-ng GPL posture was accepted by the owner under #1474. |

Vendor notice text is retained with LF line endings. The Vosk and JNA libraries must not be packaged. The Sherpa `libsherpa-onnx-c-api.so` and `libsherpa-onnx-cxx-api.so` libraries are excluded from the bundle for all ABIs, because the JNI does not use them. Whisper tiny.en (the wake verifier) is attributed to the upstream OpenAI weights (MIT code; Apache-2.0 weights card). Its [Whisper ONNX export repository](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/tree/d026532c022fa99fd789d6b32446a1df7b6bfc43) identifies no licence for the exact exported files, and its [export recipe](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html) establishes lineage, not an express grant for that export. The owner accepted this attribution on #2017.

eSpeak-ng voice data for the Cori pack comes from `vits-piper-en_GB-cori-high.tar.bz2` at `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/` (SHA-256 `42922f07738fcde2e49eed4e959635692f73b933de35a6b7c1010162ff566292`, 392 `espeak-ng-data/` entries). This hash is a reviewer record only: the in-app voice-pack download performs no hash check, and the URL is unpinned in code (`core/voice/.../SherpaPiperVoice.kt`).

## Remaining release acceptance

The attribution verifier requires the three wake-word assets to be packaged with `license_status` `reviewed` and matching SHA-256 pins (`wake_word_assets` in `app/release-attribution-metadata.json`). They ship under CC BY-NC-SA 4.0 as published, per the owner decision recorded at https://github.com/NickMonrad/kernel-ai-assistant/issues/2017#issuecomment-7056043539 (21 Nov). That decision overrides the licence review in draft PR #2020, whose verdicts for all three files and the Whisper export remain "unclear". CC BY-NC-SA 4.0 is non-commercial and carries ShareAlike; whether Play distribution is acceptable is the owner's decision and is not resolved here.

Do not mark #1793 ready until exact-head CI and the release attribution gate pass.

The following remain release-acceptance activities outside this code repair and must not be claimed without evidence:

- release-build offline navigation/display validation on a device;
- TalkBack/accessibility validation;
- light, dark and system-colour validation;
- final #1014 and #441 status updates tied to the selected release candidate.

