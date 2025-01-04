package com.tiper.umbrellaaar.extensions

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal fun File.extractJar(to: File): File? {
    if (exists()) {
        ZipFile(this).use { zip ->
            zip.getEntry("classes.jar")?.let { entry ->
                return File(to, "${nameWithoutExtension}-classes.jar").apply {
                    zip.getInputStream(entry).use { outputStream().use(it::copyTo) }
                }
            }
        }
    }
    return null
}

internal fun File.unzip(to: File, predicate: (ZipEntry) -> Boolean = { true }) {
    ZipFile(this).use { zip ->
        zip.entries().asSequence()
            .filter(predicate)
            .forEach { entry ->
                zip.getInputStream(entry).use {
                    File(to, entry.name).apply { parentFile.mkdirs() }.outputStream().use(it::copyTo)
                }
            }
    }
}

internal fun File.zip(to: File) {
    to.parentFile?.mkdirs()
    if (to.exists()) to.delete()
    ZipOutputStream(to.outputStream()).use { zos ->
        walk().filter { it.isFile }.forEach { file ->
            zos.putNextEntry(ZipEntry(file.relativeTo(this).path.replace("\\", "/")))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}
