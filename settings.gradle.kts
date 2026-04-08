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
}

rootProject.name = "KernelAI"

include(":app")
include(":core:inference")
include(":core:memory")
include(":core:wasm")
include(":core:ui")
include(":core:skills")
include(":feature:chat")
include(":feature:settings")
include(":feature:onboarding")
