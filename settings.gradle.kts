pluginManagement {
    repositories {
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.fabricmc.net/")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.6"
}

stonecutter {
    kotlinController = false
    centralScript = "build.gradle"

    create(rootProject) {
        versions("1.21.11", "26.1", "26.2", "26.3")
        vcsVersion = "26.3"
    }
}
