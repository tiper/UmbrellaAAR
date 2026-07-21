package io.github.tiper.umbrellaaar.extensions

import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider

internal fun Project.findAarTask(vararg buildTypes: String): Provider<Task> = provider {
    buildTypes.flatMap { listOf("bundle${it}Aar", "assemble$it") }
        .plus(listOf("jvmJar", "assemble"))
        .firstNotNullOfOrNull(tasks::findByName)
}

internal fun Project.findSourcesJarTask(vararg buildTypes: String): Provider<Task> = provider {
    buildTypes.flatMap { listOf("android${it}SourcesJar", "${it.lowercase()}SourcesJar", "source${it}Jar") }
        .plus(listOf("androidSourcesJar", "jvmSourcesJar", "sourcesJar"))
        .firstNotNullOfOrNull(tasks::findByName)
}

internal fun Project.findAar(
    vararg buildTypes: String,
): Provider<File> = findAarTask(*buildTypes).map { task ->
    task.outputs.files.singleOrNull { it.extension == "aar" }
        ?: task.outputs.files.singleOrNull()
        ?: throw GradleException(
            "Cannot find AAR output for task '${task.name}' in project '${project.path}'. " +
                "Found ${task.outputs.files.files.size} output file(s): " +
                task.outputs.files.files.joinToString { it.name },
        )
}
