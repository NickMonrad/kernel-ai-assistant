plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.kernel.ai"
    compileSdk = libs.versions.compileSdk.get().toInt()

    val gitSha: String = try {
        val stdout = java.io.ByteArrayOutputStream()
        exec { commandLine("git", "rev-parse", "--short", "HEAD"); standardOutput = stdout }
        stdout.toString().trim()
    } catch (_: Exception) { "unknown" }

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
        buildConfigField("String", "BUILD_TIMESTAMP", "\"${java.time.Instant.now()}\"")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // LiteRT-LM (transitive) uses internal Kotlin 2.3.x build (metadata 2.3.0)
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

dependencies {
    // Project modules
    implementation(project(":core:inference"))
    implementation(project(":core:memory"))
    implementation(project(":core:wasm"))
    implementation(project(":core:ui"))
    implementation(project(":core:skills"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:onboarding"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary)

    // Auth — AppAuth + EncryptedSharedPreferences
    implementation(libs.appauth)
    implementation(libs.security.crypto)
    implementation(libs.play.services.location)

    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
