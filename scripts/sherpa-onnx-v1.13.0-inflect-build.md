# Inflect Micro Sherpa frontend build provenance

This is the pinned Sherpa-ONNX rebuild used by the Inflect Micro release
voice path (#1510), originally produced and validated by issue #1475. The
checked-in patch is `scripts/sherpa-onnx-v1.13.0-inflect.patch`.

## Exact inputs

- Repository: `https://github.com/k2-fsa/sherpa-onnx.git`
- Revision: `b0592803468fa98777e328aa38136cf6ec015a05`
- Sherpa release/API level: v1.13.0
- ONNX Runtime Android shared library: 1.24.3, arm64-v8a, from
  `https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.24.3/onnxruntime-android-1.24.3.zip`
- Android NDK used for the retained S23U build: 28.2.13676358
- ABI: `arm64-v8a`
- Android platform: `android-21`
- CMake: 4.4.2
- Custom Inflect-enabled AAR used by debug and release:
  `third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort-inflect.aar`
- Custom Inflect-enabled AAR SHA-256:
  `cf35bb1999586fb6c2f5746bfc556504a6ee8407e02ed3047f1274fa8d2008dc`
- Stock no-ORT AAR retained as a byte-level comparison/reference artifact:
  `third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort.aar`
- Stock reference AAR SHA-256:
  `233b6b19fb5515c047adebde0dbf873a9fd8ac23f1d2ff6a3701f7ffc923b23c`
- arm64 JNI SHA-256 before AAR packaging:
  `3fa47f550edfe2bebaa8f6d219cfc2c62c38b77284f124b79f43701a535d4eac`

The app uses the custom AAR for both debug and release so the release-visible
Inflect controller resolves the existing narrow JNI symbol. The native binary
is unchanged by #1510; this issue only promotes the already-validated artifact
to the supported release configuration.

Both AARs remain checked in under the ignored `third_party/sherpa-onnx/`
directory with explicit force-adds.

## Rebuild

Run from a clean checkout of the exact revision. The build script downloads the
pinned ONNX Runtime archive into its build directory when absent.

```bash
git clone https://github.com/k2-fsa/sherpa-onnx.git /tmp/sherpa-onnx-v1.13.0
cd /tmp/sherpa-onnx-v1.13.0
git checkout b0592803468fa98777e328aa38136cf6ec015a05
git apply /path/to/kernel-ai-assistant/scripts/sherpa-onnx-v1.13.0-inflect.patch

export ANDROID_NDK="$ANDROID_SDK_ROOT/ndk/28.2.13676358"
export BUILD_SHARED_LIBS=ON
export SHERPA_ONNX_ENABLE_TTS=ON
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
export SHERPA_ONNX_ENABLE_BINARY=OFF
export SHERPA_ONNX_ENABLE_C_API=OFF
export SHERPA_ONNX_ENABLE_RKNN=OFF
export SHERPA_ONNX_ENABLE_QNN=OFF
export SHERPA_ONNX_ANDROID_PLATFORM=android-21
export SHERPA_ONNX_ENABLE_JNI=ON

./build-android-arm64-v8a.sh
```

The script's CMake invocation also keeps the approved probe build narrow:
Piper/eSpeak executables and tests, Python, Sherpa tests/checks, PortAudio and
speaker diarization are disabled; JNI and TTS are enabled; C++ shared runtime
linking is retained. The output is:

```text
build-android-arm64-v8a/install/lib/libsherpa-onnx-jni.so
build-android-arm64-v8a/install/lib/libonnxruntime.so
```

For the local app AAR, replace only the matching
`jni/arm64-v8a/libsherpa-onnx-jni.so` entry in the existing no-ORT v1.13.0 AAR.
Do not add another Sherpa/eSpeak data tree or a second native phonemizer. The
existing AAR's classes and non-arm64 entries remain unchanged for this debug
spike. Verify the custom symbol before installing:

```bash
nm -D --defined-only libsherpa-onnx-jni.so \
  | grep Java_com_kernel_ai_core_voice_InflectPhonemizer_nativePhonemize
```

The wrapper calls Sherpa's existing `InitEspeak()` and serialized
`CallPhonemizeEspeak()` path; it does not call `piper::phonemize_eSpeak()`
directly.
