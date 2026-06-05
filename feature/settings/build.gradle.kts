plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.kernel.ai.feature.settings"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:inference"))
    implementation(project(":core:memory"))
    implementation(project(":core:voice"))
    implementation(project(":core:skills"))
    implementation(project(":core:model-availability"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.compose.ui.tooling)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation(libs.uiautomator)
    implementation(libs.coil.compose)

    implementation(libs.datastore.preferences)
    implementation(libs.browser)

    // Drag-and-drop reorder for LazyColumn (#897)
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
