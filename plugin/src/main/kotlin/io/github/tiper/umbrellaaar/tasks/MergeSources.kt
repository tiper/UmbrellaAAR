package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.normalizePath
import io.github.tiper.umbrellaaar.extensions.unzip
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.NONE
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@CacheableTask
internal abstract class MergeSources : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val dependencySources: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(NONE)
    abstract val mainSourcesJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val mergedSourcesJar: RegularFileProperty

    @TaskAction
    fun execute() {
        val outputJar = mergedSourcesJar.get().asFile.apply { parentFile.mkdirs() }
        val mainTemp = temporaryDir.resolve("mainSources").also { it.mkdirs() }

        var sourceFilesCollected = 0
        var jarsProcessed = 0

        logger.lifecycle("Merging sources from ${mainSourcesJars.files.size} source JARs")
        mainSourcesJars.forEach { jar ->
            logger.debug("Extracting sources from: ${jar.name}")
            jar.unzip(mainTemp) {
                !it.isDirectory && (it.name.endsWith(".java") || it.name.endsWith(".kt"))
            }
            jarsProcessed++
        }

        ZipOutputStream(FileOutputStream(outputJar)).use { zos ->
            // Dep sources
            val depRoot = dependencySources.get().asFile
            if (depRoot.exists()) {
                depRoot.walk()
                    .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                    .forEach { srcFile ->
                        val entryName = srcFile.relativeTo(depRoot).path.normalizePath()
                        zos.putNextEntry(ZipEntry(entryName))
                        srcFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        sourceFilesCollected++
                    }
            }

            // Main sources
            mainTemp.walk()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .forEach { srcFile ->
                    val entryName = srcFile.relativeTo(mainTemp).path.normalizePath()
                    zos.putNextEntry(ZipEntry(entryName))
                    srcFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    sourceFilesCollected++
                }
        }

        logger.lifecycle("Merged sources: $sourceFilesCollected source files from $jarsProcessed JARs")
    }
}
