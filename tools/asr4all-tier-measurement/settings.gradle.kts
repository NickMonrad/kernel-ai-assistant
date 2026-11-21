// Standalone, benchmark-only Android project (asr4all ExecuTorch per-device-tier measurement, #1977/#2001 F3-F4).
// Not part of the KernelAI build: it never touches the production app, its package or its data.
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

rootProject.name = "asr4all-tier-measurement"
include(":probe")
