# Inflect Sherpa AAR rebuild manifest

This manifest records the host-built four-ABI replacement proposed for #1474. The replacement AAR is present in this draft branch only; it has not been merged, signed into an app release, or released. The manifest and build scripts are provenance inputs, not legal approval or a recipient-facing corresponding-source offer.

## Candidate artifact and source identity

- AAR: `third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort-inflect.aar`
- Candidate AAR SHA-256: `46a8e806833066bc657d8508e66e35dae8b1a04dff207ebd8538517d58b6567d`
- Candidate AAR size: 35,904,895 bytes
- Replaced AAR SHA-256: `cf35bb1999586fb6c2f5746bfc556504a6ee8407e02ed3047f1274fa8d2008dc`
- Base stock AAR: `third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort.aar`
- Base stock AAR SHA-256: `233b6b19fb5515c047adebde0dbf873a9fd8ac23f1d2ff6a3701f7ffc923b23c`
- Sherpa-ONNX repository: `https://github.com/k2-fsa/sherpa-onnx.git`
- Sherpa commit: `b0592803468fa98777e328aa38136cf6ec015a05`
- Sherpa Git tree object: `a52fc3c4bdc6babea7c242048643f7f09aed18c1`
- Checked-in Inflect patch: `scripts/sherpa-onnx-v1.13.0-inflect.patch`
- Inflect patch SHA-256: `4c47b9c172d9849cf9f6f66a4cefefeaee9320197acfec23d8a19328accdcfa4`

The patch adds `sherpa-onnx/jni/inflect-phonemizer.cc`, includes it in the JNI target, and exports `Java_com_kernel_ai_core_voice_InflectPhonemizer_nativePhonemize`. The Kotlin declaration is `InflectPhonemizer.nativePhonemize(String, String): String` in `core/voice/src/main/java/com/kernel/ai/core/voice/InflectPhonemizer.kt`.

## Toolchain and pinned build inputs

- Android SDK: existing SDK selected with process-local `ANDROID_SDK_ROOT`
- Android NDK: `28.2.13676358` (NDK Clang `19.0.1`)
- Android API: `android-21`
- CMake: `4.4.2`, archive URL `https://cmake.org/files/v4.4/cmake-4.4.2-linux-x86_64.tar.gz`, SHA-256 `3ada9a3f5d8a85413579bdd0ea6aa8e8da86efdd6d15c91a1afa517f2021956c`
- ONNX Runtime Android: `1.24.3`, archive URL `https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.24.3/onnxruntime-android-1.24.3.zip`, SHA-256 `67397e4a970e75617f765d2015ceaf911917e1d822276cfb5792744e8085cbce`
- Python: `3.14.8` in this run; package ZIP entries are stored with fixed metadata
- Build: release configuration; JNI/TTS/shared libraries enabled; C API, Python, Sherpa checks/tests, PortAudio and speaker diarization disabled; platform/ABI-specific flags come from the four upstream Android build scripts.

Pinned Sherpa CMake dependency archives and their upstream `URL_HASH` values:

| Dependency | Exact pinned upstream archive URL | SHA-256 |
|---|---|---|
| kaldi-native-fbank v1.22.3 | `https://github.com/csukuangfj/kaldi-native-fbank/archive/refs/tags/v1.22.3.tar.gz` | `9176cc66fc7ce1edf85cf355b06e320c57db6297df74277f575183468893cf61` |
| KissFFT commit `febd4caeed32e33ad8b2e0bb5ea77542c40f18ec` | `https://github.com/mborgerding/kissfft/archive/febd4caeed32e33ad8b2e0bb5ea77542c40f18ec.zip` | `497103e664168ebe39580b757adbe616f6cf85a16572af581ca7bc42d0ab13fd` |
| kaldi-decoder v0.3.0 | `https://github.com/k2-fsa/kaldi-decoder/archive/refs/tags/v0.3.0.tar.gz` | `b9f34cfb4fd3b1344100eead79ef4d37aa15962274b9e3056de345021f76a1b0` |
| kaldifst v1.8.0 | `https://github.com/k2-fsa/kaldifst/archive/refs/tags/v1.8.0.tar.gz` | `3f247b7e5a2409071202f5e2bc6200060f66728c0a3443c03923ad2723e040b3` |
| OpenFST v1.8.5-2026-04-11 | `https://github.com/csukuangfj/openfst/archive/refs/tags/v1.8.5-2026-04-11.tar.gz` | `57fbc4b950ae81b1a0e1e298af15652da968a6723a592b7874e9b4027a80a5b4` |
| Eigen v5.0.1 | `https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz` | `e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec` |
| simple-sentencepiece v0.7 | `https://github.com/pkufool/simple-sentencepiece/archive/refs/tags/v0.7.tar.gz` | `1748a822060a35baa9f6609f84efc8eb54dc0e74b9ece3d82367b7119fdc75af` |
| websocketpp commit `b9aeec6eaf3d5610503439b4fae3581d9aff08e8` | `https://github.com/zaphoyd/websocketpp/archive/b9aeec6eaf3d5610503439b4fae3581d9aff08e8.zip` | `1385135ede8191a7fbef9ec8099e3c5a673d48df0c143958216cd1690567f583` |
| Asio `asio-1-24-0` | `https://github.com/chriskohlhoff/asio/archive/refs/tags/asio-1-24-0.tar.gz` | `cbcaaba0f66722787b1a7c33afe1befb3a012b5af3ad7da7ff0f6b8c9b7a8a5b` |
| nlohmann/json v3.12.0 | `https://github.com/nlohmann/json/archive/refs/tags/v3.12.0.tar.gz` | `4b92eb0c06d10683f7447ce9406cb97cd4b453be18d7279320f7b2f025c10187` |
| eSpeak-ng commit `f6fed6c58b5e0998b8e68c6610125e2d07d595a7` | `https://github.com/csukuangfj/espeak-ng/archive/f6fed6c58b5e0998b8e68c6610125e2d07d595a7.zip` | `70cbf4050e7a014aae19140b05e57249da4720f56128459fbe3a93beaf971ae6` |
| Piper phonemize commit `78a788e0b719013401572d70fef372e77bff8e43` | `https://github.com/csukuangfj/piper-phonemize/archive/78a788e0b719013401572d70fef372e77bff8e43.zip` | `89641a46489a4898754643ce57bda9c9b54b4ca46485fdc02bf0dc84b866645d` |

The pinned source files declare these archive hashes; the controlled build logs record the source fetches. The build driver validates the CMake archive, ONNX Runtime archive, Sherpa commit, patch hash, and stock AAR hash before packaging.

## Rebuild

Use an installed Android SDK/NDK and a new disposable work directory. No machine-level configuration or signing material is needed. If `CMAKE_ARCHIVE` is omitted, the script fetches the pinned CMake archive itself.

```bash
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
export CMAKE_ARCHIVE=/path/to/verified/cmake-4.4.2-linux-x86_64.tar.gz  # optional
WORK=/tmp/jandal-sherpa-fourabi-01
bash scripts/build-sherpa-inflect-aar.sh "$WORK" "$WORK/candidate.aar"
sha256sum "$WORK/candidate.aar"
```

`CMAKE_ARCHIVE`, when supplied, is checked against the pinned SHA-256. The script clones the exact Sherpa commit, applies the hash-pinned patch, verifies the ONNX Runtime archive, and uses Sherpa's CMake `URL_HASH` checks for dependency downloads. It isolates `HOME` and `TMPDIR` under the disposable work directory and retains one build log per ABI plus a build manifest there. It refuses an existing work directory or output path. The checked-in patch passes `-g0`, `-ffile-prefix-map=<scratch>/sherpa=/src/sherpa`, and `-Wl,--build-id=none` through each ABI build. Two independent builds using only prefix mapping and `-g0` produced identical stripped sections but different 20-byte GNU build IDs; disabling that build ID made both JNI libraries and packaged AARs byte-identical.

The packager starts from the stock no-ORT AAR and replaces only these entries:

- `jni/arm64-v8a/libsherpa-onnx-jni.so`
- `jni/armeabi-v7a/libsherpa-onnx-jni.so`
- `jni/x86/libsherpa-onnx-jni.so`
- `jni/x86_64/libsherpa-onnx-jni.so`

ZIP entry timestamps and metadata are fixed and entries are stored without compression. The verifier checks all four ELF machine types, the required JNI export, identical `DT_NEEDED` sets containing only Android system libraries plus `libonnxruntime.so`, ZIP CRCs, and byte-for-byte content equality for every non-replaced base-AAR entry.

## Candidate verification evidence

| ABI | ELF machine | Inflect JNI SHA-256 |
|---|---|---|
| `arm64-v8a` | AArch64 | `a2115748b48dea4a4352b695903699c4a8026d2d580a962e95fc9185916a0da8` |
| `armeabi-v7a` | ARM | `977e47c93087247589dcd4ab723c3b87865152d6ec6445705747aea6b1c96d97` |
| `x86` | Intel 80386 | `48a266545de8455cfd71e48d9b4efcbeb0652def64808f2000b7a53e49cf89db` |
| `x86_64` | Advanced Micro Devices X86-64 | `f88da1d518c7922d3f032d1807ee7132174b4afac63556aab9c5327d5f48babd` |

Two independent clean runs, `/tmp/jandal-1474-noid-h-20261025T033352Z-35281` and `/tmp/jandal-1474-noid-i-20261025T034102Z-35281`, exited 0 and produced byte-identical JNI outputs for all four ABIs and identical AAR SHA-256 `46a8e806833066bc657d8508e66e35dae8b1a04dff207ebd8538517d58b6567d`. Each packager run verified ELF machine type, the required JNI export, `DT_NEEDED`, ZIP CRCs, and byte-for-byte preservation of every non-replaced base-AAR entry. The tracked draft candidate is copied from run H and has that same AAR hash.

## Open #1474/#1263 release obligations

The retained Piper source tree includes `LICENSE.md` and `licenses/uni-algo/LICENSE.md`; the retained eSpeak-ng tree includes `COPYING`, `COPYING.UCD`, and `COPYING.APACHE`. These are observed source-tree inputs only, not a complete notice inventory. The supplemental draft bundle in `third_party/sherpa-onnx/sherpa-onnx-v1.13.0-inflect-source-inputs.tar.gz` contains the pinned Sherpa source snapshot, checked-in patch, and the 12 pinned CMake dependency source archives for this replacement candidate. It is a review artifact, not a permanent recipient-facing source location and not historical source evidence for the earlier AAR. Complete notices/SBOM and legal sufficiency remain unresolved; Nick's legal/compliance review and the #1263 release-artifact/notice reconciliation remain required before any signed release or distribution.

No Android app release candidate, signed package, device run, Play upload, merge, or legal approval is included in this work.