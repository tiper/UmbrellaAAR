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
                zip.getInputStream(entry).use { input ->
                    File(to, entry.name).apply { parentFile.mkdirs() }.outputStream().use { output ->
                        output.write(
                            input.readBytes().let {
                                if (entry.name.endsWith(".class")) transformer(it) else it
                            },
                        )
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
        walk().filter { it.isFile }.forEach { file ->
            zos.putNextEntry(ZipEntry(file.relativeTo(this).path.normalizePath()))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}
