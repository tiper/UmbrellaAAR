package com.tiper.umbrellaaar.tasks

import com.tiper.umbrellaaar.extensions.unzip
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction

@CacheableTask
internal abstract class ExtractSources : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(RELATIVE)
    abstract val dependencySourcesJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val extractedSourcesDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val outDir = extractedSourcesDir.get().asFile.also { it.mkdirs() }
        dependencySourcesJars.forEach { jarFile ->
            if (jarFile.extension == "jar") {
                logger.lifecycle("Extracting sources from: ${jarFile.name}")
                jarFile.unzip(to = outDir) {
                    !it.isDirectory && (it.name.endsWith(".java") || it.name.endsWith(".kt"))
                }
            } else {
                logger.lifecycle("Ignoring non-jar file: ${jarFile.name}")
            }
        }
    }
}
