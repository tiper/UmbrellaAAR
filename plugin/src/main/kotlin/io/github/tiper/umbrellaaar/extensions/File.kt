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
    val canonicalTo = to.canonicalFile
    ZipFile(this).use { zip ->
        zip.entries().asSequence()
            .filter { zip.predicate(it) }
            .forEach { entry ->
                val outFile = File(to, entry.name).canonicalFile
                require(outFile.path.startsWith(canonicalTo.path + File.separator)) {
                    "Zip Slip attempt blocked: '${entry.name}' in '${this.name}' resolves outside destination"
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                    return@forEach
                }
                zip.getInputStream(entry).use { input ->
                    outFile.apply { parentFile.mkdirs() }.outputStream().use {
                        if (entry.name.endsWith(".class")) it.write(transformer(input.readBytes()))
                        else input.copyTo(it)
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
                zos.putNextEntry(ZipEntry(relativePath).also { it.time = 0L })
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
    }
}
