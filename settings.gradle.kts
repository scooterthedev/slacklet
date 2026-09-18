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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "slack-wear"

include(":app")
include(":relay")
include(":core:model")
include(":core:designsystem")
include(":core:network")
include(":core:database")
include(":core:data")
include(":core:auth")
include(":feature:home")
include(":feature:conversation")
include(":feature:notifications")
include(":feature:search")
include(":feature:settings")
include(":feature:signin")
