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
import java.io.File

@CacheableTask
internal abstract class ExtractResources : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(RELATIVE)
    abstract val dependencyAars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val resourcesOutputDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val baseDir = resourcesOutputDir.get().asFile
        baseDir.deleteRecursively()
        baseDir.mkdirs()

        dependencyAars.files.forEach { file ->
            if (file.extension == "aar") {
                file.unzip(File(baseDir, file.nameWithoutExtension).also { it.mkdirs() }) {
                    !it.isDirectory &&
                            it.name.endsWith("aar-metadata.properties").not() &&
                            it.name.endsWith("classes.jar").not()
                }
            } else logger.lifecycle("Ignoring non-AAR file for resources: ${file.name}")
        }
    }
}
