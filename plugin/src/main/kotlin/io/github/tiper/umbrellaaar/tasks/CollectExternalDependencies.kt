package io.github.tiper.umbrellaaar.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

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

        // Input is already deduplicated by Collector — just validate, sort, and write.
        val externalDeps = inputDeps
            .filter { coord ->
                val parts = coord.split(":")
                parts.size == 4 && parts.all { it.isNotBlank() }
            }
            .sorted()

        if (externalDeps.isNotEmpty()) {
            output.writeText(externalDeps.joinToString("\n"))
            logger.lifecycle("Collected ${externalDeps.size} external dependencies")
        } else {
            output.writeText("") // Gradle @OutputFile needs the file even if empty
        }
    }
}
