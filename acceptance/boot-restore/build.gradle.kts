import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateBootRestoreAndroidTestVersionManifest : DefaultTask() {
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
    .map(String::toInt)
    .orElse(1)
    .get()
check(candidateVersionCode > 0) { "versionCode must be positive." }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.kernel.ai.bootrestore"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kernel.ai.debug.bootrestore"
        testApplicationId = "com.kernel.ai.debug.bootrestore.test"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = candidateVersionCode
        versionName = "0.1.0-boot-restore-test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        debug {
            signingConfig = signingConfigs.getByName("debugSigning")
        }
    }

}

androidComponents {
    onVariants { variant ->
        if (variant.name == "debug") {
            variant.androidTest?.let { androidTest ->
                val manifestTask = tasks.register<GenerateBootRestoreAndroidTestVersionManifest>(
                    "generateBootRestoreAndroidTestVersionManifest",
                ) {
                    versionCode.set(candidateVersionCode)
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

dependencies {
    implementation(project(":core:memory"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.coroutines.android)
    androidTestImplementation(libs.work.runtime.ktx)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation(libs.room.runtime)
    kspAndroidTest(libs.hilt.compiler)
}
