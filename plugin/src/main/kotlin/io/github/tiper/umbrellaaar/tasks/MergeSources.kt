package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.IO_BUFFER_SIZE
import io.github.tiper.umbrellaaar.extensions.contentHash
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
import org.gradle.api.tasks.PathSensitivity.NAME_ONLY
import org.gradle.api.tasks.TaskAction

@CacheableTask
internal abstract class MergeSources : DefaultTask() {

    /** Named, as a jar's name decides the order and where a file that clashes goes. */
    @get:InputFiles
    @get:PathSensitive(NAME_ONLY)
    abstract val dependencySourcesJars: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(NAME_ONLY)
    abstract val mainSourcesJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val mergedSourcesJar: RegularFileProperty

    @TaskAction
    fun execute() {
        val outputJar = mergedSourcesJar.get().asFile.apply { parentFile.mkdirs() }
        val shipped = mutableSetOf<Pair<String, String>>()
        val written = mutableSetOf<String>()
        var sourceFilesCollected = 0
        var jarsProcessed = 0

        // Bytes settle a tie, as the jars of AGP's modules all share one name and their order is no input.
        val dependencyJars = dependencySourcesJars.files.filter { it.extension == "jar" }
            .associateWith { it.contentHash() }.entries
            .sortedWith(compareBy({ it.key.name }, { it.value }))
            .map { it.key }
        val mainJars = mainSourcesJars.files.filter { it.extension == "jar" }

        logger.lifecycle("Merging sources from ${dependencyJars.size + mainJars.size} source JARs")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputJar), IO_BUFFER_SIZE)).use { zos ->
            // The umbrella module first, so its files stay where its own sources jar has them.
            (mainJars + dependencyJars).forEach { jar ->
                logger.debug("Merging sources from: ${jar.name}")
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && (it.name.endsWith(".java") || it.name.endsWith(".kt")) }
                        .sortedBy { it.name }
                        .forEach { entry ->
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            val path = entry.name.normalizePath()
                            if (!shipped.add(path to bytes.contentHash())) return@forEach
                            val entryName = written.freePathFor(path, jar)
                            written += entryName
                            zos.putNextEntry(ZipEntry(entryName).also { it.time = 0L })
                            zos.write(bytes)
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
     * Where a file goes, which is [path] if that is free. Kotlin's packages need not follow its
     * folders, so two modules' `commonMain/Platform.kt` are two files, and the AAR has the classes
     * of both.
     */
    private fun Set<String>.freePathFor(path: String, jar: File): String {
        val module = jar.nameWithoutExtension
        return (sequenceOf(path, "$module/$path") + generateSequence(2, Int::inc).map { "$module-$it/$path" }).first { it !in this }
    }
}
