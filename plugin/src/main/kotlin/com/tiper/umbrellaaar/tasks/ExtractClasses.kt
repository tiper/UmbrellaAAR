package com.tiper.umbrellaaar.tasks

import com.tiper.umbrellaaar.extensions.extractJar
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
internal abstract class ExtractClasses : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(RELATIVE)
    abstract val dependencyAars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val classesOutputDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val outDir = classesOutputDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        dependencyAars.files.forEach { file ->
            when (file.extension) {
                "aar" -> file.extractJar(to = temporaryDir)?.unzip(to = outDir) { !it.isDirectory }
                "jar" -> file.unzip(to = outDir) { !it.isDirectory }
                else -> logger.lifecycle("Ignoring non-JAR/AAR file: ${file.name}")
            }
        }
    }
}
