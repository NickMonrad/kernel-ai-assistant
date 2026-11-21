import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
}

// The ExecuTorch lane needs a Kotlin driver for FUTO's asr4all ExecuTorch bundle. FUTO's reference driver
// (`executorch/asr4all_executorch.py`) carries no licence, so no port of it is published in this repository.
// The driver lives in a private directory outside Git (`EtAsr4allDecoder` + `Asr4allEtGeometry`, package
// `ai.jandal.asr4alltier`) and is compiled in from there. See ../README.md. Without it the probe still builds,
// with the Zipformer and Android native lanes only. The SHA-256 of each driver file is embedded in BuildConfig
// and written into every report, so ExecuTorch numbers trace to an exact driver source.
val privateEtDecoderDir = providers.gradleProperty("privateEtDecoderDir")
    .orElse(System.getProperty("user.home") + "/.local/share/jandal-private/asr4all-et-decoder")
    .map { File(it) }
    .get()
val privateDriverFiles = listOf("EtAsr4allDecoder.kt", "Asr4allEtGeometry.kt").map { File(privateEtDecoderDir, it) }
val hasEtDriver = privateDriverFiles.all { it.isFile }
fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    .joinToString("") { "%02x".format(it) }
val privateDriverSha256 = if (hasEtDriver) privateDriverFiles.joinToString(",") { "${it.name}=${sha256(it)}" } else ""
if (!hasEtDriver) logger.warn("Private ExecuTorch driver not found in $privateEtDecoderDir: building without the et lane.")

// Jandal's shipping transcript normaliser, compiled unmodified from Git so every lane can be scored through it.
val normaliserCommit = providers.gradleProperty("normaliserCommit")
    .orElse("00025895ee28a2cc1edba66a5bf887bb3a225574")
val normaliserDir = layout.buildDirectory.dir("generated/normaliser/kotlin")
val repoRoot = rootProject.projectDir.parentFile.parentFile
val extractNormaliser by tasks.registering {
    val commit = normaliserCommit
    val out = normaliserDir
    inputs.property("commit", commit)
    outputs.dir(out)
    doLast {
        val path = "core/voice/src/main/java/com/kernel/ai/core/voice/TranscriptNormaliser.kt"
        val proc = ProcessBuilder("git", "show", "${commit.get()}:$path").directory(repoRoot).start()
        val bytes = proc.inputStream.readBytes()
        check(proc.waitFor() == 0) { "git show ${commit.get()}:$path failed" }
        out.get().dir("com/kernel/ai/core/voice").asFile.apply { mkdirs() }
            .resolve("TranscriptNormaliser.kt").writeBytes(bytes)
    }
}

android {
    namespace = "ai.jandal.asr4alltier"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Isolated package: never the production app or its debug build.
        applicationId = "ai.jandal.spike.asr4alltier"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "tier-measurement"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "NORMALISER_COMMIT", "\"${normaliserCommit.get()}\"")
        buildConfigField("String", "PRIVATE_ET_DRIVER_SHA256", "\"$privateDriverSha256\"")
    }

    buildFeatures { buildConfig = true }

    sourceSets.getByName("main").kotlin.directories.add(normaliserDir.get().asFile.path)
    if (hasEtDriver) {
        sourceSets.getByName("main").kotlin.directories.add(privateEtDecoderDir.path)
        sourceSets.getByName("main").kotlin.directories.add("src/et/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("lib/arm64-v8a/libc++_shared.so", "lib/arm64-v8a/libonnxruntime.so")
            // Same as the app: the Sherpa JNI library is self-contained.
            excludes += setOf("lib/arm64-v8a/libsherpa-onnx-c-api.so", "lib/arm64-v8a/libsherpa-onnx-cxx-api.so")
        }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(extractNormaliser)
}

dependencies {
    // ExecuTorch 1.5.1 is the release that loads the pinned FUTO PTEs (tag 3b60683923245cf472b7323426920e15623ba361).
    implementation("org.pytorch:executorch-android:1.5.1")
    // Shipping Zipformer runtime: the app's own Sherpa AAR (reflection only) on the app's ONNX Runtime.
    implementation(files(repoRoot.resolve("third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort-inflect.aar")))
    implementation(libs.onnxruntime.android)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
}
