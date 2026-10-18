pluginManagement {
    includeBuild("build-logic")
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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Cinexperience"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:model")
include(":core:data")
include(":core:ui")
include(":core:testing")
include(":core:seatmapui")
include(":core:program")
include(":core:ticket")
include(":feature:cinemas")
include(":feature:seatmap")
include(":feature:visits")
include(":feature:rankings")
include(":feature:backup")
include(":feature:program")
include(":feature:tickets")
