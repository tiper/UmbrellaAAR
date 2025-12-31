package io.github.tiper.umbrellaaar.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.Serializable

/**
 * Task that collects and deduplicates external dependencies for the POM file.
 * This task is cacheable - if inputs haven't changed, outputs can be restored from cache.
 */
@CacheableTask
abstract class CollectExternalDependencies : DefaultTask() {

    @get:Input
    abstract val dependencies: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val inputDeps = dependencies.get()
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        // Parse and deduplicate dependencies
        val externalDeps = inputDeps
            .map { it.split(":") }
            .filter { it.size == 4 }
            .map {
                DependencyInfo(
                    group = it[0],
                    name = it[1],
                    version = it[2],
                    scope = it[3]
                )
            }
            .distinctBy { "${it.group}:${it.name}" }
            .sortedWith(compareBy({ it.group }, { it.name }))

        if (externalDeps.isNotEmpty()) {
            output.writeText(externalDeps.joinToString("\n") {
                "${it.group}:${it.name}:${it.version}:${it.scope}"
            })
            val deduplicatedCount = inputDeps.size - externalDeps.size
            logger.lifecycle("Collected ${externalDeps.size} unique external dependencies" +
                if (deduplicatedCount > 0) " (deduplicated $deduplicatedCount duplicate entries)" else "")
        } else {
            // Ensure output file is created even if empty (required for @OutputFile)
            output.writeText("")
        }
    }

    data class DependencyInfo(
        val group: String,
        val name: String,
        val version: String,
        val scope: String
    ) : Serializable
}
