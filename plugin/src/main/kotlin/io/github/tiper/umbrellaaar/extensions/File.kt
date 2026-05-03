package io.github.tiper.umbrellaaar.extensions

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal fun String.normalizePath(): String = replace("\\", "/")

internal fun File.unzip(
    to: File,
    transformer: (ByteArray) -> ByteArray = { it },
    predicate: ZipFile.(ZipEntry) -> Boolean = { true },
): File {
    ZipFile(this).use { zip ->
        zip.entries().asSequence()
            .filter { zip.predicate(it) }
            .forEach { entry ->
                if (entry.isDirectory) {
                    File(to, entry.name).mkdirs()
                    return@forEach
                }
                zip.getInputStream(entry).use { input ->
                    File(to, entry.name).apply { parentFile.mkdirs() }.outputStream().use { output ->
                        if (entry.name.endsWith(".class")) {
                            output.write(transformer(input.readBytes()))
                        } else {
                            input.copyTo(output)
                        }
                    }
                }
            }
    }
    return this
}

internal fun File.zip(to: File) {
    to.parentFile?.mkdirs()
    if (to.exists()) to.delete()
    ZipOutputStream(to.outputStream()).use { zos ->
        walk()
            .filter { it.isFile }
            .map { it to it.relativeTo(this).path.normalizePath() }
            .sortedBy { (_, relativePath) -> relativePath }
            .forEach { (file, relativePath) ->
                zos.putNextEntry(ZipEntry(relativePath))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
    }
}
