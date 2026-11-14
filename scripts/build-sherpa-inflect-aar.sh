#!/usr/bin/env bash
set -euo pipefail

readonly SHERPA_COMMIT="b0592803468fa98777e328aa38136cf6ec015a05"
readonly PATCH_SHA256="4c47b9c172d9849cf9f6f66a4cefefeaee9320197acfec23d8a19328accdcfa4"
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
patch="$repo_root/scripts/sherpa-onnx-v1.13.0-inflect.patch"
work_dir="${1:?Usage: $0 NEW_WORK_DIR NEW_OUTPUT_AAR}"
output_aar="${2:?Usage: $0 NEW_WORK_DIR NEW_OUTPUT_AAR}"

[[ ! -e "$work_dir" ]] || fail "work directory already exists; use a new disposable path: $work_dir"
[[ ! -e "$output_aar" ]] || fail "output already exists; refusing to overwrite: $output_aar"
[[ -f "$base_aar" ]] || fail "missing pinned stock AAR: $base_aar"
[[ -f "$patch" ]] || fail "missing checked-in Inflect patch: $patch"

sha256sum --check <(printf '%s  %s\n' "$BASE_AAR_SHA256" "$base_aar") || fail "stock AAR digest mismatch"
sha256sum --check <(printf '%s  %s\n' "$PATCH_SHA256" "$patch") || fail "Inflect patch digest mismatch"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk_root" && -d "$sdk_root" ]] || fail "set ANDROID_SDK_ROOT or ANDROID_HOME to an installed Android SDK"
ndk="$sdk_root/ndk/28.2.13676358"
[[ -d "$ndk" ]] || fail "required NDK 28.2.13676358 is not installed at $ndk"
ndk_revision=""
while IFS='=' read -r key value; do
  key="${key//[[:space:]]/}"
  if [[ "$key" == "Pkg.Revision" ]]; then ndk_revision="${value//[[:space:]]/}"; fi
done < "$ndk/source.properties"
[[ "$ndk_revision" == "28.2.13676358" ]] || fail "unexpected NDK revision: $ndk_revision"

command -v tar >/dev/null 2>&1 || fail "tar is required"

command -v git >/dev/null 2>&1 || fail "git is required"
command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v unzip >/dev/null 2>&1 || fail "unzip is required"
command -v python3 >/dev/null 2>&1 || fail "python3 is required"

mkdir -p "$(dirname "$output_aar")"
mkdir -p "$work_dir/inputs" "$work_dir/logs" "$work_dir/home" "$work_dir/tmp" "$work_dir/tools"
work_dir="$(cd "$work_dir" && pwd)"
output_aar="$(cd "$(dirname "$output_aar")" && pwd)/$(basename "$output_aar")"
cmake_archive="${CMAKE_ARCHIVE:-$work_dir/inputs/cmake-4.4.2-linux-x86_64.tar.gz}"
if [[ -z "${CMAKE_ARCHIVE:-}" ]]; then
  curl --fail --location --silent --show-error --connect-timeout 20 --max-time 600 "$CMAKE_URL" --output "$cmake_archive"
fi
sha256sum --check <(printf '%s  %s\n' "$CMAKE_ARCHIVE_SHA256" "$cmake_archive") || fail "CMake archive digest mismatch"
tar -xzf "$cmake_archive" -C "$work_dir/tools"
cmake_bin="$work_dir/tools/cmake-4.4.2-linux-x86_64/bin/cmake"
[[ -x "$cmake_bin" ]] || fail "pinned CMake archive did not contain the expected executable"
cmake_version_output="$("$cmake_bin" --version)"
[[ "$cmake_version_output" == "cmake version $CMAKE_VERSION"* ]] || fail "unexpected CMake version: ${cmake_version_output%%$'\n'*}"
# The patch removes DWARF/path variation and disables the pre-strip linker build ID.
# Clean builds differed only in that 20-byte ID after stripping.
sherpa="$work_dir/sherpa"

# The checked-in patch passes -g0 plus a stable prefix map to each ABI build.
# This excludes scratch paths from DWARF before LLD hashes the unstripped ELF.
export SHERPA_BUILD_ROOT="$sherpa"

# Keep CMake user caches isolated. Sherpa's FetchContent declarations verify
# each dependency archive using its pinned URL_HASH before extracting it.
export HOME="$work_dir/home"
export TMPDIR="$work_dir/tmp"
export ANDROID_NDK="$ndk"
export ANDROID_SDK_ROOT="$sdk_root"
export PATH="$(dirname "$(command -v "$cmake_bin")"):$(dirname "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"):$(dirname "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"):$PATH"
export BUILD_SHARED_LIBS=ON
export SHERPA_ONNX_ENABLE_TTS=ON
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
export SHERPA_ONNX_ENABLE_BINARY=OFF
export SHERPA_ONNX_ENABLE_C_API=OFF
export SHERPA_ONNX_ENABLE_RKNN=OFF
export SHERPA_ONNX_ENABLE_QNN=OFF
export SHERPA_ONNX_ANDROID_PLATFORM="$ANDROID_API"
export SHERPA_ONNX_ENABLE_JNI=ON

printf 'Pinned Sherpa source: %s\n' "$SHERPA_COMMIT"
git clone --filter=blob:none --no-checkout https://github.com/k2-fsa/sherpa-onnx.git "$sherpa"
git -C "$sherpa" checkout --detach "$SHERPA_COMMIT"
[[ "$(git -C "$sherpa" rev-parse HEAD)" == "$SHERPA_COMMIT" ]] || fail "Sherpa checkout SHA mismatch"
git -C "$sherpa" apply --check "$patch"
git -C "$sherpa" apply "$patch"

ort_zip="$work_dir/inputs/onnxruntime-android-${ORT_VERSION}.zip"
curl --fail --location --silent --show-error --connect-timeout 20 --max-time 600 "$ONNXRUNTIME_URL" --output "$ort_zip"
sha256sum --check <(printf '%s  %s\n' "$ONNXRUNTIME_SHA256" "$ort_zip") || fail "ONNX Runtime archive digest mismatch"
unzip -tq "$ort_zip" || fail "ONNX Runtime archive is corrupt"

for target in "${ABI_TARGETS[@]}"; do
  IFS=: read -r abi build_suffix script_name <<< "$target"
  build_dir="$sherpa/build-android-$build_suffix"
  mkdir -p "$build_dir/$ORT_VERSION"
  unzip -q -o "$ort_zip" -d "$build_dir/$ORT_VERSION"
  [[ -s "$build_dir/$ORT_VERSION/jni/$abi/libonnxruntime.so" ]] || fail "pinned ONNX Runtime archive lacks $abi library"
  printf 'Building %s with %s\n' "$abi" "$script_name"
  (
    cd "$sherpa"
    bash "./$script_name"
  ) 2>&1 | tee "$work_dir/logs/$abi.log"
  [[ -s "$build_dir/install/lib/libsherpa-onnx-jni.so" ]] || fail "missing $abi JNI output"
done

python3 "$repo_root/scripts/package-sherpa-inflect-aar.py" \
  --base-aar "$base_aar" \
  --build-root "$sherpa" \
  --output "$output_aar" \
  --llvm-nm "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" \
  --llvm-readelf "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"

{
  printf 'Sherpa commit: %s\n' "$SHERPA_COMMIT"
  printf 'Sherpa tree: %s\n' "$(git -C "$sherpa" rev-parse 'HEAD^{tree}')"
  printf 'Inflect patch SHA-256: %s\n' "$PATCH_SHA256"
  printf 'Base stock AAR SHA-256: %s\n' "$BASE_AAR_SHA256"
  printf 'ONNX Runtime archive SHA-256: %s\n' "$ONNXRUNTIME_SHA256"
  printf 'NDK revision: %s\n' "$ndk_revision"
  printf 'CMake version: %s\n' "$CMAKE_VERSION"
  printf 'CMake archive SHA-256: %s\n' "$CMAKE_ARCHIVE_SHA256"
  printf 'Android platform: %s\n' "$ANDROID_API"
  printf 'Output AAR SHA-256: '
  sha256sum "$output_aar"
  printf 'Build logs: %s/logs\n' "$work_dir"
} | tee "$work_dir/build-manifest.txt"
printf 'Build scratch retained at %s\n' "$work_dir"
