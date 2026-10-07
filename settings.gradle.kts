// Pinned per spec 10: qupath-extension-template @ bd825fc1b6d8ff25660e2fe853e97830b298986a
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
}

qupath {
    version = "0.7.0"
}

plugins {
    id("io.github.qupath.qupath-extension-settings") version "0.2.1"
}
