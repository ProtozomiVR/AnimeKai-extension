// Root build file for the AnimeKai Aniyomi/AnymeX extension
// This file configures the top-level Gradle project. The actual extension
// module lives under src/en/animekai and is registered in settings.gradle.kts.

plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
