// Standalone, benchmark-only Android project for issue #1988 (ExecuTorch INT8 vs ONNX FP32 asr4all).
// It is not part of the KernelAI build: it never touches the production app, its package or its data.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "spike-1988-executorch"
include(":probe")
