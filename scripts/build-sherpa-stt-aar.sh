#!/usr/bin/env bash
# STT-only four-ABI Sherpa-ONNX JNI build for the v0.1 release (#1793).
# Pins the same Sherpa commit, ONNX Runtime and NDK as the #1659 Inflect recipe, but:
#   - no Jandal Inflect patch (no eSpeak-linked JNI seam);
#   - SHERPA_ONNX_ENABLE_TTS=OFF, C API OFF (no TTS entry points in the AAR);
#   - JNI ON for the offline recognizer (Whisper tiny.en verifier) only.
set -euo pipefail

readonly SHERPA_COMMIT="b0592803468fa98777e328aa38136cf6ec015a05"
readonly BASE_AAR_SHA256="233b6b19fb5515c047adebde0dbf873a9fd8ac23f1d2ff6a3701f7ffc923b23c"
readonly ONNXRUNTIME_SHA256="67397e4a970e75617f765d2015ceaf911917e1d822276cfb5792744e8085cbce"
readonly ONNXRUNTIME_URL="https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.24.3/onnxruntime-android-1.24.3.zip"
readonly CMAKE_VERSION="4.4.2"
readonly CMAKE_URL="https://cmake.org/files/v4.4/cmake-4.4.2-linux-x86_64.tar.gz"
readonly CMAKE_ARCHIVE_SHA256="3ada9a3f5d8a85413579bdd0ea6aa8e8da86efdd6d15c91a1afa517f2021956c"
readonly ANDROID_API="android-21"
readonly ORT_VERSION="1.24.3"
readonly ABI_TARGETS=(
  "arm64-v8a:arm64-v8a:build-android-arm64-v8a.sh"
  "armeabi-v7a:armv7-eabi:build-android-armv7-eabi.sh"
  "x86:x86:build-android-x86.sh"
  "x86_64:x86-64:build-android-x86-64.sh"
)

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
base_aar="$repo_root/third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort.aar"
work_dir="${1:?Usage: $0 NEW_WORK_DIR NEW_OUTPUT_AAR}"
output_aar="${2:?Usage: $0 NEW_WORK_DIR NEW_OUTPUT_AAR}"

[[ ! -e "$work_dir" ]] || fail "work directory already exists: $work_dir"
[[ ! -e "$output_aar" ]] || fail "output already exists: $output_aar"
sha256sum --check <(printf '%s  %s\n' "$BASE_AAR_SHA256" "$base_aar") || fail "stock AAR digest mismatch"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk_root" && -d "$sdk_root" ]] || fail "set ANDROID_SDK_ROOT or ANDROID_HOME"
ndk="$sdk_root/ndk/28.2.13676358"
[[ -d "$ndk" ]] || fail "NDK 28.2.13676358 missing at $ndk"

mkdir -p "$(dirname "$output_aar")" "$work_dir/inputs" "$work_dir/logs" "$work_dir/home" "$work_dir/tmp" "$work_dir/tools"
work_dir="$(cd "$work_dir" && pwd)"
output_aar="$(cd "$(dirname "$output_aar")" && pwd)/$(basename "$output_aar")"
cmake_archive="$work_dir/inputs/cmake-4.4.2-linux-x86_64.tar.gz"
curl --fail --location --silent --show-error --connect-timeout 20 --max-time 600 "$CMAKE_URL" --output "$cmake_archive"
sha256sum --check <(printf '%s  %s\n' "$CMAKE_ARCHIVE_SHA256" "$cmake_archive") || fail "CMake digest mismatch"
tar -xzf "$cmake_archive" -C "$work_dir/tools"
cmake_bin="$work_dir/tools/cmake-4.4.2-linux-x86_64/bin/cmake"
[[ "$("$cmake_bin" --version | head -1)" == "cmake version $CMAKE_VERSION"* ]] || fail "unexpected CMake"

sherpa="$work_dir/sherpa"
export SHERPA_BUILD_ROOT="$sherpa"
export HOME="$work_dir/home"
export TMPDIR="$work_dir/tmp"
export ANDROID_NDK="$ndk"
export ANDROID_SDK_ROOT="$sdk_root"
export PATH="$(dirname "$cmake_bin"):$PATH"
export BUILD_SHARED_LIBS=ON
export SHERPA_ONNX_ENABLE_TTS=OFF
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
export SHERPA_ONNX_ENABLE_BINARY=OFF
export SHERPA_ONNX_ENABLE_C_API=OFF
export SHERPA_ONNX_ENABLE_RKNN=OFF
export SHERPA_ONNX_ENABLE_QNN=OFF
export SHERPA_ONNX_ANDROID_PLATFORM="$ANDROID_API"
export SHERPA_ONNX_ENABLE_JNI=ON

printf 'Pinned Sherpa source: %s (no Inflect patch)\n' "$SHERPA_COMMIT"
git clone --filter=blob:none --no-checkout https://github.com/k2-fsa/sherpa-onnx.git "$sherpa"
git -C "$sherpa" checkout --detach "$SHERPA_COMMIT"
[[ "$(git -C "$sherpa" rev-parse HEAD)" == "$SHERPA_COMMIT" ]] || fail "Sherpa SHA mismatch"

ort_zip="$work_dir/inputs/onnxruntime-android-${ORT_VERSION}.zip"
curl --fail --location --silent --show-error --connect-timeout 20 --max-time 600 "$ONNXRUNTIME_URL" --output "$ort_zip"
sha256sum --check <(printf '%s  %s\n' "$ONNXRUNTIME_SHA256" "$ort_zip") || fail "ORT digest mismatch"
unzip -tq "$ort_zip" >/dev/null || fail "ORT archive corrupt"

for target in "${ABI_TARGETS[@]}"; do
  IFS=: read -r abi build_suffix script_name <<< "$target"
  build_dir="$sherpa/build-android-$build_suffix"
  mkdir -p "$build_dir/$ORT_VERSION"
  unzip -q -o "$ort_zip" -d "$build_dir/$ORT_VERSION"
  [[ -s "$build_dir/$ORT_VERSION/jni/$abi/libonnxruntime.so" ]] || fail "ORT lacks $abi"
  printf 'Building %s\n' "$abi"
  (cd "$sherpa" && bash "./$script_name") 2>&1 | tee "$work_dir/logs/$abi.log"
  [[ -s "$build_dir/install/lib/libsherpa-onnx-jni.so" ]] || fail "missing $abi JNI output"
done

{
  printf 'Sherpa commit: %s\nSherpa tree: %s\n' "$SHERPA_COMMIT" "$(git -C "$sherpa" rev-parse 'HEAD^{tree}')"
  printf 'ONNX Runtime SHA-256: %s\nNDK: 28.2.13676358\nCMake: %s\n' "$ONNXRUNTIME_SHA256" "$CMAKE_VERSION"
  printf 'TTS: OFF\nC API: OFF\n'
} > "$work_dir/build-inputs.txt"
printf 'Build outputs retained at %s\n' "$work_dir"
