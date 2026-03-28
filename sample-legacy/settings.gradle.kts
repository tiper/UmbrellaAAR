@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    includeBuild("../gradle/build-logic")
    includeBuild("../plugin")
    repositories {
        mavenLocal {
            mavenContent {
                includeGroupAndSubgroups("io.github.tiper")
            }
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal {
            mavenContent {
                includeGroupAndSubgroups("io.github.tiper")
            }
        }
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "sample-legacy"
include(
    ":aidl:sample1",
    ":aidl:sample2",
    ":composable",
    ":export",
    ":jni:sample1",
    ":jni:sample2",
    ":test",
    ":viewmodel",
    ":app",
)


