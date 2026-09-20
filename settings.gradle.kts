pluginManagement {
    repositories {
        // The Gradle Plugin Portal mirrors the Stonecutter plugin and is listed first
        // on purpose: maven.kikugie.dev intermittently fails to respond, and Gradle
        // disables a repository for the rest of the build when it errors rather than
        // silently falling through. Keeping the flaky host first therefore breaks
        // resolution even though a later repository has the artifact.
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.fabricmc.net/")
        mavenCentral()
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
