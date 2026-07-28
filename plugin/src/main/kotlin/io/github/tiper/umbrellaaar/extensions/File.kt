package io.github.tiper.umbrellaaar.extensions

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal const val IO_BUFFER_SIZE = 65_536

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

/**
 * Streams a nested jar (typically an AAR's `classes.jar`) straight from [stream] into [to],
 * applying [transformer] to `.class` entries.
 *
 * The previous implementation copied `classes.jar` to a temporary file first and re-read it, which
 * meant every class was written and read one extra time.
 */
internal fun InputStream.unzipStream(
    to: File,
    transformer: (ByteArray) -> ByteArray = { it },
) {
    val canonicalTo = to.canonicalFile
    java.util.zip.ZipInputStream(this.buffered(IO_BUFFER_SIZE)).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            if (entry.isDirectory) continue
            val outFile = File(to, entry.name).canonicalFile
            require(outFile.path.startsWith(canonicalTo.path + File.separator)) {
                "Zip Slip attempt blocked: '${entry.name}' resolves outside destination"
            }
            outFile.parentFile.mkdirs()
            if (entry.name.endsWith(".class")) {
                outFile.writeBytes(transformer(zis.readBytes()))
            } else {
                outFile.outputStream().buffered(IO_BUFFER_SIZE).use { zis.copyTo(it, IO_BUFFER_SIZE) }
            }
        }
    }
}

internal fun File.zip(to: File) {
    val canonicalRoot = canonicalFile
    to.parentFile?.mkdirs()
    if (to.exists()) to.delete()
    ZipOutputStream(BufferedOutputStream(to.outputStream(), IO_BUFFER_SIZE)).use { zos ->
        walk()
            // Guards against symlinks escaping the tree being archived.
            .onEnter { it.isInsideOf(canonicalRoot) }
            .filter { it.isFile && it.isInsideOf(canonicalRoot) }
            .map { it to it.relativeTo(this).path.normalizePath() }
            .sortedBy { (_, relativePath) -> relativePath }
            .forEach { (file, relativePath) ->
                zos.putNextEntry(ZipEntry(relativePath).also { it.time = 0L })
                file.inputStream().use { it.copyTo(zos, IO_BUFFER_SIZE) }
                zos.closeEntry()
            }
    }
}

private fun File.isInsideOf(canonicalRoot: File): Boolean = canonicalFile.let {
    it == canonicalRoot || it.path.startsWith(canonicalRoot.path + File.separator)
}

