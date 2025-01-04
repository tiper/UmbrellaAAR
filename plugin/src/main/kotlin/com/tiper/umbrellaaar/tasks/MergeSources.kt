package com.tiper.umbrellaaar.tasks

import com.tiper.umbrellaaar.extensions.unzip
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
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
    @get:PathSensitive(RELATIVE)
    abstract val mainSourcesJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val mergedSourcesJar: RegularFileProperty

    @TaskAction
    fun execute() {
        val outputJar = mergedSourcesJar.get().asFile.apply { parentFile.mkdirs() }
        val mainTemp = temporaryDir.resolve("mainSources").also { it.mkdirs() }

        mainSourcesJars.forEach { jar ->
            jar.unzip(mainTemp) { entry ->
                !entry.isDirectory && (entry.name.endsWith(".java") || entry.name.endsWith(".kt"))
            }
        }

        logger.lifecycle("Merging sources into: $outputJar")

        ZipOutputStream(FileOutputStream(outputJar)).use { zos ->
            // Dep sources
            val depRoot = dependencySources.get().asFile
            depRoot.walk()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .forEach { srcFile ->
                    val entryName = srcFile.relativeTo(depRoot).path.replace("\\", "/")
                    zos.putNextEntry(ZipEntry(entryName))
                    srcFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            // Main sources
            mainTemp.walk()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .forEach { srcFile ->
                    val entryName = srcFile.relativeTo(mainTemp).path.replace("\\", "/")
                    zos.putNextEntry(ZipEntry(entryName))
                    srcFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }
}
