package com.tiper.umbrellaaar.tasks

import com.tiper.umbrellaaar.extensions.extractJar
import com.tiper.umbrellaaar.extensions.unzip
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction

@CacheableTask
internal abstract class ExtractMainAar : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(RELATIVE)
    abstract val mainAar: RegularFileProperty

    @get:OutputDirectory
    abstract val classesOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val unpackedAarDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val outDir = classesOutputDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val aarDir = unpackedAarDir.get().asFile
        aarDir.deleteRecursively()
        aarDir.mkdirs()

        val aarFile = mainAar.get().asFile
        aarFile.extractJar(to = temporaryDir)?.unzip(to = outDir) {
            !it.isDirectory && it.name.endsWith(".class")
        }
        aarFile.unzip(aarDir) { true }
    }
}
