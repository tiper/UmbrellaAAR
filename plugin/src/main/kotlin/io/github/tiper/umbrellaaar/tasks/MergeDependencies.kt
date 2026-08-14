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

    /** Modules contributing Android resources, and the subset declaring a public resource surface. */
    private val modulesWithResources = mutableSetOf<String>()
    private val modulesDeclaringPublicResources = mutableSetOf<String>()

    @TaskAction
    fun execute() {
        contributors.clear()
        resourceNames.clear()
        seenLines.clear()
        modulesWithResources.clear()
        modulesDeclaringPublicResources.clear()

        val src = mainAarDir.get().asFile
        val out = mergedAarDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }

        src.copyRecursively(out, overwrite = true)
        src.walk().filter { it.isFile }.forEach {
            val relativePath = it.relativeTo(src).path.normalizePath()
            contributors[relativePath] = MAIN
            if (relativePath.startsWith("res/")) modulesWithResources += MAIN
            if (relativePath == PUBLIC_TXT) modulesDeclaringPublicResources += MAIN
        }

        val manifest = File(out, "AndroidManifest.xml")
        val packageOverride = manifest.removePackage()
        val libraryManifests = mutableListOf<Pair<String, File>>()
        val classEntries = mutableListOf<Triple<String, String, File>>()
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
                    if (relativePath.startsWith("res/")) modulesWithResources += owner

                    when {
                        // Class-path entries are never copied into the merged tree: they are streamed
                        // straight from the extracted module folder into `classes.jar` below. Copying
                        // ~3 000 class files only to walk and re-zip them was the single biggest cost
                        // of this task.
                        relativePath.startsWith("classes/") -> classEntries += classEntry(owner, relativePath, srcFile)

                        relativePath.startsWith("res/values") -> {
                            recordResourceNames(owner, relativePath, srcFile)
                            srcFile.copyValues(owner = owner, to = destFile)
                        }

                        relativePath.endsWith(".kotlin_module") -> srcFile.copyValues(owner = owner, to = destFile)

                        // R.txt is a line-based symbol table: concatenating it verbatim produced
                        // duplicated (and potentially conflicting) symbols.
                        relativePath.endsWith("R.txt") -> appendedFiles += srcFile.appendLines(to = destFile, deduplicate = true)

                        // public.txt is the same shape — one `<type> <name>` per line — so it merges
                        // the same way. See finalisePublicResources for why merging alone is not
                        // enough.
                        relativePath == PUBLIC_TXT -> {
                            modulesDeclaringPublicResources += owner
                            appendedFiles += srcFile.appendLines(to = destFile, deduplicate = true)
                        }

                        // Consumer rules live in `proguard.txt` inside an AAR — `consumer-rules.pro`
                        // is not part of the AAR spec and consumers simply ignore it.
                        relativePath.endsWith("proguard.txt") || relativePath.endsWith(".pro") -> {
                            val target = File(out, "proguard.txt")
                            touchedProguardFiles += srcFile.appendLines(to = target, deduplicate = false)
                        }

                        relativePath.endsWith("AndroidManifest.xml") -> libraryManifests += owner to srcFile

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

        mergeManifests(into = manifest, libraries = libraryManifests, packageOverride = packageOverride)

        reportDuplicateResources()

        (appendedFiles + touchedProguardFiles).forEach { it.ensureTrailingNewline() }

        finalisePublicResources(out)

        buildClassesJar(out, classEntries)
        logger.lifecycle("Merged dependencies into main AAR (processed $filesProcessed files)")
    }

    /**
     * Decides whether the merged `public.txt` may be published.
     *
     * In an AAR, `public.txt` is the whitelist of public resources: if it is present, **everything
     * not listed becomes private**; if it is absent, everything is public. A module that ships no
     * `public.txt` is therefore saying "all of my resources are public" — and there are no lines to
     * merge that express it.
     *
     * So merging the files that do exist is not enough on its own: publishing the union of two
     * modules' declarations would silently make every *other* merged module's resources private.
     * The file is only kept when every module that contributes resources declared its public
     * surface; otherwise it is dropped, which restores "everything public" — the state the
     * non-declaring modules already expected. Widening one module's intent is recoverable; silently
     * hiding another module's resources breaks the consumer's build.
     */
    private fun finalisePublicResources(out: File) {
        val publicTxt = File(out, PUBLIC_TXT)
        if (!publicTxt.exists()) return

        val implicitlyPublic = modulesWithResources - modulesDeclaringPublicResources
        if (implicitlyPublic.isEmpty()) return

        publicTxt.delete()
        logger.warn(
            buildString {
                appendLine("UmbrellaAar dropped public.txt from the merged AAR, so every merged resource stays public.")
                appendLine("  Declared a public resource surface: ${modulesDeclaringPublicResources.sorted().joinToString()}")
                appendLine("  Contribute resources but declared none: ${implicitlyPublic.sorted().joinToString()}")
                append(
                    "  Keeping it would have made those modules' resources private to consumers. " +
                        "Add <public> declarations to every module contributing resources to publish a public surface.",
                )
            },
        )
    }

    /** `classes/<path>` -> the jar entry name, applying the `.kotlin_module` per-module rename. */
    private fun classEntry(owner: String, relativePath: String, srcFile: File): Triple<String, String, File> {
        val entryName = relativePath.removePrefix("classes/")
        val renamed = when {
            entryName.endsWith(".kotlin_module") -> {
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
     * Duplicate detection is unchanged — it simply happens on jar entry names instead of on files
     * that had been copied into place first.
     */
    private fun buildClassesJar(out: File, dependencyEntries: List<Triple<String, String, File>>) {
        val classes = File(out, "classes")
        val entries = linkedMapOf<String, Pair<String, File>>()

        if (classes.isDirectory) {
            classes.walk().filter { it.isFile }.forEach {
                entries[it.relativeTo(classes).path.normalizePath()] = MAIN to it
            }
        }

        dependencyEntries.forEach { (entryName, owner, file) ->
            val existing = entries.putIfAbsent(entryName, owner to file)
            if (existing != null) onDuplicate(owner, "classes/$entryName", file, existing.second, existing.first)
        }

        val jar = File(out, "classes.jar").apply { if (exists()) delete() }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(jar), IO_BUFFER_SIZE)).use { zos ->
            entries.entries.sortedBy { it.key }.forEach { (entryName, source) ->
                zos.putNextEntry(ZipEntry(entryName).also { it.time = 0L })
                source.second.inputStream().use { it.copyTo(zos, IO_BUFFER_SIZE) }
                zos.closeEntry()
            }
        }
        classes.deleteRecursively()
    }

    /**
     * Two modules contributed the same entry. Identical bytes are harmless (the same generated file
     * shipped twice); anything else is a real conflict and must name *both* contributors — the old
     * message only named the second one, which made this class of failure very hard to diagnose.
     */
    private fun onDuplicate(
        owner: String,
        relativePath: String,
        srcFile: File,
        destFile: File,
        firstContributor: String = contributors[relativePath] ?: "unknown",
    ) {
        if (srcFile.length() == destFile.length() && srcFile.readBytes().contentEquals(destFile.readBytes())) {
            logger.info("[UmbrellaAar] Duplicate but identical '$relativePath' from '$owner' — keeping one copy.")
            return
        }
        throw GradleException(
            buildString {
                appendLine("UmbrellaAar cannot merge '$relativePath': it is contributed by two modules with different content.")
                appendLine("  First contributor : $firstContributor")
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

    /**
     * Merges every library manifest in a **single** [ManifestMerger2] invocation.
     *
     * Merging them one at a time re-parsed the accumulating result from disk and re-ran the whole
     * merge algorithm for each of N libraries — quadratic in manifest size, and ~23% of the merge
     * step at 95 modules. `addManifestProviders` takes a collection precisely so this can be done
     * in one pass, which is also how AGP itself merges library manifests.
     */
    private fun mergeManifests(into: File, libraries: List<Pair<String, File>>, packageOverride: String) {
        if (libraries.isEmpty()) return

        if (!into.exists()) {
            // No main manifest: seed with the first library's, then merge the rest into it.
            libraries.first().second.copyTo(into, overwrite = true)
            return mergeManifests(into, libraries.drop(1), packageOverride)
        }

        try {
            val report = ManifestMerger2.newMerger(into, GradleILogger(logger), LIBRARY)
                .addManifestProviders(libraries.map { (owner, file) -> manifestProvider(owner, file) })
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
                    into.writeText(mergedXml)
                }
            }
        } catch (e: GradleException) {
            throw e
        } catch (e: Exception) {
            throw GradleException("Failed to merge manifests: ${e.message}", e)
        }
    }

    /** [owner] is the module folder, so merge conflicts name the module rather than 95 identical file names. */
    private fun manifestProvider(owner: String, file: File) = object : ManifestProvider {
        override fun getName(): String = owner
        override fun getManifest(): File = file
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
        const val PUBLIC_TXT = "public.txt"
        const val MAX_REPORTED = 20
        val APPEND = arrayOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        val CREATE = arrayOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
    }
}

private fun File.bufferedWriter(options: Array<java.nio.file.StandardOpenOption>) = java.io.BufferedWriter(
    java.io.OutputStreamWriter(java.nio.file.Files.newOutputStream(toPath(), *options), Charsets.UTF_8),
)

