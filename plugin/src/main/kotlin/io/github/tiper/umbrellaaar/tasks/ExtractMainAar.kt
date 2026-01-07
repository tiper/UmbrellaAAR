package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.unzip
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.NONE
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
abstract class ExtractMainAar : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(NONE)  // Use content-based hashing, not path-based
    abstract val mainAar: RegularFileProperty

    @get:OutputDirectory
    abstract val unpackedAarDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val aarDir = unpackedAarDir.get().asFile
        aarDir.deleteRecursively()
        aarDir.mkdirs()

        val aarFile = mainAar.get().asFile
        logger.lifecycle("Extracting main AAR: ${aarFile.name}")
        aarFile.unzip(aarDir) { entry ->
            if (entry.name == "classes.jar") {
                File(aarDir, "classes.jar").apply {
                    getInputStream(entry).use { outputStream().use(it::copyTo) }
                }.unzip(to = File(aarDir, "classes")) { !it.isDirectory }.delete()
                return@unzip false
            }
            !entry.isDirectory
        }
        logger.lifecycle("Extracted main AAR to: ${aarDir.absolutePath}")
    }
}
