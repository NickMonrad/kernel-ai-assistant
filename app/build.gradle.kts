import java.io.File
import java.time.Instant
import java.util.Properties
import java.util.HexFormat
import java.util.zip.ZipFile
import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.aboutlibraries.android)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.gradle.play.publisher)
}

val playTrack = providers.gradleProperty("playTrack").orElse("internal").get()
check(playTrack in setOf("internal", "alpha", "beta")) {
    "Invalid -PplayTrack='$playTrack'; allowed values are internal, alpha, beta. Production publishing is disabled."
}

val defaultPlayServiceAccountFile =
    File(System.getProperty("user.home"), ".local/share/jandal/play-publisher.json")
val configuredPlayServiceAccountPath = providers.gradleProperty("playServiceAccountFile")
    .orElse(providers.environmentVariable("PLAY_SERVICE_ACCOUNT_FILE"))
    .orNull
val playServiceAccountFile = configuredPlayServiceAccountPath?.let(::file)
    ?: defaultPlayServiceAccountFile
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val isPlayBundlePublishRequested = "publishReleaseBundle" in requestedTaskNames
val isTesterApkRequested = isPlayBundlePublishRequested &&
    providers.gradleProperty("githubTesterApk").orNull == "true"

play {
    if (isPlayBundlePublishRequested && playServiceAccountFile.isFile) {
        serviceAccountCredentials.set(playServiceAccountFile)
    }
    track.set(playTrack)
    releaseStatus.set(ReleaseStatus.COMPLETED)
    defaultToAppBundles.set(true)
    resolutionStrategy.set(
        if (isPlayBundlePublishRequested) ResolutionStrategy.AUTO else ResolutionStrategy.IGNORE,
    )
}

// Resolved by Gradle so the verifier never depends on a separately installed bundletool.
val releaseBundletoolClasspath = configurations.detachedConfiguration(
    dependencies.create(libs.bundletool.get()),
)

val verifyReleaseBundleForPlayUpload = tasks.register("verifyReleaseBundleForPlayUpload") {
    group = "verification"
    description = "Verifies the release AAB before a Play upload."
    dependsOn("bundleRelease")
    inputs.files(releaseBundletoolClasspath)
    doLast {
        val bundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile
        check(bundle.isFile) { "Missing release bundle: ${bundle.path}" }
        val verifier = rootProject.file("scripts/release/verify_release_bundle.sh")
        providers.exec {
            commandLine("bash", verifier.absolutePath, bundle.absolutePath)
            environment("BUNDLETOOL_CLASSPATH", releaseBundletoolClasspath.asPath)
        }.result.get().assertNormalExitValue()
    }
}

// Checks the tester APK against the Play-assigned versionCode and the upload certificate before
// publishReleaseBundle uploads. processReleaseVersionCodes (GPP) writes the code this run uses.
val verifyTesterApkForPlayPublish = tasks.register("verifyTesterApkForPlayPublish") {
    group = "verification"
    description = "Verifies the signed tester APK matches the Play upload before publishing."
    dependsOn("assembleRelease", "bundleRelease", "processReleaseVersionCodes")
    doLast {
        val buildDirectory = layout.buildDirectory.get().asFile
        val buildToolsDirectory = locateAapt2().parentFile.absolutePath
        // providers.exec captures the output, so it reaches Gradle's console and CI log even
        // when the build runs in a daemon (ProcessBuilder.inheritIO writes to the daemon's stdio).
        val result = providers.exec {
            commandLine(
                "bash",
                rootProject.file("scripts/release/verify_tester_apk.sh").absolutePath,
                releaseApkArtifact(buildDirectory, isSigned = true).absolutePath,
                buildDirectory.resolve("outputs/bundle/release/app-release.aab").absolutePath,
                buildDirectory.resolve("intermediates/gpp/release/available-version-codes.txt").absolutePath,
                releaseVersionPrefix,
            )
            environment("BUILD_TOOLS_DIR", buildToolsDirectory)
            isIgnoreExitValue = true
        }
        val output = result.standardOutput.asText.get().trim()
        val errors = result.standardError.asText.get().trim()
        if (output.isNotEmpty()) logger.lifecycle(output)
        if (errors.isNotEmpty()) logger.lifecycle(errors)
        val exitValue = result.result.get().exitValue
        check(exitValue == 0) { "Tester APK verification failed (exit $exitValue); see the lines above." }
    }
}

// GPP's version-code task runs before bundle creation, so reject missing keys before any task executes.
gradle.taskGraph.whenReady {
    val unsupportedPublisherTask = gradle.taskGraph.allTasks.firstOrNull { task ->
        task.project == project &&
            task.name != "publishReleaseBundle" &&
            (task.name.startsWith("publish", ignoreCase = true) ||
                task.name.startsWith("promote", ignoreCase = true) ||
                task.name.startsWith("upload", ignoreCase = true))
    }
    if (unsupportedPublisherTask != null) {
        throw org.gradle.api.GradleException(
            "Only :app:publishReleaseBundle is enabled for Play uploads; APK, metadata, internal-sharing, and promotion tasks are disabled."
        )
    }
    if (isPlayBundlePublishRequested && !playServiceAccountFile.isFile) {
        throw org.gradle.api.GradleException(
            "Play publishing credentials are missing. Set -PplayServiceAccountFile=/absolute/path/to/service-account.json or PLAY_SERVICE_ACCOUNT_FILE, or place the JSON at ~/.local/share/jandal/play-publisher.json."
        )
    }
}

tasks.configureEach {
    if (name.startsWith("promote", ignoreCase = true) ||
        name.startsWith("upload", ignoreCase = true) ||
        (name.startsWith("publish", ignoreCase = true) && name != "publishReleaseBundle")
    ) {
        enabled = false
        group = null
    }
    if (name == "publishReleaseBundle") {
        dependsOn(verifyReleaseBundleForPlayUpload)
        // Play releases must pass the release attribution gate even when CI on this commit did not run it.
        dependsOn("verifyReleaseAttributionMetadata")
        // Requested by play-release.yml only when the tester APK is published too. The APK is
        // checked (and built) before this task runs, so a bad APK never reaches Play.
        if (isTesterApkRequested) dependsOn(verifyTesterApkForPlayPublish)
    }
}

val gitSha: String by lazy {
    val result = providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        isIgnoreExitValue = true
    }
    result.standardOutput.asText.get().trim().ifEmpty { "unknown" }
}

val buildTimestamp = (project.findProperty("buildTimestamp") as String?)
    ?.let { Instant.parse(it).toString() }
    ?: Instant.now().toString()
val isolatedInflectProbe = providers.gradleProperty("isolatedInflectProbe")
    .map(String::toBooleanStrict)
    .orElse(false)
    .get()
val baseApplicationId = "ai.jandal.assistant"
val debugApplicationId = if (isolatedInflectProbe) {
    "$baseApplicationId.debug.inflectprobe"
} else {
    "$baseApplicationId.debug"
}
if (isolatedInflectProbe) {
    // Keep probe APKs separate from the normal app-debug.apk preview/build output.
    layout.buildDirectory.set(layout.projectDirectory.dir("build/inflectProbe"))
}

val releaseSigningPropertiesFile = providers.gradleProperty("releaseSigningPropertiesFile")
    .map { rootProject.file(it) }
    .orElse(rootProject.file("keystore.properties"))
    .get()
val releaseSigningProperties = releaseSigningPropertiesFile
    .takeIf { it.isFile }
    ?.let { propertiesFile ->
        val properties = Properties().apply {
            propertiesFile.inputStream().use { load(it) }
        }
        val requiredProperties = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        val missingProperties = requiredProperties.filter {
            properties.getProperty(it).isNullOrBlank()
        }
        check(missingProperties.isEmpty()) {
            "Invalid keystore.properties; missing required entries: ${missingProperties.joinToString()}"
        }
        val signingStoreFile = rootProject.file(properties.getProperty("storeFile")).canonicalFile
        check(signingStoreFile.isFile) {
            "Invalid keystore.properties; storeFile must point to an existing local keystore."
        }
        check(!signingStoreFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
            "Invalid keystore.properties; keep the release keystore outside the repository checkout."
        }
        properties.setProperty("storeFile", signingStoreFile.path)
        properties
    }

val baseVersionName = "0.1.0"
// Release outputs are "<prefix>.<versionCode>"; the prefix is baseVersionName without its patch.
val releaseVersionPrefix = baseVersionName.substringBeforeLast('.')

android {
    namespace = "com.kernel.ai"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = baseApplicationId
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = baseVersionName
        buildConfigField("String", "DEBUG_APPLICATION_ID", "\"$baseApplicationId.debug\"")
        buildConfigField("String", "PROBE_APPLICATION_ID", "\"$baseApplicationId.debug.inflectprobe\"")

        testInstrumentationRunner = "com.kernel.ai.debug.inflect.InflectSpikeAndroidJUnitRunner"

        buildConfigField("String", "HF_CLIENT_ID", "\"2607cec6-3d70-4df0-ba39-eb9cef1ba8c8\"")
        buildConfigField("String", "HF_REDIRECT_URI", "\"$baseApplicationId://oauth/callback\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")
        buildConfigField("boolean", "IS_INFLECT_PROBE", "false")
    }

    signingConfigs {
        create("debugSigning") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }

        if (releaseSigningProperties != null) {
            create("releaseSigning") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
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
            if (releaseSigningProperties != null) {
                signingConfig = signingConfigs.getByName("releaseSigning")
            }
        }

        debug {
            isMinifyEnabled = false
            applicationIdSuffix = if (isolatedInflectProbe) ".debug.inflectprobe" else ".debug"
            signingConfig = signingConfigs.getByName("debugSigning")
            buildConfigField("boolean", "IS_INFLECT_PROBE", isolatedInflectProbe.toString())
            buildConfigField("String", "HF_REDIRECT_URI", "\"$debugApplicationId://oauth/callback\"")
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
                // ExecuTorch's fbjni 0.7.0 (NDK r27b) and :core:memory (NDK r28c) both ship
                // libc++_shared.so. Merge order puts project modules first, so the newer r28c
                // runtime is kept; libc++ is backward compatible with older-NDK binaries.
                "lib/arm64-v8a/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
            excludes += setOf(
                "lib/arm64-v8a/libsherpa-onnx-c-api.so",
                "lib/armeabi-v7a/libsherpa-onnx-c-api.so",
                "lib/x86/libsherpa-onnx-c-api.so",
                "lib/x86_64/libsherpa-onnx-c-api.so",
                "lib/arm64-v8a/libsherpa-onnx-cxx-api.so",
                "lib/armeabi-v7a/libsherpa-onnx-cxx-api.so",
                "lib/x86/libsherpa-onnx-cxx-api.so",
                "lib/x86_64/libsherpa-onnx-cxx-api.so",
                // ExecuTorch ships only arm64-v8a and x86_64; fbjni's 32-bit copies would be
                // unreachable (no 32-bit library needs libfbjni or libc++_shared).
                "lib/armeabi-v7a/libfbjni.so",
                "lib/x86/libfbjni.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
            )
        }
    }
}
// The shared preprocessing exports have unresolved file-specific terms. Keep all three
// wake-word assets available to debug builds, but exclude them from release artifacts.
abstract class FilterReleaseAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun filterAssets() {
        val source = inputDirectory.get().asFile
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        source.walkTopDown()
            .filter { it.isFile }
            .filterNot {
                val path = it.relativeTo(source).invariantSeparatorsPath
                path == "models/wakeword" || path.startsWith("models/wakeword/")
            }
            .forEach { file ->
                val destination = output.resolve(file.relativeTo(source).path)
                destination.parentFile.mkdirs()
                file.copyTo(destination, overwrite = true)
            }
    }
}

val filterReleaseAssets = tasks.register<FilterReleaseAssetsTask>("filterReleaseAssets") {
    inputDirectory.set(layout.projectDirectory.dir("src/main/assets"))
    outputDirectory.set(layout.buildDirectory.dir("generated/filteredReleaseAssets"))
}
android.sourceSets.getByName("main").assets.directories.clear()

// Release builds (Play bundle and tester APK) report "<major.minor>.<versionCode>", with the
// prefix taken from defaultConfig.versionName. The mapping is lazy, so it picks up the code
// GPP's AUTO resolution assigns during a publish run.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addStaticSourceDirectory("src/main/assets")
    }
    onVariants(selector().withBuildType("release")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            filterReleaseAssets,
            FilterReleaseAssetsTask::outputDirectory,
        )
        variant.outputs.forEach { output ->
            output.versionName.set(output.versionCode.map { "$releaseVersionPrefix.$it" })
        }
    }
}
fun releaseApkArtifact(buildDirectory: File, isSigned: Boolean): File {
    val apkName = if (isSigned) "app-release.apk" else "app-release-unsigned.apk"
    return buildDirectory.resolve("outputs/apk/release").resolve(apkName)
}

tasks.register("verifyReleaseApkArtifactSelection") {
    doLast {
        val buildDirectory = layout.buildDirectory.get().asFile
        check(releaseApkArtifact(buildDirectory, isSigned = true).name == "app-release.apk")
        check(releaseApkArtifact(buildDirectory, isSigned = false).name == "app-release-unsigned.apk")
    }
}

tasks.register("verifyReleaseJniKeepRules") {
    group = "verification"
    description = "Fails if R8 renames or removes classes reached by release JNI libraries."
    dependsOn("minifyReleaseWithR8")

    doLast {
        val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile
        val usageFile = layout.buildDirectory.file("outputs/mapping/release/usage.txt").get().asFile
        check(mappingFile.isFile) { "Release R8 mapping file is missing: $mappingFile" }
        check(usageFile.isFile) { "Release R8 usage file is missing: $usageFile" }

        val jniPackages = listOf(
            "ai.onnxruntime.",
            "androidx.graphics.path.",
            "com.facebook.jni.",
            "com.google.ai.edge.",
            "com.k2fsa.sherpa.onnx.",
            "org.pytorch.executorch.",
            "org.tensorflow.lite.",
        )
        val requiredClasses = listOf(
            "ai.onnxruntime.OrtSession",
            "androidx.datastore.core.NativeSharedCounter",
            "androidx.graphics.path.ConicConverter",
            "androidx.graphics.path.PathIteratorPreApi34Impl",
            // fbjni's destructor is reached from ExecuTorch native code (pytorch/executorch#22174).
            "com.facebook.jni.HybridData\$Destructor",
            "com.google.ai.edge.litert.CompiledModel",
            "com.google.ai.edge.litert.Environment",
            "com.google.ai.edge.litert.LiteRtException",
            "com.google.ai.edge.litert.TensorBuffer",
            "com.google.ai.edge.litert.TensorBufferRequirements",
            "com.google.ai.edge.litert.TensorType",
            "com.google.ai.edge.litert.TensorType\$ElementType",
            "com.google.ai.edge.litert.TensorType\$Layout",
            "com.google.ai.edge.litertlm.LiteRtLmJni",
            "com.google.ai.edge.litertlm.LiteRtLmJniException",
            "com.google.ai.edge.litertlm.NativeLibraryLoader",
            // No Sherpa JNI runtime is packaged in release until attribution is resolved.
            // verifyReleaseAttributionMetadata checks this against the packaged native inventory.
            "org.pytorch.executorch.EValue",
            "org.pytorch.executorch.ExecutorchRuntimeException",
            "org.pytorch.executorch.Module",
            "org.pytorch.executorch.Tensor",
            "org.tensorflow.lite.InterpreterFactoryImpl",
            "org.tensorflow.lite.NativeInterpreterWrapper",
            "org.tensorflow.lite.NativeInterpreterWrapperExperimental",
            "org.tensorflow.lite.NativeSignatureRunnerWrapper",
            "org.tensorflow.lite.TensorFlowLite",
            "org.tensorflow.lite.TensorImpl",
            "com.kernel.ai.core.voice.InflectPhonemizer",
            "com.kernel.ai.core.memory.vector.VectorStoreJni",
            // LiteRT-LM reflects over ToolSet methods and parameter names (#1807).
            "com.kernel.ai.core.skills.KernelAIToolSet",
        )
        val exactJniClasses = setOf(
            "androidx.datastore.core.NativeSharedCounter",
            "com.kernel.ai.core.voice.InflectPhonemizer",
            "com.kernel.ai.core.memory.vector.VectorStoreJni",
            "com.kernel.ai.core.skills.KernelAIToolSet",
        )
        val guardedClass: (String) -> Boolean = { name ->
            (jniPackages.any(name::startsWith) || name in exactJniClasses) &&
                !name.contains("\$\$ExternalSynthetic")
        }
        val mappingLines = mappingFile.readLines()
        val mappingEntries = mappingLines.mapNotNull { line ->
            if (line.startsWith(" ") || !line.endsWith(":") || " -> " !in line) {
                null
            } else {
                line.removeSuffix(":").split(" -> ", limit = 2)
                    .let { (original, obfuscated) -> original to obfuscated }
            }
        }
        val mappedClasses = mappingEntries.map { (original, _) -> original }
        val renamedClasses = mappingEntries.filter { (original, obfuscated) ->
            guardedClass(original) && original != obfuscated
        }.map { (original, _) -> original }
        var currentClass: String? = null
        val renamedMembers = mappingLines.mapNotNull { line ->
            if (!line.startsWith(" ") && line.endsWith(":") && " -> " in line) {
                currentClass = line.removeSuffix(":").substringBefore(" -> ")
                null
            } else if (currentClass?.let(guardedClass) == true && " -> " in line) {
                val originalMember = line.substringBefore(" -> ")
                    .trim()
                    .substringBefore("(")
                    .substringAfterLast(":")
                    .substringAfterLast(" ")
                val generatedMember = originalMember.startsWith("\$r8\$") ||
                    originalMember.startsWith("-\$\$Nest\$")
                if (generatedMember || '.' in originalMember) {
                    null
                } else {
                    val obfuscatedMember = line.substringAfter(" -> ").substringBefore(" ")
                    (originalMember != obfuscatedMember).takeIf { it }?.let {
                        "$currentClass.$originalMember -> $obfuscatedMember"
                    }
                }
            } else {
                null
            }
        }
        val missingClasses = requiredClasses.filterNot(mappedClasses::contains)
        val mappedClassSet = mappedClasses.toSet()
        val usageLines = usageFile.readLines()
        var usageClass: String? = null
        val removedClasses = usageLines.mapNotNull { line ->
            if (!line.startsWith(" ") && line.endsWith(":")) {
                usageClass = line.removeSuffix(":")
                usageClass.takeIf { guardedClass(it) && it !in mappedClassSet }
            } else {
                null
            }
        }
        usageClass = null
        val removedMembers = usageLines.mapNotNull { line ->
            if (!line.startsWith(" ") && line.endsWith(":")) {
                usageClass = line.removeSuffix(":")
                null
            } else if (
                line.startsWith("    ") &&
                usageClass?.let(guardedClass) == true &&
                "<clinit>" !in line
            ) {
                "$usageClass.${line.trim()}"
            } else {
                null
            }
        }
        check(missingClasses.isEmpty()) {
            "Release mapping does not contain required JNI classes: ${missingClasses.joinToString()}"
        }
        check(renamedClasses.isEmpty()) {
            "R8 renamed JNI-reached classes: ${renamedClasses.joinToString()}"
        }
        check(renamedMembers.isEmpty()) {
            "R8 renamed JNI-reached members: ${renamedMembers.joinToString()}"
        }
        check(removedClasses.isEmpty()) {
            "R8 removed JNI-reached classes: ${removedClasses.joinToString()}"
        }
        check(removedMembers.isEmpty()) {
            "R8 removed JNI-reached members: ${removedMembers.joinToString()}"
        }
    }
}

fun locateAapt2(): File {
    val sdkDirectory = sequenceOf(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
    ).filterNotNull()
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?: rootProject.file("local.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("sdk.dir=")
            ?.let(::File)
        ?: error("Unable to locate the Android SDK")
    return sdkDirectory.resolve("build-tools")
        .listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { it.name }
        ?.resolve("aapt2")
        ?.takeIf { it.isFile }
        ?: error("Unable to locate aapt2")
}

/**
 * #1678: the packaged release APK must deny cleartext traffic. Checks the merged release manifest
 * attributes and the network security config actually packaged in the release APK.
 */
tasks.register("verifyReleaseNetworkSecurity") {
    dependsOn("assembleRelease", "verifyReleaseApkArtifactSelection")
    doLast {
        val aapt2 = locateAapt2()
        val apk = releaseApkArtifact(layout.buildDirectory.get().asFile, isSigned = releaseSigningProperties != null)
        check(apk.isFile) { "Missing release APK: ${apk.path}" }
        fun xmlTree(path: String): String = providers.exec {
            commandLine(aapt2.absolutePath, "dump", "xmltree", apk.absolutePath, "--file", path)
        }.standardOutput.asText.get()

        val manifest = xmlTree("AndroidManifest.xml")
        check(!Regex("""usesCleartextTraffic(?:\([^)]*\))?="?(true|0xffffffff)""").containsMatchIn(manifest)) {
            "Release manifest must not set usesCleartextTraffic=true"
        }
        check(Regex("""networkSecurityConfig\([^)]*\)=@""").containsMatchIn(manifest)) {
            "Release manifest must reference a network security config"
        }
        val resources = providers.exec {
            commandLine(aapt2.absolutePath, "dump", "resources", apk.absolutePath)
        }.standardOutput.asText.get()
        val configPaths = Regex("""resource \S+ xml/network_security_config\s+\(\) \(file\) (res/\S+) type=XML""")
            .findAll(resources).map { it.groupValues[1] }.toList()
        check(configPaths.isNotEmpty()) { "Packaged network_security_config.xml missing from ${apk.name}" }
        configPaths.forEach { path ->
            val config = xmlTree(path)
            // Compiled XML prints `A: cleartextTrafficPermitted=false`; tolerate the quoted form too.
            val permits = Regex("""cleartextTrafficPermitted(?:\([^)]*\))?="?([^"\s]+)""")
                .findAll(config).map { it.groupValues[1] }.toList()
            check(permits.isNotEmpty() && permits.all { it == "false" }) {
                "Release network security config $path must set cleartextTrafficPermitted=false everywhere (found $permits)"
            }
        }
    }
}

/**
 * Verifies literal variant-specific shortcut metadata in the packaged APKs.
 */
tasks.register("verifyListsShortcutVariants") {
    dependsOn("assembleDebug", "assembleRelease", "verifyReleaseApkArtifactSelection")
    doLast {
        val aapt2 = locateAapt2()

        fun packagedShortcut(artifact: File): String {
            check(artifact.isFile) { "Missing artifact: ${artifact.path}" }
            val resources = providers.exec {
                commandLine(aapt2.absolutePath, "dump", "resources", artifact.absolutePath)
            }.standardOutput.asText.get()
            val packagedPath = Regex(
                """resource \S+ xml/shortcuts\s+\(\) \(file\) (res/\S+) type=XML""",
            ).find(resources)?.groupValues?.get(1)
                ?: error("Packaged shortcuts.xml missing from ${artifact.path}")
            return providers.exec {
                commandLine(aapt2.absolutePath, "dump", "xmltree", artifact.absolutePath, "--file", packagedPath)
            }.standardOutput.asText.get()
        }

        fun verify(artifact: File, expectedTarget: String) {
            val xml = packagedShortcut(artifact)
            val target = xml.lineSequence().firstOrNull { "targetPackage(" in it }
                ?: error("Packaged targetPackage missing from ${artifact.path}")
            check("""="$expectedTarget" (Raw: "$expectedTarget")""" in target && "=@" !in target) {
                "Packaged targetPackage must be literal $expectedTarget in ${artifact.path}"
            }
            check(Regex("""shortcutId\([^)]*\)="lists" \(Raw: "lists"\)""").containsMatchIn(xml))
            check(Regex("""targetClass\([^)]*\)="com\.kernel\.ai\.MainActivity" """).containsMatchIn(xml))
            check(Regex("""name\([^)]*\)="navigation_route" """).containsMatchIn(xml))
            check(Regex("""value\([^)]*\)="lists" """).containsMatchIn(xml))
        }

        verify(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile, debugApplicationId)
        verify(
            releaseApkArtifact(
                layout.buildDirectory.get().asFile,
                isSigned = releaseSigningProperties != null,
            ),
            baseApplicationId,
        )
    }
}

tasks.register("verifyAcousticStimulusReleaseIsolation") {
    dependsOn("assembleDebug", "assembleRelease", "bundleRelease", "verifyReleaseApkArtifactSelection")
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
        check("package=\"$debugApplicationId\"" in debugManifest) {
            "Debug manifest package must be $debugApplicationId"
        }
        check("com.kernel.ai.debug.acoustic.AcousticStimulusReceiver" in debugManifest)
        check("com.kernel.ai.debug.action.PLAY_ACOUSTIC_STIMULUS" in debugManifest)
        check("com.kernel.ai.debug.acoustic.AcousticStimulusReceiver" !in releaseManifest)
        check("com.kernel.ai.debug.action.PLAY_ACOUSTIC_STIMULUS" !in releaseManifest)
        check("package=\"$baseApplicationId\"" in releaseManifest) {
            "Release manifest package must be $baseApplicationId"
        }

        val artifacts = listOf(
            buildDirectory.resolve("outputs/apk/debug/app-debug.apk"),
            releaseApkArtifact(buildDirectory, isSigned = releaseSigningProperties != null),
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
    dependsOn("assembleDebug", "assembleRelease", "bundleRelease", "verifyReleaseApkArtifactSelection")
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
            "$debugApplicationId.target-event-journal",
        )
        journalComponents.forEach { component ->
            check(component in debugManifest) { "Debug manifest must contain $component" }
            check(component !in releaseManifest) { "Release manifest must NOT contain $component" }
        }
        journalBroadcastActions.forEach { action ->
            check(action in debugManifest) { "Debug manifest must contain $action" }
            check(action !in releaseManifest) { "Release manifest must NOT contain $action" }
        }
        check("package=\"$baseApplicationId\"" in releaseManifest)

        val artifacts = listOf(
            buildDirectory.resolve("outputs/apk/debug/app-debug.apk"),
            releaseApkArtifact(buildDirectory, isSigned = releaseSigningProperties != null),
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
    implementation(project(":core:executorch"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:widget"))
    implementation(project(":feature:convert"))

    // Keep the Inflect/Piper/eSpeak runtime out of release until its distribution obligations are met.
    val inflectSherpaAar =
        rootProject.file("third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort-inflect.aar")
    debugImplementation(files(inflectSherpaAar.absolutePath))

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
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.litert)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    // Keep LeakCanary's debug-only providers/watchers out of the isolated probe package.
    if (!isolatedInflectProbe) {
        debugImplementation(libs.leakcanary)
    }

    implementation(libs.appauth)
    implementation(libs.security.crypto)
    implementation(libs.play.services.location)

    testImplementation(libs.junit.jupiter)
    testImplementation("org.json:json:20240303")
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}

aboutLibraries {
    offlineMode = false
    collect {
        configPath = file("src/main")
        filterVariants.add("release")
    }
    export {
        variant = "release"
        prettyPrint = true
    }
    license {
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        // These are observed license identifiers, not legal distribution approvals.
        // Adding any new identifier requires review; unresolved licenses remain a hard build failure.
        allowedLicenses.addAll("ASDKL", "Apache-2.0", "BSD-3-Clause", "MIT", "PCSDKToS")
    }
}

// AboutLibraries adds the generated release JSON as a resource directory. Keep its export
// dependency explicit for AGP 9 task validation.
tasks.configureEach {
    if (name == "mergeReleaseResources" || name == "packageReleaseResources") {
        dependsOn("exportLibraryDefinitionsRelease")
    }
}

tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs = listOf("releaseRuntimeClasspath")
    testConfigs = emptyList()
    jsonOutput = layout.buildDirectory.file("reports/sbom/release-runtime-bom.json")
    xmlOutput = layout.buildDirectory.file("reports/sbom/release-runtime-bom.xml")
}

fun attributionSha256(stream: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = stream.read(buffer)
        if (read == -1) break
        digest.update(buffer, 0, read)
    }
    return HexFormat.of().formatHex(digest.digest())
}

tasks.register("verifyReleaseAttributionMetadata") {
    group = "verification"
    description = "Checks resolved release licences, local binaries, packaged native libraries and bundled wake-word assets."
    dependsOn("exportLibraryDefinitionsRelease", "cyclonedxDirectBom", "bundleRelease")

    val aboutLibrariesFile = layout.buildDirectory.file(
        "generated/aboutLibraries/release/res/raw/aboutlibraries.json",
    )
    val metadataFile = layout.projectDirectory.file("release-attribution-metadata.json")
    val runtimeConfiguration = configurations.getByName("releaseRuntimeClasspath")
    val runtimeFiles = runtimeConfiguration.allDependencies
        .withType(org.gradle.api.artifacts.FileCollectionDependency::class.java)
    val bundleFile = layout.buildDirectory.file("outputs/bundle/release/app-release.aab")
    val reportFile = layout.buildDirectory.file("reports/release-audit/attribution-verification.json")
    inputs.files(aboutLibrariesFile, metadataFile, bundleFile)
    inputs.files(provider { runtimeFiles.flatMap { it.files.files } })
    inputs.dir(layout.projectDirectory.dir("src/main/assets/legal"))
    outputs.file(reportFile)

    doLast {
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        val errors = mutableListOf<String>()
        val aboutLibraries = JsonSlurper().parse(aboutLibrariesFile.get().asFile) as Map<*, *>
        val libraryRows = aboutLibraries["libraries"] as List<*>
        val libraryById = libraryRows.associate { row ->
            val library = row as Map<*, *>
            library["uniqueId"].toString() to library
        }
        val moduleIds = runtimeConfiguration.incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                (component.id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}" }
            }
            .toSet()
        val missingModuleLicences = moduleIds.filter { componentId ->
            val platformBaseId = componentId.removeSuffix("-android").removeSuffix("-jvm")
            val equivalentLibraries = libraryById.filterKeys { libraryId ->
                libraryId == componentId ||
                    libraryId == platformBaseId ||
                    libraryId == "$platformBaseId-android" ||
                    libraryId == "$platformBaseId-jvm"
            }.values
            equivalentLibraries.all { library ->
                val licences = (library as Map<*, *>)["licenses"] as? List<*>
                licences.isNullOrEmpty()
            }
        }.sorted()
        if (missingModuleLicences.isNotEmpty()) {
            errors += "Resolved release modules with missing licence metadata: ${missingModuleLicences.joinToString()}"
        }

        val repositoryRoot = rootProject.projectDir.canonicalFile.toPath()
        val fileDependencies = runtimeFiles.flatMap { it.files.files }
            .associate { file ->
                val canonical = file.canonicalFile.toPath()
                val relative = if (canonical.startsWith(repositoryRoot)) {
                    repositoryRoot.relativize(canonical).toString().replace(File.separatorChar, '/')
                } else {
                    canonical.fileName.toString()
                }
                "file:$relative" to file
            }
        val metadata = JsonSlurper().parse(metadataFile.asFile) as Map<*, *>
        val localRows = metadata["local_dependencies"] as? List<*> ?: emptyList<Any>()
        val localById = localRows.associate { row ->
            val local = row as Map<*, *>
            local["component_id"].toString() to local
        }
        val missingLocalRows = fileDependencies.keys - localById.keys
        val staleLocalRows = localById.keys - fileDependencies.keys
        if (missingLocalRows.isNotEmpty()) errors += "Local runtime dependencies missing explicit metadata: ${missingLocalRows.sorted().joinToString()}"
        if (staleLocalRows.isNotEmpty()) errors += "Local metadata does not match release runtime dependencies: ${staleLocalRows.sorted().joinToString()}"
        localById.forEach { (id, row) ->
            val local = row as Map<*, *>
            if (local["license_expression"]?.toString().isNullOrBlank() || local["license_status"] != "reviewed") {
                errors += "$id has no reviewed licence expression; owner decision required."
            }
            val path = local["artifact_path"]?.toString()
            val artifact = fileDependencies[id]
            if (artifact == null || path.isNullOrBlank() || rootProject.file(path).canonicalFile != artifact.canonicalFile || !artifact.isFile) {
                errors += "$id does not identify its resolved artifact file."
            } else {
                if (artifact.inputStream().use(::attributionSha256) != local["artifact_sha256"]) {
                    errors += "$id does not match its reviewed SHA-256."
                }
            }
            if (local["source_component"]?.toString().isNullOrBlank()) errors += "$id has no source component mapping."
            val noticePath = local["required_notice"] as? String
            val noticePrefix = "app/src/main/assets/"
            if (noticePath == null || !noticePath.startsWith("${noticePrefix}legal/") ||
                !rootProject.file(noticePath).isFile
            ) {
                errors += "$id is missing its required notice file."
            } else {
                ZipFile(bundleFile.get().asFile).use { zip ->
                    val entry = zip.getEntry("base/assets/${noticePath.removePrefix(noticePrefix)}")
                    if (entry == null || !zip.getInputStream(entry).use { it.readBytes().contentEquals(rootProject.file(noticePath).readBytes()) }) {
                        errors += "$id is missing its exact required notice in the release bundle."
                    }
                }
            }
        }

        val nativeRows = metadata["native_libraries"] as? List<*> ?: emptyList<Any>()
        val nativeByName = nativeRows.associate { row ->
            val native = row as Map<*, *>
            native["name"].toString() to native
        }
        val bundle = bundleFile.get().asFile
        val packagedNativeNames = ZipFile(bundle).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.matches(Regex("[^/]+/lib/[^/]+/[^/]+\\.so")) }
                .map { it.name.substringAfterLast('/') }
                .toSet()
        }
        if (packagedNativeNames != nativeByName.keys) {
            errors += "Packaged native library metadata mismatch; missing=${(packagedNativeNames - nativeByName.keys).sorted()}, stale=${(nativeByName.keys - packagedNativeNames).sorted()}."
        }
        nativeByName.forEach { (name, row) ->
            val native = row as Map<*, *>
            if (native["license_expression"]?.toString().isNullOrBlank() || native["license_status"] != "reviewed") {
                errors += "$name has no reviewed licence expression; owner decision required."
            }
            val sourceComponent = native["source_component"]?.toString()
            if (sourceComponent.isNullOrBlank()) errors += "$name has no source component mapping."
        }

        val wakeAssets = metadata["wake_word_assets"] as List<*>
        val wakeByName = wakeAssets.associate { row ->
            val asset = row as Map<*, *>
            asset["name"].toString() to asset
        }
        val wakePrefix = "base/assets/models/wakeword/"
        ZipFile(bundle).use { zip ->
            val noticeAssets = metadata["native_notice_assets"] as Map<*, *>
            check(noticeAssets.isNotEmpty()) { "No reviewed native notice assets declared." }
            noticeAssets.forEach { (filename, expectedSha256) ->
                val entry = zip.getEntry("base/assets/legal/$filename")
                if (entry == null || zip.getInputStream(entry).use(::attributionSha256) != expectedSha256) {
                    errors += "$filename is missing or differs from the reviewed native notice."
                }
            }
            val packagedWakeAssets = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(wakePrefix) }
                .associateBy { it.name.removePrefix(wakePrefix) }
            if (packagedWakeAssets.keys != wakeByName.keys) {
                errors += "Packaged wake-word asset metadata mismatch; missing=${(packagedWakeAssets.keys - wakeByName.keys).sorted()}, stale=${(wakeByName.keys - packagedWakeAssets.keys).sorted()}."
            }
            wakeByName.forEach { (name, row) ->
                if (row["license_status"] != "reviewed" || (row["license_expression"] as? String).isNullOrBlank()) {
                    errors += "$name has no reviewed licence expression for the bundled wake-word asset."
                }
                if ((row["source_component"] as? String).isNullOrBlank()) errors += "$name has no source mapping."
                val entry = packagedWakeAssets[name] ?: return@forEach
                if (zip.getInputStream(entry).use(::attributionSha256) != row["artifact_sha256"]) {
                    errors += "$name does not match its reviewed SHA-256."
                }
            }
        }

        val result = mapOf(
            "format" to "jandal-release-attribution-verification",
            "schema_version" to 1,
            "configuration" to "releaseRuntimeClasspath",
            "resolved_module_count" to moduleIds.size,
            "module_count_with_licence_metadata" to moduleIds.size - missingModuleLicences.size,
            "local_dependency_count" to fileDependencies.size,
            "packaged_native_library_count" to packagedNativeNames.size,
            "bundled_wake_word_asset_count" to wakeByName.size,
            "status" to if (errors.isEmpty()) "verified" else "owner_review_required",
            "errors" to errors,
        )
        report.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result)) + "\n")
        check(errors.isEmpty()) {
            "Release attribution verification failed. See ${report.relativeTo(rootProject.projectDir)}:\n- ${errors.joinToString("\n- ")}"
        }
    }
}
