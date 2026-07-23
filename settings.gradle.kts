pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
        }
    }
}

rootProject.name = "CraftRelay"

include(
    "craftrelay-api",
    "craftrelay-common",
    "craftrelay-transport-redis",
    "craftrelay-platform-paper",
    "craftrelay-platform-velocity",
    "craftrelay-example-plugin",
    "craftrelay-integration-tests",
)
