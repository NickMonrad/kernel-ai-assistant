plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.kernel.ai.core.inference"
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
        // LiteRT-LM is compiled with an internal Kotlin build (metadata 2.3.0).
        // Skip the strict metadata version check to allow compilation.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)

    // LiteRT-LM on-device inference
    implementation(libs.litertlm.android)

    // MediaPipe TextEmbedder (for Universal Sentence Encoder / EmbeddingGemma)
    implementation(libs.mediapipe.tasks.text)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
