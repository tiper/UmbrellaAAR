package io.github.tiper.umbrellaaar.extensions

import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider

/**
 * Candidate archive tasks, in priority order.
 *
 * Deliberately **not** including `assemble`/`assemble<BuildType>`: those are lifecycle tasks with
 * no outputs, and falling back to them produced the misleading
 * `Cannot find AAR output for task 'assemble'` error instead of saying that the module produces no
 * AAR at all.
 */
private fun aarTaskCandidates(buildTypes: Array<out String>): List<String> = buildTypes.map { "bundle${it}Aar" } + listOf("jvmJar", "jar")

private fun sourcesJarTaskCandidates(buildTypes: Array<out String>): List<String> = buildTypes.flatMap {
    listOf("android${it}SourcesJar", "${it.replaceFirstChar(Char::lowercaseChar)}SourcesJar", "source${it}Jar")
} + listOf("androidSourcesJar", "jvmSourcesJar", "sourcesJar")

/**
 * Resolves a task name without realising the task — `tasks.names` only inspects the registry,
 * whereas `tasks.findByName(...)` eagerly configures every candidate in a foreign project and
 * defeats lazy configuration.
 */
private fun Project.firstRegistered(candidates: List<String>): String? = tasks.names.let { registered ->
    candidates.firstOrNull(registered::contains)
}

internal fun Project.findAarTask(vararg buildTypes: String): Provider<Task> = provider {
    val candidates = aarTaskCandidates(buildTypes)
    val taskName = firstRegistered(candidates) ?: throw GradleException(
        "UmbrellaAar cannot find an archive task in project '$path'.\n" +
            "  Tried: ${candidates.joinToString()}\n" +
            "  Only Android library modules (com.android.library / com.android.kotlin.multiplatform.library) " +
            "and JVM modules can be merged. Remove the module from the umbrellaAar/export graph, " +
            "or exclude it with exclude(group = \"$group\", module = \"$name\").",
    )
    tasks.named(taskName).get()
}

internal fun Project.findSourcesJarTask(vararg buildTypes: String): Provider<Task> = provider {
    val taskName = firstRegistered(sourcesJarTaskCandidates(buildTypes))
    if (taskName == null) {
        logger.info("[UmbrellaAar] No sources jar task in project '$path' — its sources will be missing from the merged sources jar.")
        null
    } else {
        tasks.named(taskName).get()
    }
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
