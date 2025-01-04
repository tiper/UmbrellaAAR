package com.tiper.umbrellaaar.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@CacheableTask
abstract class RelocateRClasses : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(RELATIVE)
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val relocatedJar: RegularFileProperty

    @get:Input
    abstract val mainNamespace: Property<String>

    @TaskAction
    fun doRelocation() {
        val inputFile = inputJar.get().asFile
        val outputFile = relocatedJar.get().asFile.apply { parentFile.mkdirs() }

        val mainNsInternal = mainNamespace.get().replace('.', '/')

        ZipInputStream(inputFile.inputStream()).use { zis ->
            ZipOutputStream(outputFile.outputStream()).use { zos ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    if (!entry.isDirectory && entryName.endsWith(".class")) {
                        val transformedBytes = zis.readBytes().transformClass(mainNsInternal)
                        zos.putNextEntry(ZipEntry(entryName))
                        zos.write(transformedBytes)
                        zos.closeEntry()
                    } else {
                        zos.putNextEntry(ZipEntry(entryName))
                        zis.copyTo(zos)
                        zos.closeEntry()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun ByteArray.transformClass(
        mainNsInternal: String,
    ): ByteArray {
        val reader = ClassReader(this)
        val writer = ClassWriter(reader, 0)
        val remapper = object : Remapper() {
            override fun map(internalName: String): String {
                if (internalName.startsWith("android/")
                    || internalName.startsWith("androidx/")
                    || internalName.startsWith("com/google/android/material/")
                ) {
                    return super.map(internalName)
                }
                if (internalName.startsWith("$mainNsInternal/R")) {
                    return super.map(internalName)
                }
                val rIndex = internalName.indexOf("/R$")
                if (rIndex > 0) {
                    return mainNsInternal + internalName.substring(rIndex)
                }
                return super.map(internalName)
            }
        }
        val cr = ClassRemapper(writer, remapper)
        reader.accept(cr, 0)
        return writer.toByteArray()
    }
}
