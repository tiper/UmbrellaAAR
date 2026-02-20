package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.transformClass
import io.github.tiper.umbrellaaar.extensions.unzip
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.NONE
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ExtractDependencies : DefaultTask() {

    @get:Input
    abstract val mainNamespace: Property<String>

    @get:InputFiles
    @get:PathSensitive(NONE)
    abstract val dependencies: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val rootDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val baseDir = outputDir.get().asFile
        baseDir.deleteRecursively()
        baseDir.mkdirs()

        if (dependencies.files.isEmpty()) {
            logger.warn("No dependencies to extract")
            return
        }

        val namespace = mainNamespace.get().replace('.', '/')
        var aarsProcessed = 0
        var jarsProcessed = 0

        logger.lifecycle("Extracting dependencies from ${dependencies.files.size} archives")
        dependencies.files.forEach { file ->
            val folderName = file.relativeTo(rootDir.get().asFile).path
                .replace(File.separatorChar, '_')
                .removeSuffix(".${file.extension}")
            when (file.extension) {
                "aar" -> try {
                    logger.debug("Extracting AAR: ${file.name}")
                    file.unzip(to = File(baseDir, folderName).also { it.mkdirs() }) { entry ->
                        if (entry.name == "classes.jar") {
                            File(baseDir, "classes.jar").apply {
                                getInputStream(entry).use { outputStream().use(it::copyTo) }
                            }.unzip(
                                to = File(baseDir, "$folderName/classes"),
                                transformer = {
                                    it.transformClass(namespace)
                                },
                            ) { !it.isDirectory }.delete()
                            return@unzip false
                        } else !entry.isDirectory &&
                            entry.name.endsWith("aar-metadata.properties").not() &&
                            entry.name.endsWith("classes.jar").not()
                    }
                    aarsProcessed++
                } catch (e: Exception) {
                    logger.warn("Failed to extract AAR ${file.name}: ${e.message}")
                }

                "jar" -> try {
                    logger.debug("Extracting JAR: ${file.name}")
                    // Skip MANIFEST.MF - conflicts with main manifest
                    file.unzip(to = File(baseDir, "$folderName/classes")) {
                        !it.isDirectory && !it.name.endsWith("MANIFEST.MF")
                    }
                    jarsProcessed++
                } catch (e: Exception) {
                    logger.warn("Failed to extract JAR ${file.name}: ${e.message}")
                }

                else -> logger.debug("Ignoring non-JAR/AAR file: ${file.name}")
            }
        }

        logger.lifecycle("Extracted dependencies: $aarsProcessed AARs, $jarsProcessed JARs")
    }
}
