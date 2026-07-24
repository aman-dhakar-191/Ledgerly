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

rootProject.name = "Ledgerly"

include(":app")
include(":core:model")
include(":core:database")
include(":core:crypto")
include(":core:crypto-engine")
include(":core:parser")
include(":core:update")
