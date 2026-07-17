// Settings file for the AnimeKai extension project.
// Includes the local extension module located at src/en/animekai.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "AnimeKai-extension"

include(":animekai")
project(":animekai").projectDir = File(rootDir, "src/en/animekai")
