plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.kernel.ai.core.voice"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    testOptions {
        unitTests {
            // Allow Android framework stubs (e.g. android.util.Log) to return default
            // values in JVM unit tests rather than throwing "not mocked" RuntimeExceptions.
            isReturnDefaultValues = true
            all { it.useJUnitPlatform() }
        }
    }

    sourceSets {
        getByName("main").assets.setSrcDirs(emptyList<String>())
    }
}

// ── Sherpa-ONNX spike (reflection-only — no compile-time dependency on Sherpa) ──────
// SherpaOnnxVoiceOutputController uses Class.forName() for all Sherpa access so this
// module compiles without the AAR.  Add the AAR to :app so it is included in the APK
// at runtime; see :app/build.gradle.kts for the conditional implementation block.

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.vosk.android)
    // ONNX Runtime — wake word inference (OnnxWakeWordDetector, 3-stage openWakeWord pipeline)
    implementation(libs.onnxruntime.android)
    // JVM ONNX Runtime (CPU) — real-model Stage 3 classifier tests (WakeWordClassifierModelTest)
    testImplementation(libs.onnxruntime)

    // WorkManager — required for VoicePackDownloadWorker / SherpaVoicePackDownloadManager
    implementation(libs.work.runtime.ktx)

    // Apache Commons Compress — BZip2 + Tar extraction for Sherpa Piper voice packs
    implementation(libs.commons.compress)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}

// WakeWordClassifierModelTest reads the authoritative app classifier (committed in
// app/src/main/assets) as the JVM test resource /models/wakeword/hey_jandal.onnx.
// AGP 9 unit-test java resources only pick up src/test/resources from the test source
// set, so map the app asset directory into the unit-test java-res copy task directly;
// the same committed file ships in the APK — no duplicate model binary is committed.
// (Eager file() is required: AGP re-wires the copy spec with lazy providers during
// task realization, which silently drops lazily-supplied from() sources.)
tasks.withType<Sync>().configureEach {
    if (name == "processDebugUnitTestJavaRes") {
        from(file("../../app/src/main/assets"))
    }
}
