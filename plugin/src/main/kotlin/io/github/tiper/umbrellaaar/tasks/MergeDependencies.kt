package io.github.tiper.umbrellaaar.tasks

import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.ManifestMerger2.Invoker.Feature.USES_SDK_IN_MANIFEST_LENIENT_HANDLING
import com.android.manifmerger.ManifestMerger2.MergeType.LIBRARY
import com.android.manifmerger.ManifestProvider
import com.android.manifmerger.ManifestSystemProperty.Document.PACKAGE
import com.android.manifmerger.MergingReport.MergedManifestKind.MERGED
import com.android.manifmerger.MergingReport.Record.Severity.ERROR
import com.android.utils.ILogger
import io.github.tiper.umbrellaaar.extensions.IO_BUFFER_SIZE
import io.github.tiper.umbrellaaar.extensions.declaredResourceNames
import io.github.tiper.umbrellaaar.extensions.normalizePath
import io.github.tiper.umbrellaaar.extensions.stripPackageAttribute
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class MergeDependencies : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val dependencies: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val mainAarDir: DirectoryProperty


    @get:OutputDirectory
    abstract val mergedAarDir: DirectoryProperty

    /** `entry path -> module folder that contributed it`, so duplicates can name *both* sides. */
    private val contributors = mutableMapOf<String, String>()

    /** `res/values*` folder + resource name -> module folders that declared it. */
    private val resourceNames = mutableMapOf<String, MutableList<String>>()

    /** Lines already written to each appended file (`R.txt`, `public.txt`), to keep merging linear. */
    private val seenLines = mutableMapOf<File, MutableSet<String>>()

    @TaskAction
    fun execute() {
        contributors.clear()
        resourceNames.clear()
        seenLines.clear()

        val src = mainAarDir.get().asFile
        val out = mergedAarDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }

        src.copyRecursively(out, overwrite = true)
        src.walk().filter { it.isFile }.forEach { contributors[it.relativeTo(src).path.normalizePath()] = MAIN }

        val manifest = File(out, "AndroidManifest.xml")
        val packageOverride = manifest.removePackage()
        val touchedProguardFiles = mutableSetOf<File>()
        val appendedFiles = mutableSetOf<File>()
        var filesProcessed = 0

        dependencies.get().asFile.listFiles()?.sortedBy { it.name }?.forEach { subLibFolder ->
            if (!subLibFolder.isDirectory) return@forEach
            val owner = subLibFolder.name

            subLibFolder.walk()
                .filter { it.isFile }
                .map { it to it.relativeTo(subLibFolder).path.normalizePath() }
                .sortedBy { (_, relativePath) -> relativePath }
                .forEach { (srcFile, relativePath) ->
                    val destFile = File(out, relativePath)

                    when {
                        relativePath.startsWith("res/values") -> {
                            recordResourceNames(owner, relativePath, srcFile)
                            srcFile.copyValues(owner = owner, to = destFile)
                        }

                        relativePath.endsWith(".kotlin_module") -> srcFile.copyValues(owner = owner, to = destFile)

                        // R.txt is a line-based symbol table: concatenating it verbatim produced
                        // duplicated (and potentially conflicting) symbols.
                        relativePath.endsWith("R.txt") -> appendedFiles += srcFile.appendLines(to = destFile, deduplicate = true)

                        // Consumer rules live in `proguard.txt` inside an AAR — `consumer-rules.pro`
                        // is not part of the AAR spec and consumers simply ignore it.
                        relativePath.endsWith("proguard.txt") || relativePath.endsWith(".pro") -> {
                            val target = File(out, "proguard.txt")
                            touchedProguardFiles += srcFile.appendLines(to = target, deduplicate = false)
                        }

                        relativePath.endsWith("AndroidManifest.xml") -> srcFile.mergeManifest(
                            to = manifest,
                            packageOverride = packageOverride,
                        )

                        destFile.exists() -> onDuplicate(owner, relativePath, srcFile, destFile)

                        else -> {
                            destFile.parentFile?.mkdirs()
                            srcFile.copyTo(destFile, overwrite = true)
                            contributors[relativePath] = owner
                        }
                    }
                    filesProcessed++
                }
        }

        reportDuplicateResources()

        (appendedFiles + touchedProguardFiles).forEach { it.ensureTrailingNewline() }

        val jar = File(out, "classes.jar").apply {
            if (exists()) delete()
        }
        val classes = File(out, "classes")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(jar), IO_BUFFER_SIZE)).use { zos ->
            classes.walk()
                .filter { it.isFile }
                .map { it to it.relativeTo(classes).path.normalizePath() }
                .sortedBy { (_, entryName) -> entryName }
                .forEach { (classFile, entryName) ->
                    zos.putNextEntry(ZipEntry(entryName).also { it.time = 0L })
                    classFile.inputStream().use { it.copyTo(zos, IO_BUFFER_SIZE) }
                    zos.closeEntry()
                }
        }
        classes.deleteRecursively()
        logger.lifecycle("Merged dependencies into main AAR (processed $filesProcessed files)")
    }

    /**
     * Two modules contributed the same entry. Identical bytes are harmless (the same generated file
     * shipped twice); anything else is a real conflict and must name *both* contributors — the old
     * message only named the second one, which made this class of failure very hard to diagnose.
     */
    private fun onDuplicate(owner: String, relativePath: String, srcFile: File, destFile: File) {
        if (srcFile.length() == destFile.length() && srcFile.readBytes().contentEquals(destFile.readBytes())) {
            logger.info("[UmbrellaAar] Duplicate but identical '$relativePath' from '$owner' — keeping one copy.")
            return
        }
        throw GradleException(
            buildString {
                appendLine("UmbrellaAar cannot merge '$relativePath': it is contributed by two modules with different content.")
                appendLine("  First contributor : ${contributors[relativePath] ?: "unknown"}")
                appendLine("  Second contributor: $owner")
                append(
                    when {
                        relativePath.startsWith("assets/") -> "  Rename the asset in one of the modules, or move it to a module-specific sub-folder."
                        relativePath.startsWith("jni/") -> "  Two modules ship a native library with the same name — rename one of them."
                        relativePath.startsWith("libs/") -> "  Two modules embed a local jar with the same name — rename one of them."
                        relativePath.startsWith("classes/") -> "  Two modules declare the same class. If one of them is the umbrella module itself, " +
                            "check build/reports/umbrellaaar for the merged module list."
                        else -> "  Rename the file in one of the modules, or exclude the module from the umbrellaAar configuration."
                    },
                )
            },
        )
    }

    private fun recordResourceNames(owner: String, relativePath: String, srcFile: File) {
        val folder = relativePath.substringBeforeLast('/')
        srcFile.readText().declaredResourceNames().forEach { resource ->
            resourceNames.getOrPut("$folder/$resource") { mutableListOf() } += owner
        }
    }

    private fun reportDuplicateResources() {
        val duplicates = resourceNames.filterValues { it.size > 1 }
        if (duplicates.isEmpty()) return

        logger.warn(
            buildString {
                appendLine("UmbrellaAar found ${duplicates.size} duplicate resource name(s) across merged modules; AAPT2 resolves these last-one-wins in the consumer:")
                duplicates.entries.sortedBy { it.key }.take(MAX_REPORTED).forEach { (key, owners) ->
                    appendLine("  $key <- ${owners.joinToString()}")
                }
                if (duplicates.size > MAX_REPORTED) append("  … and ${duplicates.size - MAX_REPORTED} more")
            }.trimEnd(),
        )
    }

    private fun File.removePackage(): String {
        if (!exists()) {
            logger.warn("Main manifest does not exist at: $absolutePath")
            return ""
        }
        val (cleanedXml, pkgName) = readText().stripPackageAttribute()
        writeText(cleanedXml)
        return pkgName.orEmpty()
    }

    private fun File.mergeManifest(to: File, packageOverride: String) {
        if (!to.exists()) {
            copyTo(to, overwrite = true)
            return
        }

        try {
            val report = ManifestMerger2.newMerger(to, GradleILogger(logger), LIBRARY)
                .addManifestProviders(listOf(toManifestProvider()))
                .withFeatures(USES_SDK_IN_MANIFEST_LENIENT_HANDLING)
                .apply {
                    if (packageOverride.isNotEmpty()) {
                        setOverride(PACKAGE, packageOverride)
                        setNamespace(packageOverride)
                    }
                }
                .merge()

            when {
                report.result.isError -> {
                    val errors = report.loggingRecords
                        .filter { it.severity == ERROR }
                        .joinToString("\n") { it.message }
                    throw GradleException("Manifest merge failed:\n$errors")
                }

                else -> {
                    val mergedXml = report.getMergedDocument(MERGED)
                        ?: throw GradleException("Manifest merge succeeded but produced no output")
                    to.writeText(mergedXml)
                }
            }
        } catch (e: GradleException) {
            throw e
        } catch (e: Exception) {
            throw GradleException("Failed to merge manifests: ${e.message}", e)
        }
    }

    private fun File.toManifestProvider() = object : ManifestProvider {
        override fun getName(): String = this@toManifestProvider.name
        override fun getManifest(): File = this@toManifestProvider
    }

    private class GradleILogger(private val logger: Logger) : ILogger {
        override fun error(t: Throwable?, msgFormat: String?, vararg args: Any?) {
            logger.error(msgFormat?.format(*args), t)
        }
        override fun warning(msgFormat: String?, vararg args: Any?) {
            logger.warn(msgFormat?.format(*args))
        }
        override fun info(msgFormat: String?, vararg args: Any?) {
            logger.info(msgFormat?.format(*args))
        }
        override fun verbose(msgFormat: String?, vararg args: Any?) {
            logger.debug(msgFormat?.format(*args))
        }
    }

    private fun File.copyValues(owner: String, to: File) = copyTo(
        target = File(
            to.parentFile,
            "$owner-${to.nameWithoutExtension}.${to.extension}",
        ).also { it.parentFile.mkdirs() },
        overwrite = true,
    )

    /**
     * Streams [this] into [to] line by line. `R.txt` reaches megabytes on large graphs, so the file
     * is never held in memory, and [deduplicate] keeps repeated symbols out of the merged table.
     */
    private fun File.appendLines(to: File, deduplicate: Boolean): File {
        val existing = if (deduplicate && to.exists()) to.readLines().filter(String::isNotBlank).toMutableSet() else mutableSetOf()
        val appendToExisting = to.exists() && to.length() > 0L
        to.parentFile?.mkdirs()

        to.bufferedWriter(options = if (appendToExisting) APPEND else CREATE).use { writer ->
            var first = !appendToExisting
            bufferedReader().useLines { lines ->
                lines.filter(String::isNotBlank).forEach { line ->
                    if (deduplicate && !existing.add(line)) return@forEach
                    if (first) first = false else writer.append('\n')
                    writer.append(line)
                }
            }
        }
        return to
    }

    private fun File.ensureTrailingNewline() {
        if (!exists() || length() == 0L) return
        RandomAccessFile(this, "r").use {
            it.seek(length() - 1)
            if (it.read().toByte() != '\n'.code.toByte()) appendText("\n")
        }
    }

    private companion object {
        const val MAIN = "<main module>"
        const val MAX_REPORTED = 20
        val APPEND = arrayOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        val CREATE = arrayOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
    }
}

private fun File.bufferedWriter(options: Array<java.nio.file.StandardOpenOption>) = java.io.BufferedWriter(
    java.io.OutputStreamWriter(java.nio.file.Files.newOutputStream(toPath(), *options), Charsets.UTF_8),
)

