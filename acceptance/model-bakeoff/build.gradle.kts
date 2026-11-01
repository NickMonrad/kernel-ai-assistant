plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.kernel.ai.modelbakeoff"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kernel.ai.debug.modelbakeoff"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "debug-model-bakeoff"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "debug"
    }
}

dependencies {
    implementation(project(":core:inference"))
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.litertlm.android)
    androidTestImplementation(libs.coroutines.android)
}
