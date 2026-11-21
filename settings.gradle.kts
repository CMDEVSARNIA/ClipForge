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

rootProject.name = "ClipForge"
include(":core")
include(":desktop")
// CI's Windows job builds only the desktop app: -Pclipforge.desktopOnly=true skips the Android modules
// (no Android SDK or NDK needed there).
if (providers.gradleProperty("clipforge.desktopOnly").orNull != "true") {
    include(":app")
    include(":whisperfast")
}
