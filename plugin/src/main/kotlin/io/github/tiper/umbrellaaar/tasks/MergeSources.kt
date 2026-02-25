package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.normalizePath
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
        val seen = mutableSetOf<String>()
        var sourceFilesCollected = 0
        var jarsProcessed = 0

        logger.lifecycle("Merging sources from ${mainSourcesJars.files.size} source JARs")

        ZipOutputStream(FileOutputStream(outputJar)).use { zos ->

            // Dep sources first, then main sources — duplicates throw, consistent with MergeDependencies
            val depRoot = dependencySources.get().asFile
            if (depRoot.exists()) {
                depRoot.walk()
                    .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                    .forEach { srcFile ->
                        val entryName = srcFile.relativeTo(depRoot).path.normalizePath()
                        if (seen.add(entryName)) {
                            zos.putNextEntry(ZipEntry(entryName))
                            srcFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                            sourceFilesCollected++
                        } else {
                            throw GradleException("Source duplicate: $entryName")
                        }
                    }
            }

            // Main sources — streamed directly from each source jar (no temp extraction)
            mainSourcesJars.forEach { jar ->
                if (jar.extension != "jar") return@forEach
                logger.debug("Merging sources from: ${jar.name}")
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && (it.name.endsWith(".java") || it.name.endsWith(".kt")) }
                        .forEach { entry ->
                            val entryName = entry.name.normalizePath()
                            if (seen.add(entryName)) {
                                zos.putNextEntry(ZipEntry(entryName))
                                zip.getInputStream(entry).use { it.copyTo(zos) }
                                zos.closeEntry()
                                sourceFilesCollected++
                            } else {
                                throw GradleException("Source duplicate: $entryName")
                            }
                        }
                }
                jarsProcessed++
            }
        }

        logger.lifecycle("Merged sources: $sourceFilesCollected source files from $jarsProcessed JARs")
    }
}
