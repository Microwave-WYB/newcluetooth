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

rootProject.name = "Cluetooth Scanner"
include(":app")

// v2 modules (docs/v2-api.md §6.0). Each lane owns its module's build file and sources.
include(
    ":core-api", ":ui-contract", ":platform-api", ":designsystem",
    ":core-fake", ":platform-fake", ":core-bridge", ":platform",
    ":chrome", ":sheets", ":map", ":gallery",
)
