package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.IO_BUFFER_SIZE
import io.github.tiper.umbrellaaar.extensions.normalizePath
import io.github.tiper.umbrellaaar.extensions.transformClass
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater.NO_COMPRESSION
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val SERVICES_DIR = "META-INF/services/"
private const val TARGETED_RULES = "META-INF/com.android.tools/"
private const val MERGED_RULES = "${TARGETED_RULES}r8/umbrella-merged"

/**
 * `classes/<path>` -> the jar entry name, applying the per-module rename of what every module can
 * ship under one name: a `.kotlin_module`, and a targeted rule file, each read from a jar of its own
 * in a consumer of those modules, and whose folder says which R8 reads it.
 */
internal fun classEntry(owner: String, relativePath: String, srcFile: File): Triple<String, String, File> {
    val entryName = relativePath.removePrefix("classes/")
    val renamed = when {
        entryName.endsWith(".kotlin_module") || entryName.startsWith(TARGETED_RULES) -> {
            val dir = entryName.substringBeforeLast('/', missingDelimiterValue = "")
            val file = entryName.substringAfterLast('/')
            if (dir.isEmpty()) "$owner-$file" else "$dir/$owner-$file"
        }

        else -> entryName
    }
    return Triple(renamed, owner, srcFile)
}

/**
 * Writes `classes.jar` from the main module's exploded tree plus every dependency's class files,
 * read directly from where `ExtractDependencies` left them.
 *
 * - The umbrella's own classes get their `R` references relocated here (see [MergeDependencies.execute]).
 * - Service provider files (`META-INF/services/<interface>`) contributed by several modules are merged, because each lists
 *   implementations that exist in the merged jar. Any other duplicate entry is a conflict.
 */
internal fun buildClassesJar(
    out: File,
    dependencyEntries: List<Triple<String, String, File>>,
    umbrellaNamespace: String,
    mergedNamespaces: Set<String>,
    module: String,
    onDuplicate: (owner: String, relativePath: String, file: File, existing: File, existingOwner: String) -> Unit,
    onRField: (field: String, reader: String) -> Unit,
    relocateRules: (String) -> String,
) {
    val classes = File(out, "classes")
    val entries = linkedMapOf<String, Pair<String, File>>()
    val serviceFiles = mutableMapOf<String, MutableList<File>>()

    if (classes.isDirectory) {
        classes.walk().filter { it.isFile }.forEach {
            val entryName = it.relativeTo(classes).path.normalizePath()
            entries[entryName] = MAIN to it
            if (entryName.isServiceFile()) serviceFiles.getOrPut(entryName) { mutableListOf() } += it
        }
    }

    dependencyEntries.forEach { (entryName, owner, file) ->
        if (entryName.isServiceFile()) {
            serviceFiles.getOrPut(entryName) { mutableListOf() } += file
            entries.putIfAbsent(entryName, owner to file)
            return@forEach
        }
        val existing = entries.putIfAbsent(entryName, owner to file)
        if (existing != null) onDuplicate(owner, "classes/$entryName", file, existing.second, existing.first)
    }

    // A consumer that finds targeted rules in an AAR's classes reads no other rules of that AAR.
    val rules = File(out, PROGUARD_TXT)
    if (rules.isFile && entries.keys.any { it.startsWith(TARGETED_RULES) }) {
        val name = generateSequence(1, Int::inc).map { if (it == 1) "$MERGED_RULES.pro" else "$MERGED_RULES-$it.pro" }.first { it !in entries }
        entries[name] = MAIN to rules
    }

    val relocate = umbrellaNamespace.isNotEmpty() && mergedNamespaces.isNotEmpty()
    val jar = File(out, "classes.jar").apply { if (exists()) delete() }
    ZipOutputStream(BufferedOutputStream(FileOutputStream(jar), IO_BUFFER_SIZE)).use { zos ->
        // The AAR deflates this jar as a whole, and deflate finds nothing left in bytes already deflated.
        zos.setLevel(NO_COMPRESSION)
        // Rules last: they go by what reads an `R`, and that takes the umbrella's own classes too.
        entries.entries.sortedWith(compareBy({ it.key.startsWith(TARGETED_RULES) }, { it.key })).forEach { (entryName, source) ->
            val (owner, file) = source
            zos.putNextEntry(ZipEntry(entryName).also { it.time = 0L })
            val services = serviceFiles[entryName]
            when {
                services != null && services.size > 1 -> zos.write(services.mergeServiceFiles())
                relocate && owner == MAIN && entryName.endsWith(".class") ->
                    zos.write(file.readBytes().transformClass(umbrellaNamespace, mergedNamespaces, module, onRField))
                relocate && entryName.startsWith(TARGETED_RULES) ->
                    zos.write(relocateRules(file.readText()).encodeToByteArray())
                else -> file.inputStream().use { it.copyTo(zos, IO_BUFFER_SIZE) }
            }
            zos.closeEntry()
        }
    }
    classes.deleteRecursively()
}

private fun String.isServiceFile() = startsWith(SERVICES_DIR) && length > SERVICES_DIR.length

/** Union of the providers listed by each module, in contribution order, comments dropped. */
private fun List<File>.mergeServiceFiles(): ByteArray = asSequence()
    .flatMap { it.readLines().asSequence() }
    .map { it.substringBefore('#').trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .joinToString(separator = "\n", postfix = "\n")
    .encodeToByteArray()
