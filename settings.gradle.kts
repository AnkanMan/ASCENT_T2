pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ASCENT"
include(":app")
include(":core:engine")
include(":core:designsystem")
include(":core:data")
include(":integrations:bridge")
include(":feature:status")
include(":feature:awakening")
include(":feature:quests")
include(":feature:console")
include(":feature:gates")
include(":feature:inventory")
include(":feature:codex")
include(":feature:skills")
 