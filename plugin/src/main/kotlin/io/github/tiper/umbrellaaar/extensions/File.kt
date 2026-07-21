package io.github.tiper.umbrellaaar.extensions

import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val IO_BUFFER_SIZE = 65_536

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
                    outFile.apply { parentFile.mkdirs() }.outputStream().buffered(IO_BUFFER_SIZE).use {
                        if (entry.name.endsWith(".class")) it.write(transformer(input.readBytes()))
                        else input.copyTo(it, IO_BUFFER_SIZE)
                    }
                }
            }
    }
    return this
}

internal fun File.zip(to: File) {
    to.parentFile?.mkdirs()
    if (to.exists()) to.delete()
    ZipOutputStream(BufferedOutputStream(to.outputStream(), IO_BUFFER_SIZE)).use { zos ->
        walk()
            .filter { it.isFile }
            .map { it to it.relativeTo(this).path.normalizePath() }
            .sortedBy { (_, relativePath) -> relativePath }
            .forEach { (file, relativePath) ->
                zos.putNextEntry(ZipEntry(relativePath).also { it.time = 0L })
                file.inputStream().use { it.copyTo(zos, IO_BUFFER_SIZE) }
                zos.closeEntry()
            }
    }
}
