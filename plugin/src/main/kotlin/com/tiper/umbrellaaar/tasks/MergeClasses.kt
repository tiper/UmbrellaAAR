package com.tiper.umbrellaaar.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@CacheableTask
internal abstract class MergeClasses : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val dependencyClasses: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val mainClasses: DirectoryProperty

    @get:OutputFile
    abstract val mergedJar: RegularFileProperty

    @TaskAction
    fun execute() {
        val outputJar = mergedJar.get().asFile.apply {
            parentFile.mkdirs()
            if (exists()) delete()
        }
        ZipOutputStream(FileOutputStream(outputJar)).use { zos ->
            listOf(dependencyClasses, mainClasses).map { it.get().asFile }.forEach { rootDir ->
                rootDir.walk().filter { it.isFile }.forEach { classFile ->
                    val entryName = classFile.relativeTo(rootDir).path.replace("\\", "/")
                    zos.putNextEntry(ZipEntry(entryName))
                    classFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }
}
