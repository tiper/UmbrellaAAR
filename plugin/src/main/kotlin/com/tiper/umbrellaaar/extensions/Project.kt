package com.tiper.umbrellaaar.extensions

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.File

internal fun Project.findAarOrAssembleTask(buildType: String) = provider {
    listOf(
        "bundle${buildType}Aar",
        "assemble${buildType}",
        "jvmJar",
        "assemble"
    ).firstNotNullOfOrNull(tasks::findByName)
}

internal fun Project.findSourcesJarTask(buildType: String)= provider {
    listOf(
        "android${buildType}SourcesJar",
        "jvmSourcesJar",
        "sourcesJar"
    ).firstNotNullOfOrNull(tasks::findByName)
}

internal fun Project.getMainAarProvider(
    buildType: String,
): Provider<File> = findAarOrAssembleTask(buildType).map { it.outputs.files.singleFile }
