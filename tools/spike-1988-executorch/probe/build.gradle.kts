plugins {
    alias(libs.plugins.android.application)
}

// The ExecuTorch lane needs a Kotlin driver for FUTO's asr4all ExecuTorch bundle. FUTO's reference driver
// (`executorch/asr4all_executorch.py`) carries no licence, so no port of it is published in this repository.
// The driver (`EtAsr4allDecoder` + `Asr4allEtGeometry`, package `ai.jandal.spike1988`) lives in a private
// directory outside Git and is compiled in from `<dir>/main`; `<dir>/androidTest` (optional) holds the
// root-cause `StepProbeTest`. See ../README.md.
val privateEtDecoderDir = providers.gradleProperty("privateEtDecoderDir")
    .orElse(System.getProperty("user.home") + "/.local/share/jandal-private/spike-1988-et-decoder")
    .map { File(it) }
    .get()
val privateEtMain = File(privateEtDecoderDir, "main")
val privateEtAndroidTest = File(privateEtDecoderDir, "androidTest")
check(listOf("EtAsr4allDecoder.kt", "Asr4allEtGeometry.kt").all { File(privateEtMain, it).isFile }) {
    "Private ExecuTorch driver not found: expected EtAsr4allDecoder.kt and Asr4allEtGeometry.kt in " +
        "$privateEtMain (set -PprivateEtDecoderDir=<dir>). It is not published; see tools/spike-1988-executorch/README.md."
}

// Jandal's ONNX Runtime FP32 asr4all engine is compiled from PR #1987's exact head, read straight out of
// Git, so the comparison lane runs that code unmodified and nothing is copied into this branch.
// A second copy in package `...asr4all.tuned` is the ORT control lane (#1988 review): identical code except
// openSession uses 4 intra-op threads with the CPU arena and weight prepacking left enabled.
val onnxEngineCommit = providers.gradleProperty("onnxEngineCommit")
    .orElse("591e82919851bdd77e4490446fe61162f7109eb0")
val onnxEngineFiles = listOf(
    "Asr4allEngine.kt",
    "Asr4allExternalData.kt",
    "Asr4allMetadata.kt",
    "Asr4allPcec.kt",
    "Asr4allTensors.kt",
)
val onnxEngineDir = layout.buildDirectory.dir("generated/onnxEngine/kotlin")
val repoRoot = rootProject.projectDir.parentFile.parentFile
val extractOnnxEngine by tasks.registering {
    val commit = onnxEngineCommit
    val out = onnxEngineDir
    inputs.property("commit", commit)
    outputs.dir(out)
    doLast {
        val root = out.get().dir("com/kernel/ai/core/voice/asr4all").asFile.apply { deleteRecursively(); mkdirs() }
        val tuned = root.resolve("tuned").apply { mkdirs() }
        for (name in onnxEngineFiles) {
            val path = "core/voice/src/main/java/com/kernel/ai/core/voice/asr4all/$name"
            val proc = ProcessBuilder("git", "show", "${commit.get()}:$path")
                .directory(repoRoot).redirectErrorStream(false).start()
            val bytes = proc.inputStream.readBytes()
            check(proc.waitFor() == 0) { "git show ${commit.get()}:$path failed" }
            root.resolve(name).writeBytes(bytes)
            var text = String(bytes).replaceFirst(
                "package com.kernel.ai.core.voice.asr4all",
                "package com.kernel.ai.core.voice.asr4all.tuned",
            )
            if (name == "Asr4allEngine.kt") {
                val original = "so.setCPUArenaAllocator(false)\n" +
                    "                so.addConfigEntry(\"session.disable_prepacking\", \"1\")"
                check(original in text) { "#1987 openSession changed; update the ORT control patch" }
                text = text.replace(original, "so.setIntraOpNumThreads(4)")
            }
            tuned.resolve(name).writeText(text)
        }
    }
}

android {
    namespace = "ai.jandal.spike1988"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Isolated package: never the production app or its debug build.
        applicationId = "ai.jandal.spike1988.etprobe"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1988"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "ONNX_ENGINE_COMMIT", "\"${onnxEngineCommit.get()}\"")
    }

    buildFeatures { buildConfig = true }

    sourceSets.getByName("main").kotlin.directories.add(onnxEngineDir.get().asFile.path)
    sourceSets.getByName("main").kotlin.directories.add(privateEtMain.path)
    if (privateEtAndroidTest.isDirectory) {
        sourceSets.getByName("androidTest").kotlin.directories.add(privateEtAndroidTest.path)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/arm64-v8a/libc++_shared.so"
        }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(extractOnnxEngine)
}

dependencies {
    // ExecuTorch 1.5.1 is the release that loads the pinned FUTO PTEs (tag 3b60683923245cf472b7323426920e15623ba361).
    implementation("org.pytorch:executorch-android:1.5.1")
    // Same versions as the #1987 engine (core/voice).
    implementation(libs.onnxruntime.android)
    implementation(libs.gson)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
}
