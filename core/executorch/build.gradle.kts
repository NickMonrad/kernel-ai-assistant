plugins {
    alias(libs.plugins.android.library)
}

// Sole owner of the ExecuTorch Android runtime (docs/architecture/executorch-runtime.md).
// The dependency stays `implementation`: consumers use this module's API, which enforces
// output-shape assertions and the process-wide thread-pool guard.
android {
    namespace = "com.kernel.ai.core.executorch"
    compileSdk = libs.versions.compileSdk.get().toInt()

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
            isReturnDefaultValues = true
            all { it.useJUnitPlatform() }
        }
    }
}

dependencies {
    // Generic Module API plus the LLM runner (LlmModule) in one AAR; pulls fbjni 0.7.0
    // (libfbjni.so, libc++_shared.so) and soloader nativeloader 0.10.5.
    implementation(libs.executorch.android)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
}
