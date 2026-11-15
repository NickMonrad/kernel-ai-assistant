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
include(":acceptance:boot-restore")
include(":acceptance:timer-cancel")
include(":core:inference")
include(":core:memory")
include(":core:voice")
include(":core:wasm")
include(":core:ui")
include(":core:model-availability")
include(":core:skills")
include(":core:permissions")
include(":feature:chat")
include(":feature:convert")
include(":feature:settings")
include(":feature:widget")
