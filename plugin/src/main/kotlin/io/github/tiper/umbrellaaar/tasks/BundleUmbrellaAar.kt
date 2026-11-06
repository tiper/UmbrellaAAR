package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.zip
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import java.util.Locale.ROOT

@CacheableTask
abstract class BundleUmbrellaAar : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val unpackedMainAar: DirectoryProperty

    @get:OutputFile
    abstract val umbrellaAarOutput: RegularFileProperty

    /** What the build wrote about this AAR. Named at the end of the log, and no part of the AAR. */
    @get:Internal
    abstract val reports: ConfigurableFileCollection

    @TaskAction
    fun execute() {
        val unpackedDir = unpackedMainAar.get().asFile
        val outAar = umbrellaAarOutput.get().asFile.apply { parentFile.mkdirs() }

        unpackedDir.zip(to = outAar)

        val fileSizeBytes = outAar.length()
        val fileSizeMb = fileSizeBytes / (1024.0 * 1024.0)
        logger.lifecycle("Created UmbrellaAar: ${outAar.name} (${String.format(ROOT, "%.2f", fileSizeMb)} MB, $fileSizeBytes bytes)")

        val written = reports.files.filter { it.length() > 0 }
        if (written.isNotEmpty()) logger.lifecycle("[UmbrellaAar] Reports: ${written.joinToString()}")
    }
}
