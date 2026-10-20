package io.github.tiper.umbrellaaar.tasks

import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.ManifestMerger2.Invoker.Feature.USES_SDK_IN_MANIFEST_LENIENT_HANDLING
import com.android.manifmerger.ManifestMerger2.MergeType.LIBRARY
import com.android.manifmerger.ManifestProvider
import com.android.manifmerger.ManifestSystemProperty.Document.PACKAGE
import com.android.manifmerger.MergingReport.MergedManifestKind.MERGED
import com.android.manifmerger.MergingReport.Record.Severity.ERROR
import com.android.utils.ILogger
import io.github.tiper.umbrellaaar.extensions.AAR_METADATA_PATH
import io.github.tiper.umbrellaaar.extensions.IO_BUFFER_SIZE
import io.github.tiper.umbrellaaar.extensions.declaredResourceNames
import io.github.tiper.umbrellaaar.extensions.mergeAarMetadata
import io.github.tiper.umbrellaaar.extensions.mergeXmlChildren
import io.github.tiper.umbrellaaar.extensions.normalizePath
import io.github.tiper.umbrellaaar.extensions.packageName
import io.github.tiper.umbrellaaar.extensions.parseAarMetadata
import io.github.tiper.umbrellaaar.extensions.stripPackageAttribute
import io.github.tiper.umbrellaaar.extensions.toAarMetadataText
import io.github.tiper.umbrellaaar.extensions.transformClass
import io.github.tiper.umbrellaaar.extensions.withoutResources
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
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

    /**
     * The umbrella module's namespace. `R` references in the umbrella's *own* classes that point at
     * a merged module's `R` are rewritten to it. When absent, the `package` of the umbrella AAR's
     * manifest is used.
     */
    @get:Input
    @get:Optional
    abstract val mainNamespace: Property<String>

    @get:OutputDirectory
    abstract val mergedAarDir: DirectoryProperty

    /** `entry path -> module folder that contributed it`, so duplicates can name *both* sides. */
    private val contributors = mutableMapOf<String, String>()

    /** `res/values*` folder + resource name -> module folders that declared it. */
    private val resourceNames = mutableMapOf<String, MutableList<String>>()

    /**
     * Keys already written to each de-duplicated file (`R.txt`, `public.txt`, `baseline-prof.txt`).
     * Kept in memory so each module's lines are checked once, instead of re-reading the growing
     * merged file for every module.
     */
    private val seenKeys = mutableMapOf<File, MutableSet<String>>()

    /** Modules contributing Android resources, and the subset declaring a public resource surface. */
    private val modulesWithResources = mutableSetOf<String>()
    private val modulesDeclaringPublicResources = mutableSetOf<String>()

    /** Modules whose `lint.jar` was not kept, because an AAR can only carry one. */
    private val droppedLintJars = mutableListOf<String>()

    @TaskAction
    fun execute() {
        contributors.clear()
        resourceNames.clear()
        seenKeys.clear()
        modulesWithResources.clear()
        modulesDeclaringPublicResources.clear()
        droppedLintJars.clear()

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
            // Recorded first, so the umbrella's own declaration of a resource always wins.
            if (relativePath.startsWith("res/values")) recordResourceNames(MAIN, relativePath, it)
            if (relativePath == PUBLIC_TXT) modulesDeclaringPublicResources += MAIN
        }

        val manifest = File(out, "AndroidManifest.xml")
        val packageOverride = manifest.removePackage()
        val libraryManifests = mutableListOf<Pair<String, File>>()
        val classEntries = mutableListOf<Triple<String, String, File>>()
        val aarMetadata = mutableListOf<Pair<String, File>>()
        val annotationZips = mutableListOf<Pair<String, File>>()
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

                        // All modules' values files end up in one AAR, i.e. one resource set for the
                        // consumer, where a resource declared twice is a hard "Duplicate resources"
                        // error. Only the first declaration is kept.
                        relativePath.startsWith("res/values") -> {
                            val alreadyDeclared = recordResourceNames(owner, relativePath, srcFile)
                            srcFile.copyValues(owner = owner, to = destFile, dropping = alreadyDeclared)
                        }

                        relativePath.endsWith(".kotlin_module") -> srcFile.copyValues(owner = owner, to = destFile)

                        // R.txt is a symbol table: one symbol per `<java type> <resource type> <name>`.
                        // The same resource declared by two modules carries a different placeholder
                        // ID in each, so de-duplicating whole lines left both — a duplicate symbol.
                        relativePath.endsWith("R.txt") -> appendedFiles += srcFile.appendLines(to = destFile, keyOf = SYMBOL_KEY)

                        // public.txt is one `<type> <name>` per line, so whole lines are the key. See
                        // finalisePublicResources for why merging alone is not enough.
                        relativePath == PUBLIC_TXT -> {
                            modulesDeclaringPublicResources += owner
                            appendedFiles += srcFile.appendLines(to = destFile, keyOf = WHOLE_LINE)
                        }

                        // Baseline profile rules are independent lines: every module's rules apply
                        // to the classes it contributed.
                        relativePath == BASELINE_PROFILE -> appendedFiles += srcFile.appendLines(to = destFile, keyOf = WHOLE_LINE)

                        // Consumer rules live in `proguard.txt` inside an AAR — `consumer-rules.pro`
                        // is not part of the AAR spec and consumers simply ignore it.
                        relativePath.endsWith("proguard.txt") || relativePath.endsWith(".pro") -> {
                            val target = File(out, "proguard.txt")
                            touchedProguardFiles += srcFile.appendLines(to = target, keyOf = null)
                        }

                        relativePath.endsWith("AndroidManifest.xml") -> libraryManifests += owner to srcFile

                        relativePath == AAR_METADATA_PATH -> aarMetadata += owner to srcFile

                        relativePath == ANNOTATIONS_ZIP -> annotationZips += owner to srcFile

                        relativePath == LINT_JAR && destFile.exists() -> keepFirstLintJar(owner, srcFile, destFile)

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

        mergeAarMetadata(into = File(out, AAR_METADATA_PATH), modules = aarMetadata)

        mergeAnnotationZips(into = File(out, ANNOTATIONS_ZIP), modules = annotationZips)

        reportDroppedLintJars()

        reportDuplicateResources()

        (appendedFiles + touchedProguardFiles).forEach { it.ensureTrailingNewline() }

        finalisePublicResources(out)

        // The merged modules' R classes are folded into the umbrella's, so references to them from
        // the umbrella's own code must follow. Dependency classes were already relocated while they
        // were extracted.
        val mergedNamespaces = libraryManifests.mapNotNullTo(mutableSetOf()) { (_, file) ->
            file.readText().packageName()?.replace('.', '/')
        }
        val umbrellaNamespace = (mainNamespace.orNull ?: packageOverride).replace('.', '/')
        buildClassesJar(out, classEntries, umbrellaNamespace, mergedNamespaces)
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

    /**
     * Folds every merged module's `aar-metadata.properties` into the umbrella's.
     *
     * The file is how an AAR tells its consumer what it needs — `minCompileSdk`, the minimum AGP
     * version, whether core library desugaring must be enabled. Keeping only the umbrella's copy
     * silently dropped the requirements of every merged module: a module using `java.time` with
     * desugaring would build fine in the consumer and crash at runtime on old devices.
     */
    private fun mergeAarMetadata(into: File, modules: List<Pair<String, File>>) {
        if (modules.isEmpty()) return

        val merged = if (into.isFile) into.readText().parseAarMetadata() else linkedMapOf()
        val conflicts = mutableListOf<String>()
        modules.forEach { (owner, file) ->
            merged.mergeAarMetadata(file.readText().parseAarMetadata()) { key, kept, dropped ->
                conflicts += "$key: kept '$kept', dropped '$dropped' from $owner"
            }
        }

        into.parentFile.mkdirs()
        into.writeText(merged.toAarMetadataText())

        if (conflicts.isNotEmpty()) {
            logger.warn(
                buildString {
                    appendLine("UmbrellaAar found conflicting AAR metadata across merged modules; the first value was kept:")
                    conflicts.forEach { appendLine("  $it") }
                }.trimEnd(),
            )
        }
    }

    /**
     * Merges every module's `annotations.zip` — the typedef annotations (`@IntDef`, `@StringDef`, …)
     * that lint applies in the consumer — into one.
     *
     * Entries are per-package `annotations.xml` files, so modules rarely collide; when two modules
     * share a package, their `<item>` declarations are combined.
     */
    private fun mergeAnnotationZips(into: File, modules: List<Pair<String, File>>) {
        if (modules.isEmpty()) return

        val entries = sortedMapOf<String, Pair<String, ByteArray>>()
        val sources = buildList {
            if (into.isFile) add(MAIN to into.readZipEntries())
            modules.forEach { (owner, file) -> add(owner to file.readZipEntries()) }
        }

        sources.forEach { (owner, zipEntries) ->
            zipEntries.forEach { (name, bytes) ->
                val existing = entries[name]
                entries[name] = when {
                    existing == null -> owner to bytes
                    existing.second.contentEquals(bytes) -> existing
                    else -> existing.first to (
                        existing.second.decodeToString().mergeXmlChildren(bytes.decodeToString())?.encodeToByteArray()
                            ?: throw conflict("$ANNOTATIONS_ZIP!/$name", existing.first, owner, "  Two modules declare different typedef annotations for the same package.")
                        )
                }
            }
        }

        into.parentFile.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(into), IO_BUFFER_SIZE)).use { zos ->
            entries.forEach { (name, value) ->
                zos.putNextEntry(ZipEntry(name).also { it.time = 0L })
                zos.write(value.second)
                zos.closeEntry()
            }
        }
    }

    private fun File.readZipEntries(): List<Pair<String, ByteArray>> = ZipFile(this).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory }
            .map { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }
            .toList()
    }

    /**
     * An AAR carries exactly one `lint.jar`, and lint checks cannot be combined into one jar safely
     * (each jar registers its own issue registry). The umbrella's own — or else the first module's —
     * is kept, and the others are reported rather than failing the whole merge.
     */
    private fun keepFirstLintJar(owner: String, srcFile: File, destFile: File) {
        if (srcFile.length() == destFile.length() && srcFile.readBytes().contentEquals(destFile.readBytes())) return
        droppedLintJars += owner
    }

    private fun reportDroppedLintJars() {
        if (droppedLintJars.isEmpty()) return
        logger.warn(
            "UmbrellaAar kept the lint checks of '${contributors[LINT_JAR] ?: MAIN}' only. An AAR carries a single lint.jar, " +
                "so the lint checks of ${droppedLintJars.joinToString()} are not shipped in the merged AAR.",
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
     * - The umbrella's own classes get their `R` references relocated here (see [execute]).
     * - Service provider files (`META-INF/services/<interface>`) contributed by several modules are merged, because each lists
     *   implementations that exist in the merged jar. Any other duplicate entry is a conflict.
     */
    private fun buildClassesJar(
        out: File,
        dependencyEntries: List<Triple<String, String, File>>,
        umbrellaNamespace: String,
        mergedNamespaces: Set<String>,
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

        val relocate = umbrellaNamespace.isNotEmpty() && mergedNamespaces.isNotEmpty()
        val jar = File(out, "classes.jar").apply { if (exists()) delete() }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(jar), IO_BUFFER_SIZE)).use { zos ->
            entries.entries.sortedBy { it.key }.forEach { (entryName, source) ->
                val (owner, file) = source
                zos.putNextEntry(ZipEntry(entryName).also { it.time = 0L })
                val services = serviceFiles[entryName]
                when {
                    services != null && services.size > 1 -> zos.write(services.mergeServiceFiles())
                    relocate && owner == MAIN && entryName.endsWith(".class") ->
                        zos.write(file.readBytes().transformClass(umbrellaNamespace, mergedNamespaces))
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
        throw conflict(
            relativePath,
            firstContributor,
            owner,
            when {
                relativePath.startsWith("assets/") -> "  Rename the asset in one of the modules, or move it to a module-specific sub-folder."
                relativePath.startsWith("jni/") -> "  Two modules ship a native library with the same name — rename one of them."
                relativePath.startsWith("libs/") -> "  Two modules embed a local jar with the same name — rename one of them."
                relativePath.startsWith("classes/") -> "  Two modules declare the same class. If one of them is the umbrella module itself, " +
                    "check build/reports/umbrellaaar for the merged module list."
                else -> "  Rename the file in one of the modules, or exclude the module from the umbrellaAar configuration."
            },
        )
    }

    private fun conflict(relativePath: String, firstContributor: String, secondContributor: String, hint: String) = GradleException(
        buildString {
            appendLine("UmbrellaAar cannot merge '$relativePath': it is contributed by two modules with different content.")
            appendLine("  First contributor : $firstContributor")
            appendLine("  Second contributor: $secondContributor")
            append(hint)
        },
    )

    /** Records [owner]'s resource declarations and returns the ones another module already made. */
    private fun recordResourceNames(owner: String, relativePath: String, srcFile: File): Set<String> {
        val folder = relativePath.substringBeforeLast('/')
        return srcFile.readText().declaredResourceNames().filterTo(mutableSetOf()) { resource ->
            val owners = resourceNames.getOrPut("$folder/$resource") { mutableListOf() }
            val declaredElsewhere = owners.any { it != owner }
            owners += owner
            declaredElsewhere
        }
    }

    private fun reportDuplicateResources() {
        val duplicates = resourceNames.filterValues { it.size > 1 }
        if (duplicates.isEmpty()) return

        logger.warn(
            buildString {
                appendLine(
                    "UmbrellaAar found ${duplicates.size} duplicate resource name(s) across merged modules; the first declaration was kept " +
                        "(the umbrella module's own, otherwise the first module listed):",
                )
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

    /** Copies a per-module file under a module-prefixed name, leaving out the resources in [dropping]. */
    private fun File.copyValues(owner: String, to: File, dropping: Set<String> = emptySet()) {
        val target = File(to.parentFile, "$owner-${to.nameWithoutExtension}.${to.extension}").also { it.parentFile.mkdirs() }
        val filtered = if (dropping.isEmpty()) null else readText().withoutResources(dropping)
        if (filtered == null) copyTo(target, overwrite = true) else target.writeText(filtered)
    }

    /**
     * Streams [this] into [to] line by line. `R.txt` reaches megabytes on large graphs, so the file
     * is never held in memory. When [keyOf] is given, a line whose key was already written to [to]
     * is skipped; `null` appends everything.
     */
    private fun File.appendLines(to: File, keyOf: ((String) -> String)?): File {
        val seen = keyOf?.let { key ->
            seenKeys.getOrPut(to) {
                if (to.exists()) to.useLines { lines -> lines.filter(String::isNotBlank).mapTo(mutableSetOf(), key) } else mutableSetOf()
            }
        }
        val appendToExisting = to.exists() && to.length() > 0L
        to.parentFile?.mkdirs()

        to.bufferedWriter(options = if (appendToExisting) APPEND else CREATE).use { writer ->
            var first = !appendToExisting
            bufferedReader().useLines { lines ->
                lines.filter(String::isNotBlank).forEach { line ->
                    if (seen != null && !seen.add(keyOf(line))) return@forEach
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
        const val BASELINE_PROFILE = "baseline-prof.txt"
        const val ANNOTATIONS_ZIP = "annotations.zip"
        const val LINT_JAR = "lint.jar"
        const val SERVICES_DIR = "META-INF/services/"
        const val MAX_REPORTED = 20
        val APPEND = arrayOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        val CREATE = arrayOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)

        val WHITESPACE = Regex("\\s+")

        /** `int string app_name 0x7f0e0001` -> `int string app_name`; `int[] styleable Foo { … }` -> `int[] styleable Foo`. */
        val SYMBOL_KEY: (String) -> String = { line -> line.trim().split(WHITESPACE, limit = 4).take(3).joinToString(" ") }
        val WHOLE_LINE: (String) -> String = String::trim
    }
}

private fun File.bufferedWriter(options: Array<java.nio.file.StandardOpenOption>) = java.io.BufferedWriter(
    java.io.OutputStreamWriter(java.nio.file.Files.newOutputStream(toPath(), *options), Charsets.UTF_8),
)
