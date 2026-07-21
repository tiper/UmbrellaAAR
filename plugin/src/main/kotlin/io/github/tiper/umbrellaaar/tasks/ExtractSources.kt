package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.unzip
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.NONE
import org.gradle.api.tasks.TaskAction

@CacheableTask
internal abstract class ExtractSources : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(NONE)
    abstract val dependencySourcesJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val extractedSourcesDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val outDir = extractedSourcesDir.get().asFile.also {
            it.deleteRecursively()
            it.mkdirs()
        }
        var jarsProcessed = 0

        dependencySourcesJars.forEach { jarFile ->
            if (jarFile.extension == "jar") {
                logger.debug("Extracting sources from: ${jarFile.name}")
                jarFile.unzip(to = outDir) {
                    !it.isDirectory && (it.name.endsWith(".java") || it.name.endsWith(".kt"))
                }
                jarsProcessed++
            } else {
                logger.debug("Ignoring non-jar file: ${jarFile.name}")
            }
        }

        val filesExtracted = outDir.walk().count { it.isFile }
        logger.lifecycle("Extracted sources: $filesExtracted files from $jarsProcessed JARs")
    }
}
