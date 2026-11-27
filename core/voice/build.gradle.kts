plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.kernel.ai.core.voice"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = "28.2.13676358"

    // Kokoro phonemis JNI (#2117): the ExecuTorch lane's phonemizer, built into every variant.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
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

    // #769: the module must not bundle the old sherpa-tts assets, so the default src/main/assets
    // dir stays cleared. The lexicon (#1852) is added from its own dir below.
    sourceSets.getByName("main").assets.setSrcDirs(emptyList<String>())
    sourceSets.getByName("main").assets.srcDir("src/lexicon/assets")
    // Silero VAD v4 (MIT; #2078). Endpointing model for the asr4all reader; see docs/VOICE_MODEL_ATTRIBUTION.md.
    sourceSets.getByName("main").assets.srcDir("src/vad/assets")
}

// ── Sherpa-ONNX spike (reflection-only — no compile-time dependency on Sherpa) ──────
// SherpaOnnxVoiceOutputController uses Class.forName() for all Sherpa access so this
// module compiles without the AAR.  Add the AAR to :app so it is included in the APK
// at runtime; see :app/build.gradle.kts for the conditional implementation block.

dependencies {
    implementation(project(":core:inference"))
    // ExecuTorch, only through the shared runtime module (#2003): asr4all engine (#2004).
    implementation(project(":core:executorch"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    // Pronunciation lexicon JSON (#1852). org.json is stubbed in JVM unit tests.
    implementation(libs.gson)
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

    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}

// The asr4all host harness (Asr4AllHostHarnessTest, #2004) reads its config path from the
// environment, which Gradle does not track: make it an input and never treat a harness run as
// up to date, so setting the variable always runs the models.
tasks.withType<Test>().configureEach {
    val harnessConfig = providers.environmentVariable("ASR4ALL_HARNESS_CONFIG")
    inputs.property("asr4allHarnessConfig", harnessConfig.orElse(""))
    outputs.upToDateWhen { !harnessConfig.isPresent }
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
