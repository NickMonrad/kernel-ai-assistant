import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateResponseFormatAndroidTestVersionManifest : DefaultTask() {
    @get:Input
    abstract val versionCode: Property<Int>

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        manifestFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools" android:versionCode="${versionCode.get()}" tools:replace="android:versionCode" />""",
            )
        }
    }
}

val candidateVersionCode = providers.gradleProperty("versionCode")
    .map { value ->
        value.toLongOrNull()
            ?: error("versionCode must be an integer between 1 and 2100000000.")
    }
    .orElse(1L)
    .get()
check(candidateVersionCode in 1L..2_100_000_000L) {
    "versionCode must be between 1 and Android's supported maximum of 2100000000."
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.kernel.ai"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kernel.ai.debug"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = candidateVersionCode.toInt()
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("probeDebug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "debug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("probeDebug")
        }
    }

    buildFeatures {
        buildConfig = true
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

androidComponents {
    onVariants { variant ->
        if (variant.name == "debug") {
            variant.androidTest?.let { androidTest ->
                val manifestTask = tasks.register<GenerateResponseFormatAndroidTestVersionManifest>(
                    "generateResponseFormatAndroidTestVersionManifest",
                ) {
                    versionCode.set(candidateVersionCode.toInt())
                    manifestFile.set(
                        layout.buildDirectory.file(
                            "generated/androidTestManifest/${variant.name}/AndroidManifest.xml",
                        ),
                    )
                }
                androidTest.sources.manifests.addGeneratedManifestFile(manifestTask) {
                    it.manifestFile
                }
            }
        }
    }
}

tasks.register("verifyIsolation") {
    group = "verification"
    description = "Checks the isolated response-format acceptance manifest fails closed."
    dependsOn("processDebugMainManifest")
    doLast {
        val manifestFile = layout.buildDirectory.get().asFile.resolve(
            "intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
        )
        check(manifestFile.isFile) { "Missing acceptance manifest: ${manifestFile.path}" }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val manifest = factory.newDocumentBuilder().parse(manifestFile).documentElement
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val application = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        check(manifest.getAttribute("package") == "com.kernel.ai.debug") {
            "Acceptance app must preserve the debug package-private model path."
        }
        check(application.getAttributeNS(androidNamespace, "name").isBlank()) {
            "Acceptance app must use the no-op platform Application."
        }
        listOf("provider", "receiver", "activity", "activity-alias").forEach { component ->
            check(application.getElementsByTagName(component).length == 0) {
                "Acceptance manifest must not register $component components."
            }
        }
        val services = application.getElementsByTagName("service")
        val serviceNames = (0 until services.length).map {
            (services.item(it) as org.w3c.dom.Element).getAttributeNS(androidNamespace, "name")
        }.toSet()
        check(serviceNames == setOf(
            "com.kernel.ai.core.inference.InferenceLoadingService",
            "com.kernel.ai.core.inference.InferenceGenerationService",
        )) { "Acceptance app may retain only inference foreground services; found $serviceNames." }
        val permissions = manifest.getElementsByTagName("uses-permission")
        val permissionNames = (0 until permissions.length).map {
            (permissions.item(it) as org.w3c.dom.Element).getAttributeNS(androidNamespace, "name")
        }
        val forbiddenPermissions = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        )
        check(permissionNames.none { it in forbiddenPermissions }) {
            "Acceptance app must not retain network, boot, or data-sync permissions."
        }
    }
}

dependencies {
    implementation(project(":core:inference"))

    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.litertlm.android)
    androidTestImplementation(libs.coroutines.android)
}
