package io.github.tiper.umbrellaaar.extensions

import java.io.File
import org.gradle.api.Project
import org.gradle.api.provider.Provider

internal fun Project.findAarTask(buildType: String) = provider {
    listOf(
        "bundle${buildType}Aar",
        "assemble$buildType",
        "jvmJar",
        "assemble",
    ).firstNotNullOfOrNull(tasks::findByName)
}

internal fun Project.findSourcesJarTask(buildType: String) = provider {
    listOf(
        "android${buildType}SourcesJar",
        "${buildType}SourcesJar",
        "androidSourcesJar",
        "jvmSourcesJar",
        "sourcesJar",
    ).firstNotNullOfOrNull(tasks::findByName)
}

internal fun Project.findAar(
    buildType: String,
): Provider<File> = findAarTask(buildType).map { it.outputs.files.singleFile }
