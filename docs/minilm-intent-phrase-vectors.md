# MiniLM intent phrase vectors

`MiniLMIntentClassifier` uses the committed `app/src/main/assets/intent_phrase_vectors.bin` when its asset header matches the shipped `minilm-l6-v2-int8.tflite`, `vocab.txt`, and `intent_phrases.json`. The header binds the binary to the SHA-256 of each input; a payload SHA-256 detects corruption. A missing, stale, malformed, or corrupt vector file is logged and falls back to the existing runtime phrase builder.

The version-1 file uses big-endian integers and float32 values. Its 152-byte header contains the `JNDLMVEC` magic, format version, embedding dimension (384), intent and phrase counts, three 32-byte source hashes, and one 32-byte payload checksum. The payload stores each intent's UTF-8 name and phrase count, followed by that intent's row-major 384-float vectors. The current source has 39 intents and 515 phrases; the vector payload is about 773 KiB.

## Regenerate on an Android device

The opt-in instrumentation generator calls the same `MiniLMPhraseVectorizer` used by production fallback, with the shipped WordPiece tokenizer, TFLite model, pooling, and normalization. It computes all 515 vectors from the model, encodes and reloads the asset, then checks per-vector cosine similarity (at least 0.999), nearest-neighbour scores, top-1 classifications, and `QuickIntentRouter` decisions against the runtime-built vectors. The generator writes the artifact to the target app's external files directory and logs the build and asset-load durations.

Build and install both debug APKs with a version code above the installed app. Use the approved device serial and direct instrumentation; do not run a Gradle `connected*` task, which can remove the target app after testing.

`3381` below is only an example; choose a value greater than the installed package's version code.

```sh
export ANDROID_SERIAL='DEVICE_SERIAL'
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PversionCode=3381
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Optional classifier readiness measurement; this does not open a chat or write conversation data.
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class com.kernel.ai.MiniLMIntentClassifierReadinessTest \
  com.kernel.ai.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class com.kernel.ai.MiniLMPhraseVectorAssetGeneratorTest \
  -e generate_minilm_phrase_vector_asset true \
  com.kernel.ai.debug.test/androidx.test.runner.AndroidJUnitRunner
```
Immediately pull the generated file from the debug app's external files directory, verify its inputs, then run the freshness test:

```sh
adb -s "$ANDROID_SERIAL" pull /sdcard/Android/data/com.kernel.ai.debug/files/intent_phrase_vectors.bin \
  app/src/main/assets/intent_phrase_vectors.bin
sha256sum app/src/main/assets/minilm-l6-v2-int8.tflite \
  app/src/main/assets/vocab.txt app/src/main/assets/intent_phrases.json \
  app/src/main/assets/intent_phrase_vectors.bin
./gradlew :app:testDebugUnitTest
```

`MiniLMPhraseVectorAssetFreshnessTest` fails when the generated asset is absent, its payload is damaged, its shape changes, or any source file changes without regenerating the vectors. Keep the instrumentation parity assertions enabled when regenerating; the host test validates staleness and format, not TFLite model output.
