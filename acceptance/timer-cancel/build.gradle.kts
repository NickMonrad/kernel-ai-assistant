plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.kernel.ai.timeracceptance"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kernel.ai.debug.timeracceptance"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.0-timeracceptance"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":core:skills"))
    implementation(project(":core:memory"))
    implementation(project(":core:inference"))
    implementation(project(":core:permissions"))
    implementation(libs.androidx.startup)

    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.work.runtime.ktx)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("io.mockk:mockk-android:${libs.versions.mockk.get()}") {
        exclude(group = "org.junit.jupiter", module = "junit-jupiter")
    }
}
