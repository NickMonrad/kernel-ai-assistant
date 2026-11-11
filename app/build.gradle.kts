import java.io.File
import java.time.Instant
import java.util.Properties
import java.util.zip.ZipFile
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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

val verifyReleaseBundleForPlayUpload = tasks.register("verifyReleaseBundleForPlayUpload") {
    group = "verification"
    description = "Verifies the release AAB before a Play upload."
    dependsOn("bundleRelease")
    doLast {
        val bundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile
        check(bundle.isFile) { "Missing release bundle: ${bundle.path}" }
        val verifier = rootProject.file("scripts/release/verify_release_bundle.sh")
        providers.exec {
            commandLine("bash", verifier.absolutePath, bundle.absolutePath)
        }.result.get().assertNormalExitValue()
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

android {
    namespace = "com.kernel.ai"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = baseApplicationId
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.0"
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
            )
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
            "com.google.ai.edge.",
            "com.k2fsa.sherpa.onnx.",
            "com.sun.jna.",
            "org.tensorflow.lite.",
            "org.vosk.",
        )
        val requiredClasses = listOf(
            "ai.onnxruntime.OrtSession",
            "androidx.datastore.core.NativeSharedCounter",
            "androidx.graphics.path.ConicConverter",
            "androidx.graphics.path.PathIteratorPreApi34Impl",
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
            "com.k2fsa.sherpa.onnx.OfflineTts",
            "com.sun.jna.Native",
            "org.tensorflow.lite.InterpreterFactoryImpl",
            "org.tensorflow.lite.NativeInterpreterWrapper",
            "org.tensorflow.lite.NativeInterpreterWrapperExperimental",
            "org.tensorflow.lite.NativeSignatureRunnerWrapper",
            "org.tensorflow.lite.TensorFlowLite",
            "org.tensorflow.lite.TensorImpl",
            "org.vosk.Model",
            "org.vosk.Recognizer",
            "com.kernel.ai.core.voice.InflectPhonemizer",
            "com.kernel.ai.core.memory.vector.VectorStoreJni",
        )
        val exactJniClasses = setOf(
            "androidx.datastore.core.NativeSharedCounter",
            "com.kernel.ai.core.voice.InflectPhonemizer",
            "com.kernel.ai.core.memory.vector.VectorStoreJni",
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

/**
 * Writes the resolved release dependency graph as review inputs.
 *
 * This inventory is not a CycloneDX/SPDX SBOM or a licence determination. Licence and notice
 * metadata are deliberately reported as unknown until reviewed source metadata is supplied.
 */
tasks.register("generateReleaseDependencyInventory") {
    group = "verification"
    description = "Writes a release dependency inventory and explicit licence/notice metadata gaps."

    val reportDirectory = layout.buildDirectory.dir("reports/release-audit")
    val inventoryFile = reportDirectory.map { it.file("release-runtime-dependency-inventory.json") }
    val metadataGapsFile = reportDirectory.map { it.file("release-runtime-metadata-gaps.json") }
    outputs.files(inventoryFile, metadataGapsFile)
    // The resolved dependency graph and source commit are read during execution, not modeled as task inputs.
    outputs.upToDateWhen { false }

    doLast {
        val configuration = configurations.getByName("releaseRuntimeClasspath")
        val repositoryRoot = rootProject.projectDir.canonicalFile.toPath()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val components = configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
            when (val id = component.id) {
                is ModuleComponentIdentifier -> mapOf(
                    "component_id" to "${id.group}:${id.module}:${id.version}",
                    "component_type" to "module",
                    "artifact_file" to null,
                    "artifact_sha256" to null,
                    "artifact_hash_status" to "not_resolved_by_dependency_graph",
                    "license_expression" to null,
                    "license_status" to "unknown_not_collected",
                    "copyright_status" to "unknown_not_collected",
                    "notice_text_status" to "unknown_not_collected",
                    "source_url_status" to "unknown_not_collected",
                )
                is ProjectComponentIdentifier -> if (id.projectPath == project.path) {
                    null
                } else {
                    mapOf(
                        "component_id" to "project:${id.projectPath}",
                        "component_type" to "project",
                        "artifact_file" to null,
                        "artifact_sha256" to null,
                        "artifact_hash_status" to "not_resolved_by_dependency_graph",
                        "license_expression" to null,
                        "license_status" to "unknown_not_collected",
                        "copyright_status" to "unknown_not_collected",
                        "notice_text_status" to "unknown_not_collected",
                        "source_url_status" to "unknown_not_collected",
                    )
                }
                else -> null
            }
        }
        val fileDependencies = configuration.allDependencies
            .withType(org.gradle.api.artifacts.FileCollectionDependency::class.java)
            .flatMap { it.files.files }
            .filter { it.isFile }
            .map { file ->
                val canonicalFile = file.canonicalFile
                val path = canonicalFile.toPath()
                    .takeIf { it.startsWith(repositoryRoot) }
                    ?.let { repositoryRoot.relativize(it).toString().replace(File.separatorChar, '/') }
                    ?: canonicalFile.name
                mapOf(
                    "component_id" to "file:$path",
                    "component_type" to "file",
                    "artifact_file" to path,
                    "artifact_sha256" to sha256(canonicalFile),
                    "artifact_hash_status" to "computed",
                    "license_expression" to null,
                    "license_status" to "unknown_not_collected",
                    "copyright_status" to "unknown_not_collected",
                    "notice_text_status" to "unknown_not_collected",
                    "source_url_status" to "unknown_not_collected",
                )
            }
        val entries = (components + fileDependencies).distinctBy { it.getValue("component_id") }
            .sortedBy { it.getValue("component_id").toString() }
        check(entries.isNotEmpty()) { "No dependencies resolved for releaseRuntimeClasspath." }

        val commit = providers.exec {
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.get().trim()
        val metadataGaps = entries.map { entry ->
            mapOf(
                "component_id" to entry.getValue("component_id"),
                "status" to "unknown_not_collected",
                "missing_metadata" to listOf(
                    "license_expression",
                    "copyright_attribution",
                    "required_notice_text",
                    "source_url",
                ),
            )
        }
        val inventory = mapOf(
            "format" to "jandal-release-runtime-dependency-inventory",
            "schema_version" to 1,
            "is_formal_sbom" to false,
            "sbom_note" to "Resolved dependency inventory only; not a CycloneDX/SPDX SBOM or licence determination.",
            "source_commit" to commit,
            "version_name" to android.defaultConfig.versionName,
            "version_code" to android.defaultConfig.versionCode,
            "configuration" to "releaseRuntimeClasspath",
            "component_count" to entries.size,
            "file_dependency_count" to fileDependencies.size,
            "notice_input_status" to "requires_reviewed_licence_and_notice_metadata",
            "components" to entries,
        )
        val metadataReport = mapOf(
            "format" to "jandal-release-runtime-metadata-gaps",
            "schema_version" to 1,
            "source_commit" to commit,
            "configuration" to "releaseRuntimeClasspath",
            "status_note" to "unknown_not_collected means this task does not ingest reviewed licence metadata; it does not assert upstream metadata is absent.",
            "component_count" to metadataGaps.size,
            "gaps" to metadataGaps,
        )
        val inventoryOutput = inventoryFile.get().asFile
        val gapsOutput = metadataGapsFile.get().asFile
        inventoryOutput.parentFile.mkdirs()
        inventoryOutput.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(inventory)) + "\n")
        gapsOutput.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(metadataReport)) + "\n")
    }
}

tasks.register("verifyReleaseDependencyInventory") {
    group = "verification"
    description = "Checks release inventory completeness and fail-closed metadata statuses."
    dependsOn("generateReleaseDependencyInventory")

    doLast {
        val reportDirectory = layout.buildDirectory.dir("reports/release-audit").get().asFile
        val inventory = JsonSlurper().parse(
            reportDirectory.resolve("release-runtime-dependency-inventory.json"),
        ) as Map<*, *>
        val metadataReport = JsonSlurper().parse(
            reportDirectory.resolve("release-runtime-metadata-gaps.json"),
        ) as Map<*, *>
        val components = inventory["components"] as List<*>
        val gaps = metadataReport["gaps"] as List<*>
        val componentIds = components.map { (it as Map<*, *>)["component_id"].toString() }
        val gapIds = gaps.map { (it as Map<*, *>)["component_id"].toString() }

        check(inventory["configuration"] == "releaseRuntimeClasspath")
        check(inventory["is_formal_sbom"] == false)
        check(inventory["source_commit"].toString().matches(Regex("[0-9a-f]{40}")))
        check(inventory["version_name"].toString().isNotBlank())
        check((inventory["version_code"] as Number).toInt() > 0)
        check(components.isNotEmpty() && componentIds == componentIds.distinct().sorted())
        check("project:${project.path}" !in componentIds)
        check(inventory["component_count"] == components.size)
        check(inventory["file_dependency_count"] == components.count {
            (it as Map<*, *>)["component_type"] == "file"
        })
        check((inventory["file_dependency_count"] as Number).toInt() > 0)
        check(componentIds == gapIds && metadataReport["component_count"] == gaps.size)
        components.forEach { value ->
            val component = value as Map<*, *>
            check(component["license_expression"] == null)
            check(component["license_status"] == "unknown_not_collected")
            check(component["copyright_status"] == "unknown_not_collected")
            check(component["notice_text_status"] == "unknown_not_collected")
            check(component["source_url_status"] == "unknown_not_collected")
            if (component["component_type"] == "file") {
                check(component["artifact_hash_status"] == "computed")
                check(component["artifact_sha256"].toString().matches(Regex("[0-9a-f]{64}")))
            }
        }
        gaps.forEach { value ->
            val gap = value as Map<*, *>
            check(gap["status"] == "unknown_not_collected")
            check(gap["missing_metadata"] == listOf(
                "license_expression",
                "copyright_attribution",
                "required_notice_text",
                "source_url",
            ))
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

    // Release and debug use the same pinned Inflect-enabled Sherpa runtime. The AAR is the
    // existing v1.13.0 rebuild from scripts/sherpa-onnx-v1.13.0-inflect-build.md; no native
    // binary is rebuilt or duplicated by this release-exposure change.
    val inflectSherpaAar =
        rootProject.file("third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort-inflect.aar")
    releaseImplementation(files(inflectSherpaAar.absolutePath))
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
