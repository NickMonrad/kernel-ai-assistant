plugins {
    alias(libs.plugins.android.library)
}

val openClLinkLibrary = providers.gradleProperty("openclSystemLibrary").orNull.orEmpty()
android {
    namespace = "com.kernel.ai.debug.llamaprobe"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DLLAMA_SUBPROCESS=OFF",
                    "-DLLAMA_LLGUIDANCE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_NATIVE=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        create("llamaProbeOpencl") {
            matchingFallbacks += listOf("debug")
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DGGML_OPENCL=ON",
                        "-DOPENCL_ANDROID_LIBRARY=$openClLinkLibrary",
                    )
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        if (variant.buildType !in setOf("debug", "llamaProbeOpencl")) variant.enable = false
    }
}

dependencies {
    implementation(project(":core:inference"))
    implementation(project(":core:skills"))
    implementation(project(":core:memory"))
    implementation(libs.coroutines.android)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation("org.json:json:20240303")
}
