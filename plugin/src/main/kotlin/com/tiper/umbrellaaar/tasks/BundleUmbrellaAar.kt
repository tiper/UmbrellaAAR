package com.tiper.umbrellaaar.tasks

import com.tiper.umbrellaaar.extensions.zip
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
internal abstract class BundleUmbrellaAar : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(RELATIVE)
    abstract val mergedJar: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val unpackedMainAar: DirectoryProperty

    @get:OutputFile
    abstract val umbrellAarOutput: RegularFileProperty

    @TaskAction
    fun execute() {
        val unpackedDir = unpackedMainAar.get().asFile
        val outAar = umbrellAarOutput.get().asFile.apply { parentFile.mkdirs() }

        mergedJar.get().asFile.copyTo(
            target = File(unpackedDir, "classes.jar").apply { delete() },
            overwrite = true
        )

        unpackedDir.zip(to = outAar)
        logger.lifecycle("Created fat AAR: ${outAar.absolutePath}")
    }
}
