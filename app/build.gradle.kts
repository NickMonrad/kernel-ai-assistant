import java.time.Instant
import java.util.zip.ZipFile

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
        unitTests {
            // Allow Android framework stubs (e.g. android.util.Log) to return default
            // values in JVM unit tests rather than throwing "not mocked" RuntimeExceptions.
            // The controller-to-wake integration tests drive the real
            // NativeAndroidVoiceInputController, which logs through android.util.Log.
            isReturnDefaultValues = true
            all { it.useJUnitPlatform() }
        }
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

tasks.register("verifyAcousticStimulusReleaseIsolation") {
    dependsOn("assembleDebug", "assembleRelease", "bundleRelease")
    doLast {
        val buildDirectory = layout.buildDirectory.get().asFile
        val manifests = buildDirectory.walkTopDown()
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .toList()
        fun mergedManifest(variant: String): File = manifests.firstOrNull { file ->
            file.path.contains("/$variant/") && file.path.contains("merged")
        } ?: error("Merged $variant manifest not found")

        val debugManifest = mergedManifest("debug").readText()
        val releaseManifest = mergedManifest("release").readText()
        check("package=\"com.kernel.ai.debug\"" in debugManifest) {
            "Debug manifest package must be com.kernel.ai.debug"
        }
        check("com.kernel.ai.debug.acoustic.AcousticStimulusReceiver" in debugManifest)
        check("com.kernel.ai.debug.action.PLAY_ACOUSTIC_STIMULUS" in debugManifest)
        check("com.kernel.ai.debug.acoustic.AcousticStimulusReceiver" !in releaseManifest)
        check("com.kernel.ai.debug.action.PLAY_ACOUSTIC_STIMULUS" !in releaseManifest)
        check("package=\"com.kernel.ai\"" in releaseManifest) {
            "Release manifest package must be com.kernel.ai"
        }

        val artifacts = listOf(
            buildDirectory.resolve("outputs/apk/debug/app-debug.apk"),
            buildDirectory.resolve("outputs/apk/release/app-release-unsigned.apk"),
            buildDirectory.resolve("outputs/bundle/release/app-release.aab"),
        )
        artifacts.forEach { artifact ->
            check(artifact.isFile) { "Missing artifact: ${artifact.path}" }
            ZipFile(artifact).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                check(names.none { it.endsWith(".wav", ignoreCase = true) }) {
                    "WAV fixture packaged in ${artifact.name}"
                }
                val helperBytes = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .any { entry ->
                        val text = zip.getInputStream(entry).use { input ->
                            input.readBytes().toString(Charsets.ISO_8859_1)
                        }
                        "AcousticStimulusReceiver" in text ||
                            "com/kernel/ai/debug/acoustic" in text
                    }
                if (artifact.name.contains("release")) {
                    check(!helperBytes) { "Debug acoustic helper packaged in ${artifact.name}" }
                } else {
                    check(helperBytes) { "Debug acoustic helper missing from ${artifact.name}" }
                }
            }
        }
    }
}

tasks.register("verifyTargetEventJournalReleaseIsolation") {
    dependsOn("assembleDebug", "assembleRelease", "bundleRelease")
    doLast {
        val buildDirectory = layout.buildDirectory.get().asFile
        val manifests = buildDirectory.walkTopDown()
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .toList()
        fun mergedManifest(variant: String): File = manifests.firstOrNull { file ->
            file.path.contains("/$variant/") && file.path.contains("merged")
        } ?: error("Merged $variant manifest not found")

        val debugManifest = mergedManifest("debug").readText()
        val releaseManifest = mergedManifest("release").readText()
        val journalBroadcastActions = listOf(
            "GET_JOURNAL_SEQUENCE",
            "GET_JOURNAL_SNAPSHOT",
        )
        val journalComponents = listOf(
            "com.kernel.ai.debug.journal.TargetEventJournalReceiver",
            "com.kernel.ai.debug.journal.TargetEventJournalProvider",
            "com.kernel.ai.debug.target-event-journal",
        )
        journalComponents.forEach { component ->
            check(component in debugManifest) { "Debug manifest must contain $component" }
            check(component !in releaseManifest) { "Release manifest must NOT contain $component" }
        }
        journalBroadcastActions.forEach { action ->
            check(action in debugManifest) { "Debug manifest must contain $action" }
            check(action !in releaseManifest) { "Release manifest must NOT contain $action" }
        }
        check("package=\"com.kernel.ai\"" in releaseManifest)

        val artifacts = listOf(
            buildDirectory.resolve("outputs/apk/debug/app-debug.apk"),
            buildDirectory.resolve("outputs/apk/release/app-release-unsigned.apk"),
            buildDirectory.resolve("outputs/bundle/release/app-release.aab"),
        )
        val debugMarkers = listOf(
            "TargetEventJournalReceiver",
            "TargetEventJournalProvider",
            "com/kernel/ai/debug/journal",
            "AcousticEventJournal",
        )
        artifacts.forEach { artifact ->
            check(artifact.isFile) { "Missing artifact: ${artifact.path}" }
            ZipFile(artifact).use { zip ->
                val dexText = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".dex") }
                    .map { entry ->
                        zip.getInputStream(entry).use { input ->
                            input.readBytes().toString(Charsets.ISO_8859_1)
                        }
                    }
                    .toList()
                check(dexText.isNotEmpty()) { "No DEX payload found in ${artifact.name}" }
                val containsDebugJournal = dexText.any { text ->
                    debugMarkers.any { marker -> marker in text }
                }
                if (artifact.name.contains("debug")) {
                    check(containsDebugJournal) {
                        "Debug journal implementation missing from ${artifact.name}"
                    }
                } else {
                    check(!containsDebugJournal) {
                        "Debug journal implementation present in ${artifact.name}"
                    }
                }
            }
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

    // Keep the custom frontend AAR debug-only. Release builds retain the upstream Sherpa
    // artifact, while debug builds use the custom symbol when it is available and otherwise
    // fall back to the upstream AAR for existing Sherpa functionality.
    val sherpaAar = rootProject.file("third_party/sherpa-onnx/sherpa-onnx-1.13.0.aar")
    val inflectSherpaAar = rootProject.file("third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort.aar")
    if (sherpaAar.exists()) {
        releaseImplementation(files(sherpaAar.absolutePath))
        if (!inflectSherpaAar.exists()) {
            debugImplementation(files(sherpaAar.absolutePath))
        }
    }
    if (inflectSherpaAar.exists()) {
        debugImplementation(files(inflectSherpaAar.absolutePath))
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
