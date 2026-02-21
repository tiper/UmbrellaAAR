@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    includeBuild("gradle/build-logic")
    includeBuild("plugin")
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
}

rootProject.name = "UmbrellaAAR"
include(
    ":sample:aidl:sample1",
    ":sample:aidl:sample2",
    ":sample:composable",
    ":sample:export",
    ":sample:jni:sample1",
    ":sample:jni:sample2",
    ":sample:test",
    ":sample:viewmodel",
    ":sample:app",
)
