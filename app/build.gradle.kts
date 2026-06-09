import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val gitSha: String by lazy {
    val result = providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        isIgnoreExitValue = true
    }
    result.standardOutput.asText.get().trim().ifEmpty { "unknown" }
}

android {
    namespace = "com.kernel.ai"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kernel.ai"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "HF_CLIENT_ID", "\"2607cec6-3d70-4df0-ba39-eb9cef1ba8c8\"")
        buildConfigField("String", "HF_REDIRECT_URI", "\"com.kernel.ai://oauth/callback\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"${Instant.now()}\"")
    }

    signingConfigs {
        create("debugSigning") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debugSigning")
            buildConfigField("String", "HF_REDIRECT_URI", "\"com.kernel.ai.debug://oauth/callback\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/armeabi-v7a/libonnxruntime.so",
                "lib/x86/libonnxruntime.so",
                "lib/x86_64/libonnxruntime.so",
            )
        }
    }
}

dependencies {
    implementation(project(":core:inference"))
    implementation(project(":core:memory"))
    implementation(project(":core:voice"))
    implementation(project(":core:wasm"))
    implementation(project(":core:ui"))
    implementation(project(":core:skills"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:widget"))
    implementation(project(":feature:convert"))

    val sherpaAar = rootProject.file("third_party/sherpa-onnx/sherpa-onnx-1.13.0.aar")
        .takeIf { it.exists() }
        ?: rootProject.file("third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort.aar")
    if (sherpaAar.exists()) {
        implementation(files(sherpaAar.absolutePath))
    }

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    implementation(libs.work.runtime.ktx)
    ksp(libs.hilt.compiler)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.uiautomator)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary)

    implementation(libs.appauth)
    implementation(libs.security.crypto)
    implementation(libs.play.services.location)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
