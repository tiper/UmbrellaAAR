package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.transformClass
import io.github.tiper.umbrellaaar.extensions.unzip
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
abstract class ExtractDependencies : DefaultTask() {

    @get:Input
    abstract val mainNamespace: Property<String>

    @get:InputFiles
    @get:PathSensitive(RELATIVE)
    abstract val dependencies: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val baseDir = outputDir.get().asFile
        baseDir.deleteRecursively()
        baseDir.mkdirs()

        dependencies.files.forEach { file ->
            when(file.extension) {
                "aar" -> file.unzip(to = File(baseDir, file.nameWithoutExtension).also { it.mkdirs() }) { entry ->
                    if (entry.name == "classes.jar") {
                        File(baseDir, "classes.jar").apply {
                            getInputStream(entry).use { outputStream().use(it::copyTo) }
                        }.unzip(
                            to = File(baseDir, "${file.nameWithoutExtension}/classes"),
                            transformer = {
                                it.transformClass(mainNamespace.get().replace('.', '/'))
                            }
                        ) { !it.isDirectory }.delete()
                        return@unzip false
                    }
                    else !entry.isDirectory
                            && entry.name.endsWith("aar-metadata.properties").not()
                            && entry.name.endsWith("classes.jar").not()
                }
                "jar" -> file.unzip(to = File(baseDir, "${file.nameWithoutExtension}/classes")) {
                    !it.isDirectory && !it.name.endsWith("MANIFEST.MF")
                }
                else -> logger.lifecycle("Ignoring non-JAR/AAR file: ${file.name}")
            }
        }
    }
}
