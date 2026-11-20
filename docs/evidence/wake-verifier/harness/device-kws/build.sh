#!/usr/bin/env bash
# Builds the KWS device lane: KwsProbe + the app's pinned sherpa-onnx AAR classes/JNI + onnxruntime-android.
# usage: build.sh <out-dir>     (needs javac, Android SDK build-tools/platform 36, the Gradle cache)
set -euo pipefail
out=$1
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../../../.." && pwd)
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
AJAR=$SDK/platforms/android-36/android.jar
D8=$SDK/build-tools/36.1.0/d8
AAR=$root/third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort-inflect.aar
ORT=$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime-android/1.24.3" -name '*.aar' | head -n1)
STD=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.3.21" -name 'kotlin-stdlib-2.3.21.jar' | head -n1)
rm -rf "$out" && mkdir -p "$out/tmp" "$out/lib"
unzip -q -o "$AAR" classes.jar 'jni/arm64-v8a/*' -d "$out/tmp/sherpa"
unzip -q -o "$ORT" 'jni/arm64-v8a/libonnxruntime.so' -d "$out/tmp/ort"
cp "$out"/tmp/sherpa/jni/arm64-v8a/*.so "$out/tmp/ort/jni/arm64-v8a/libonnxruntime.so" "$out/lib/"
javac -nowarn -source 17 -target 17 -cp "$out/tmp/sherpa/classes.jar:$AJAR" -d "$out/tmp/cls" "$here/KwsProbe.java"
"$D8" --release --min-api 35 --lib "$AJAR" --output "$out" "$out/tmp/cls/KwsProbe.class" "$out/tmp/sherpa/classes.jar" "$STD"
rm -rf "$out/tmp"
sha256sum "$AAR" "$ORT" "$STD" > "$out/inputs.sha256"
ls -la "$out" "$out/lib"
