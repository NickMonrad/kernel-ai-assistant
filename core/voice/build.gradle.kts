plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Allow Android framework stubs (e.g. android.util.Log) to return default
            // values in JVM unit tests rather than throwing "not mocked" RuntimeExceptions.
            isReturnDefaultValues = true
            all { it.useJUnitPlatform() }
        }
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

    // WorkManager — required for VoicePackDownloadWorker / SherpaVoicePackDownloadManager
    implementation(libs.work.runtime.ktx)

    // Apache Commons Compress — BZip2 + Tar extraction for Sherpa Piper voice packs
    implementation(libs.commons.compress)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
