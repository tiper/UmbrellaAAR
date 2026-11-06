package io.github.tiper.umbrellaaar.extensions

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
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
    val written = mutableMapOf<String, String>()
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
                written.claim(entry.name, to)
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
    val written = mutableMapOf<String, String>()
    java.util.zip.ZipInputStream(this.buffered(IO_BUFFER_SIZE)).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            if (entry.isDirectory) continue
            val outFile = File(to, entry.name).canonicalFile
            require(outFile.path.startsWith(canonicalTo.path + File.separator)) {
                "Zip Slip attempt blocked: '${entry.name}' resolves outside destination"
            }
            written.claim(entry.name, to)
            outFile.parentFile.mkdirs()
            if (entry.name.endsWith(".class")) {
                outFile.writeBytes(transformer(zis.readBytes()))
            } else {
                outFile.outputStream().buffered(IO_BUFFER_SIZE).use { zis.copyTo(it, IO_BUFFER_SIZE) }
            }
        }
    }
}

internal const val CASE_VARIANT_ADVICE = "Rename one in its module, or build on a case-sensitive disk."

internal fun caseVariantsOf(first: String, second: String) = "'$first' and '$second' differ by case alone, and this disk has one path for both"

private fun MutableMap<String, String>.claim(entry: String, to: File) {
    caseVariant(entry, to)?.let { (first, second) -> throw IllegalArgumentException("${caseVariantsOf(first, second)}. $CASE_VARIANT_ADVICE") }
}

/**
 * On a disk that ignores case the second of two entries overwrites the first, without a word, and
 * the second of two folders moves its files into the first. What is on the disk under [to] says
 * whether those are one. Takes [entry] and its folders, and returns what it has by case alone, if
 * anything, as the path taken first and its variant.
 */
internal fun MutableMap<String, String>.caseVariant(entry: String, to: File): Pair<String, String>? = entry.andFolders().firstNotNullOfOrNull { path ->
    putIfAbsent(path.lowercase(), path)?.takeIf { it != path && File(to, path).isSameFileAs(File(to, it)) }?.let { it to path }
}

/** This relative path, then the folders it is in, from the nearest out. */
internal fun String.andFolders() = generateSequence(this) { it.substringBeforeLast('/', "").ifEmpty { null } }

/** Empty for what is no file, which a build need not have made by the time it asks. */
internal fun File.contentHash(): String {
    if (!isFile) return ""
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(IO_BUFFER_SIZE)
    inputStream().use { input -> generateSequence { input.read(buffer).takeIf { it >= 0 } }.forEach { digest.update(buffer, 0, it) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun File.sameBytesAs(other: File) = length() == other.length() && readBytes().contentEquals(other.readBytes())

private fun File.isSameFileAs(other: File) = exists() && other.exists() && Files.isSameFile(toPath(), other.toPath())

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

