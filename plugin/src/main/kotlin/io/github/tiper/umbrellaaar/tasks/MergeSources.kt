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
import org.gradle.api.GradleException
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
        val owners = mutableMapOf<String, String>()
        val shadowed = mutableMapOf<String, MutableSet<String>>()
        var sourceFilesCollected = 0
        var jarsProcessed = 0

        val dependencyJars = dependencySourcesJars.files.filter { it.extension == "jar" }.sortedBy { it.name }
        val mainJars = mainSourcesJars.files.filter { it.extension == "jar" }

        logger.lifecycle("Merging sources from ${dependencyJars.size + mainJars.size} source JARs")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputJar), IO_BUFFER_SIZE)).use { zos ->
            // Dependency sources first, then main sources. Both are streamed straight out of their
            // jars; the previous extract-to-disk step (ExtractSources) has been removed.
            (dependencyJars + mainJars).forEach { jar ->
                val isMain = jar in mainJars
                logger.debug("Merging sources from: ${jar.name}")
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && (it.name.endsWith(".java") || it.name.endsWith(".kt")) }
                        .sortedBy { it.name }
                        .forEach { entry ->
                            val entryName = entry.name.normalizePath()
                            val previousOwner = owners.putIfAbsent(entryName, jar.name)
                            if (previousOwner != null) {
                                // A dependency shadowing the umbrella module's own sources means the
                                // umbrella ended up inside its own graph — always fatal, and the
                                // only symptom users used to see for that class of bug.
                                if (isMain) throw selfInclusion(entryName, previousOwner, jar)
                                shadowed.getOrPut(entryName) { mutableSetOf(previousOwner) } += jar.name
                                return@forEach
                            }
                            zos.putNextEntry(ZipEntry(entryName).also { it.time = 0L })
                            zip.getInputStream(entry).use { it.copyTo(zos, IO_BUFFER_SIZE) }
                            zos.closeEntry()
                            sourceFilesCollected++
                        }
                }
                jarsProcessed++
            }
        }

        if (shadowed.isNotEmpty()) {
            logger.warn(
                buildString {
                    appendLine("UmbrellaAar: ${shadowed.size} source file(s) exist in more than one merged module; the first one was kept:")
                    shadowed.entries.sortedBy { it.key }.take(MAX_REPORTED).forEach { (entry, jars) ->
                        appendLine("  $entry <- ${jars.joinToString()}")
                    }
                    if (shadowed.size > MAX_REPORTED) append("  … and ${shadowed.size - MAX_REPORTED} more")
                }.trimEnd(),
            )
        }

        logger.lifecycle("Merged sources: $sourceFilesCollected source files from $jarsProcessed JARs")
    }

    private fun selfInclusion(entryName: String, previousOwner: String, jar: File) = GradleException(
        buildString {
            appendLine("UmbrellaAar found the umbrella module's own source '$entryName' among its merged dependencies.")
            appendLine("  Contributed by     : $previousOwner")
            appendLine("  Main sources jar   : ${jar.name}")
            append(
                "  The umbrella module appears inside its own graph. Check " +
                    "build/reports/umbrellaaar/*/merged-modules.txt for the merged module list and the edge that " +
                    "pulled each module in.",
            )
        },
    )

    private companion object {
        const val MAX_REPORTED = 20
    }
}

