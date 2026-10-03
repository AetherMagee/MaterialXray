pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Material Xray"
include(":app")
include(":core:model")
include(":core:common")
include(":core:xray")
include(":core:root")
include(":core:network")
include(":core:connection")
include(":core:android")
include(":core:database")
include(":core:data")
include(":core:telemetry")
include(":core:runtime")
include(":core:ui")
include(":core:navigation")
include(":feature:home")
include(":feature:routing")
include(":feature:logs")
include(":feature:settings")
include(":feature:configviewer")
