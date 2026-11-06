package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.IO_BUFFER_SIZE
import io.github.tiper.umbrellaaar.extensions.normalizePath
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.NONE
import org.gradle.api.tasks.TaskAction

@CacheableTask
internal abstract class MergeSources : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(NONE)
    abstract val dependencySourcesJars: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(NONE)
    abstract val mainSourcesJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val mergedSourcesJar: RegularFileProperty

    @TaskAction
    fun execute() {
        val outputJar = mergedSourcesJar.get().asFile.apply { parentFile.mkdirs() }
        val written = mutableMapOf<String, Pair<Long, Long>>()
        var sourceFilesCollected = 0
        var jarsProcessed = 0

        val dependencyJars = dependencySourcesJars.files.filter { it.extension == "jar" }.sortedBy { it.name }
        val mainJars = mainSourcesJars.files.filter { it.extension == "jar" }

        logger.lifecycle("Merging sources from ${dependencyJars.size + mainJars.size} source JARs")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputJar), IO_BUFFER_SIZE)).use { zos ->
            // The umbrella module first, so its files stay where its own sources jar has them. All of
            // them are streamed straight out of their jars.
            (mainJars + dependencyJars).forEach { jar ->
                logger.debug("Merging sources from: ${jar.name}")
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && (it.name.endsWith(".java") || it.name.endsWith(".kt")) }
                        .sortedBy { it.name }
                        .forEach { entry ->
                            val content = entry.crc to entry.size
                            val entryName = written.freePathFor(entry.name.normalizePath(), content, jar)
                            if (written.put(entryName, content) != null) return@forEach
                            zos.putNextEntry(ZipEntry(entryName).also { it.time = 0L })
                            zip.getInputStream(entry).use { it.copyTo(zos, IO_BUFFER_SIZE) }
                            zos.closeEntry()
                            sourceFilesCollected++
                        }
                }
                jarsProcessed++
            }
        }

        logger.lifecycle("Merged sources: $sourceFilesCollected source files from $jarsProcessed JARs")
    }

    /**
     * Where a file with [content] goes, which is [path] if that is free or holds the same. Kotlin's
     * packages need not follow its folders, so two modules' `commonMain/Platform.kt` are two files,
     * and the AAR has the classes of both.
     */
    private fun Map<String, Pair<Long, Long>>.freePathFor(path: String, content: Pair<Long, Long>, jar: File): String {
        val module = jar.nameWithoutExtension
        return (sequenceOf(path, "$module/$path") + generateSequence(2, Int::inc).map { "$module-$it/$path" }).first { this[it] == null || this[it] == content }
    }
}
