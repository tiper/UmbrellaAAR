package io.github.tiper.umbrellaaar.tasks

import io.github.tiper.umbrellaaar.extensions.AAR_METADATA_PATH
import io.github.tiper.umbrellaaar.extensions.CASE_VARIANT_ADVICE
import io.github.tiper.umbrellaaar.extensions.IO_BUFFER_SIZE
import io.github.tiper.umbrellaaar.extensions.RClassContent
import io.github.tiper.umbrellaaar.extensions.andFolders
import io.github.tiper.umbrellaaar.extensions.caseVariant
import io.github.tiper.umbrellaaar.extensions.caseVariantsOf
import io.github.tiper.umbrellaaar.extensions.mergeAarMetadata
import io.github.tiper.umbrellaaar.extensions.mergeXmlChildren
import io.github.tiper.umbrellaaar.extensions.normalizePath
import io.github.tiper.umbrellaaar.extensions.packageName
import io.github.tiper.umbrellaaar.extensions.parseAarMetadata
import io.github.tiper.umbrellaaar.extensions.relocateRInRules
import io.github.tiper.umbrellaaar.extensions.sameBytesAs
import io.github.tiper.umbrellaaar.extensions.stripPackageAttribute
import io.github.tiper.umbrellaaar.extensions.toAarMetadataText
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
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction

/** The owner of what the umbrella module brings itself. */
internal const val MAIN = "<main module>"
internal const val PROGUARD_TXT = "proguard.txt"

@CacheableTask
abstract class MergeDependencies : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(RELATIVE)
    abstract val dependencies: DirectoryProperty

    /**
     * The module folders of [dependencies] in the order a consumer of those modules ranks them. Where
     * two modules ship one thing the first to come here keeps it, as it would in that consumer.
     */
    @get:Input
    abstract val moduleOrder: ListProperty<String>

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

    /** What the merged AAR holds so far, for [claimPath]. */
    private val paths = mutableMapOf<String, String>()

    /** `res/values*` folder + resource name, or a key of [MergedOverlays] -> module folders that shipped it. */
    private val resourceNames = mutableMapOf<String, MutableList<String>>()

    /** The fields merged classes read from the umbrella's `R`. */
    private val rFieldReads = mutableListOf<RFieldRead>()

    /**
     * Keys already written to each de-duplicated file (`public.txt`, `baseline-prof.txt`).
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
        paths.clear()
        resourceNames.clear()
        rFieldReads.clear()
        seenKeys.clear()
        modulesWithResources.clear()
        modulesDeclaringPublicResources.clear()
        droppedLintJars.clear()

        val src = mainAarDir.get().asFile
        val out = mergedAarDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }

        val values = MergedValues(resourceNames, ::conflict)
        val overlays = MergedOverlays(resourceNames, logger)
        val symbolTables = linkedMapOf(MAIN to File(src, R_TXT))

        src.copyRecursively(out, overwrite = true)
        src.walk().filter { it.isFile }.forEach {
            val relativePath = it.relativeTo(src).path.normalizePath()
            contributors[relativePath] = MAIN
            claimPath(MAIN, relativePath, out)
            if (relativePath.startsWith("res/")) modulesWithResources += MAIN
            // Recorded first, so the umbrella's own declaration of a resource always wins.
            when {
                relativePath.startsWith("res/values") -> values.add(MAIN, relativePath, File(out, relativePath))
                relativePath.isOverlay() -> overlays.isOverridden(MAIN, relativePath, it, out)
            }
            if (relativePath == PUBLIC_TXT) modulesDeclaringPublicResources += MAIN
        }

        val manifest = File(out, ANDROID_MANIFEST)
        val packageOverride = manifest.removePackage()
        val libraryManifests = mutableListOf<Pair<String, File>>()
        val classEntries = mutableListOf<Triple<String, String, File>>()
        val aarMetadata = mutableListOf<Pair<String, File>>()
        val annotationZips = mutableListOf<Pair<String, File>>()
        var filesProcessed = 0

        moduleOrder.get().forEach { owner ->
            val subLibFolder = dependencies.get().dir(owner).asFile

            subLibFolder.walk()
                .filter { it.isFile }
                .map { it to it.relativeTo(subLibFolder).path.normalizePath() }
                .sortedBy { (_, relativePath) -> relativePath }
                .forEach { (srcFile, relativePath) ->
                    val destFile = File(out, relativePath)
                    if (relativePath.startsWith("res/")) modulesWithResources += owner

                    when {
                        // For the check below, and nothing an AAR has any use for.
                        relativePath == R_REFERENCES -> srcFile.forEachLine { line ->
                            val (field, reader) = line.split('\t')
                            rFieldReads += RFieldRead(field, reader, owner)
                        }

                        // Says what one JVM module exports, and one jar is many. Two would clash, and
                        // a consumer runs an AAR's classes from the class path, where it is not read.
                        relativePath.isModuleDescriptor() -> Unit

                        // Class-path entries are never copied into the merged tree: they are streamed
                        // straight from the extracted module folder into `classes.jar` below. Copying
                        // ~3 000 class files only to walk and re-zip them was the single biggest cost
                        // of this task.
                        relativePath.startsWith("classes/") -> classEntries += classEntry(owner, relativePath, srcFile)

                        // Settled together once every module's is in, see MergedValues. Only the
                        // folder is a path of theirs, as the file goes under its module's name.
                        relativePath.startsWith("res/values") -> {
                            claimPath(owner, relativePath.substringBeforeLast('/'), out)
                            contributors.putIfAbsent(relativePath, owner)
                            values.add(owner, relativePath, srcFile, destFile.ofModule(owner))
                        }

                        // A consumer layers its modules, and what one ships replaces the same
                        // resource or asset of anything below it. One AAR has no layers.
                        relativePath.isOverlay() && overlays.isOverridden(owner, relativePath, srcFile, out) -> Unit

                        relativePath.endsWith(".kotlin_module") -> srcFile.copyTo(destFile.ofModule(owner), overwrite = true)

                        relativePath == R_TXT -> symbolTables[owner] = srcFile

                        // public.txt is one `<type> <name>` per line, so whole lines are the key. See
                        // finalisePublicResources for why merging alone is not enough.
                        relativePath == PUBLIC_TXT -> {
                            modulesDeclaringPublicResources += owner
                            srcFile.appendLines(to = destFile, keyOf = WHOLE_LINE)
                        }

                        // Baseline profile rules are independent lines: every module's rules apply
                        // to the classes it contributed.
                        relativePath == BASELINE_PROFILE -> srcFile.appendLines(to = destFile, keyOf = WHOLE_LINE)

                        // Consumer rules live in `proguard.txt` inside an AAR — `consumer-rules.pro`
                        // is not part of the AAR spec and consumers simply ignore it. Away from the
                        // AAR root (`assets/…`) neither is a rule file, just a file with that name.
                        relativePath == PROGUARD_TXT || relativePath.isRootProFile() -> {
                            val target = File(out, PROGUARD_TXT)
                            srcFile.appendLines(to = target, keyOf = null)
                        }

                        relativePath == ANDROID_MANIFEST -> libraryManifests += owner to srcFile

                        relativePath == AAR_METADATA_PATH -> aarMetadata += owner to srcFile

                        relativePath == ANNOTATIONS_ZIP -> annotationZips += owner to srcFile

                        relativePath == LINT_JAR && destFile.exists() -> keepFirstLintJar(owner, srcFile, destFile)

                        else -> {
                            claimPath(owner, relativePath, out)
                            failOnFileAndFolder(owner, relativePath, out)
                            if (destFile.exists()) {
                                onDuplicate(owner, relativePath, srcFile, destFile)
                            } else {
                                destFile.parentFile?.mkdirs()
                                srcFile.copyTo(destFile, overwrite = true)
                                contributors[relativePath] = owner
                            }
                        }
                    }
                    filesProcessed++
                }

            overlays.takeOverriddenIds(owner)?.let { ids ->
                val name = "res/values/$owner-overridden-ids.xml"
                File(out, name).apply { parentFile.mkdirs() }.writeText(ids.joinToString("", "<resources>", "</resources>\n") { "<item name=\"$it\" type=\"id\"/>" })
                values.add(owner, name, File(out, name))
            }
        }

        val lost = values.merge().let { if (it.isEmpty()) it else it - out.inlineIdSymbols() }

        mergeManifests(into = manifest, libraries = libraryManifests, packageOverride = packageOverride, logger = logger)

        mergeAarMetadata(into = File(out, AAR_METADATA_PATH), modules = aarMetadata)

        mergeAnnotationZips(into = File(out, ANNOTATIONS_ZIP), modules = annotationZips)

        reportDroppedLintJars()

        reportDuplicateResources()

        val symbols = mergeSymbols(into = File(out, R_TXT), tables = symbolTables, lost = lost)

        File(out, PUBLIC_TXT).takeIf { it.isFile }?.let { file ->
            file.writeText(file.readLines().filterNot { it.isBlank() || it.trim().replace(' ', '/') in lost }.joinToString("") { "$it\n" })
        }
        finalisePublicResources(out)

        // The merged modules' R classes are folded into the umbrella's, so references to them from
        // the umbrella's own code must follow. Dependency classes were already relocated while they
        // were extracted.
        val namespaces = libraryManifests.mapNotNull { (owner, file) -> file.readText().packageName()?.let { owner to it.replace('.', '/') } }.toMap()
        val umbrellaNamespace = (mainNamespace.orNull ?: packageOverride).replace('.', '/')
        val rClasses by lazy { (namespaces + (MAIN to umbrellaNamespace)).rClasses(symbolTables, symbols) }
        buildClassesJar(
            out,
            classEntries,
            umbrellaNamespace,
            namespaces.values.toSet(),
            module = path.substringBeforeLast(':').ifEmpty { ":" },
            onDuplicate = ::onDuplicate,
            onRField = { field, reader -> rFieldReads += RFieldRead(field, reader, MAIN) },
            relocateRules = { it.relocateRInRules(umbrellaNamespace, rClasses) },
        )
        File(out, PROGUARD_TXT).takeIf { it.isFile }?.let { it.writeText(it.readText().relocateRInRules(umbrellaNamespace, rClasses)) }
        failOnMissingFields(symbols)
        logger.lifecycle("Merged dependencies into main AAR (processed $filesProcessed files)")
    }

    /**
     * Folds [tables], the umbrella's `R.txt` and each module's, into one, without what the merge
     * [lost]. Only that goes: any other symbol has a resource left, or belongs to a dependency that a
     * transitive `R` lists.
     */
    private fun mergeSymbols(into: File, tables: Map<String, File>, lost: Set<String>) = SymbolTable().apply {
        val existing = tables.filterValues(File::isFile)
        existing.forEach { (module, table) -> read(table, module) }
        remove(lost)
        failOnFieldClash()
        if (existing.isNotEmpty()) write(into)
    }

    /**
     * Two fields in one `R`, which stock mostly has in two. An `int` and an `int[]` of one name are a
     * class that Kotlin cannot read. Two `int`s are a class the JVM cannot load and one field in the
     * APK, with the place of one of them, so a module reads another attr, and the app stays green.
     */
    private fun SymbolTable.failOnFieldClash() {
        val (first, second) = clash() ?: return
        throw cannotMerge(
            "R.styleable.${first.name}",
            "styleables ${first.styleable} and ${second.styleable} each declare it, and the umbrella has one R",
            first.module,
            second.module,
            "  A stock consumer has an R per namespace. Here a module would read another's field, or a consumer could not compile a read of it. " +
                "Rename one of the styleables or attrs, or exclude(...) one of the modules from export and publish it on its own.",
        )
    }

    /**
     * From `module -> namespace` to what the `R` of each namespace had in stock, by type, out of
     * [tables] by module. A field that [merged] does not have is gone.
     */
    private fun Map<String, String>.rClasses(tables: Map<String, File>, merged: SymbolTable): Map<String, Map<String, RClassContent>> {
        val readers = rFieldReads.groupBy({ it.field }, { it.reader })
        return entries.groupBy({ it.value }, { it.key }).mapValues { (_, owners) ->
            val table = SymbolTable().apply { owners.mapNotNull(tables::get).filter(File::isFile).forEach(::read) }
            table.fieldsIn(merged).mapValues { (type, fields) ->
                RClassContent(fields, fields.flatMapTo(sortedSetOf()) { readers["$type/$it"].orEmpty() }, fields.filterTo(hashSetOf()) { table.isArray("$type/$it") })
            }
        }
    }

    /**
     * The fields of a merged module's `R` are the umbrella's now, and a consumer generates that class
     * out of the final `R.txt`. A class reading a field that did not make it in links and installs,
     * and throws `NoSuchFieldError` where it is reached. So this goes by `R.txt`, which lists what a
     * transitive `R` gets from outside the merge too, and not by the resources merged.
     */
    private fun failOnMissingFields(symbols: SymbolTable) {
        val missing = rFieldReads.filterNot { it.field in symbols }.distinctBy { it.field }.sortedBy { it.field }
        if (missing.isEmpty()) return

        throw GradleException(
            buildString {
                appendLine("UmbrellaAar found ${missing.size} R field(s) that merged classes read and the merged R.txt does not have:")
                missing.take(MAX_REPORTED).forEach { appendLine("  ${it.field} read by ${it.reader} in ${it.owner}") }
                if (missing.size > MAX_REPORTED) appendLine("  … and ${missing.size - MAX_REPORTED} more")
                append(
                    "  A consumer's R would not have them, so those classes would throw NoSuchFieldError at runtime. " +
                        "Declare each resource in a merged module, or stop reading it. " +
                        "An id of an <enum> goes with its attr when another module's definition of that attr wins.",
                )
            },
        )
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
        if (!srcFile.sameBytesAs(destFile)) droppedLintJars += owner
    }

    private fun reportDroppedLintJars() {
        if (droppedLintJars.isEmpty()) return
        logger.warn(
            "UmbrellaAar kept the lint checks of '${contributors[LINT_JAR] ?: MAIN}' only. An AAR carries a single lint.jar, " +
                "so the lint checks of ${droppedLintJars.joinToString()} are not shipped in the merged AAR.",
        )
    }

    private fun String.isModuleDescriptor() = MODULE_DESCRIPTOR.matches(this)

    private fun String.isRootProFile() = endsWith(".pro") && '/' !in this

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
        if (srcFile.sameBytesAs(destFile)) {
            logger.info("[UmbrellaAar] Duplicate but identical '$relativePath' from '$owner' — keeping one copy.")
            return
        }
        throw conflict(
            relativePath,
            firstContributor,
            owner,
            when {
                relativePath.startsWith("jni/") -> "  Two modules ship a native library with the same name — rename one of them."

                relativePath.startsWith("libs/") -> "  Two modules embed a local jar with the same name — rename one of them."

                relativePath.startsWith("classes/") && relativePath.endsWith(".class") ->
                    "  Two modules declare the same class. If one of them is the umbrella module itself, " +
                        "check build/reports/umbrellaaar for the merged module list."

                relativePath == "navigation.json" ->
                    "  AGP generates this file, so it cannot be renamed, and two modules with deep links cannot be merged. " +
                        "Move the deep links to one module, or exclude(...) one of them from export and publish it on its own."

                relativePath.startsWith("prefab/") ->
                    "  AGP generates this file, so it cannot be renamed, and two modules with prefab cannot be merged. " +
                        "Move the native libraries to one module, or exclude(...) one of them from export and publish it on its own."

                else -> "  Rename the file in one of the modules, or exclude(...) one of them from export and publish it on its own."
            },
        )
    }

    private fun conflict(relativePath: String, firstContributor: String, secondContributor: String, hint: String) = cannotMerge(relativePath, "it is contributed by two modules with different content", firstContributor, secondContributor, hint)

    private fun cannotMerge(relativePath: String, reason: String, firstContributor: String, secondContributor: String, hint: String) = GradleException(
        buildString {
            appendLine("UmbrellaAar cannot merge '$relativePath': $reason.")
            appendLine("  First contributor : $firstContributor")
            appendLine("  Second contributor: $secondContributor")
            append(hint)
        },
    )

    /**
     * Takes [relativePath] of the merged AAR for [owner]. On a disk that ignores case, a path that
     * differs by case alone from one already here ends up at that one, and the AAR takes that to
     * every consumer, whatever their disk. A library published on its own keeps the path it has.
     */
    private fun claimPath(owner: String, relativePath: String, out: File) {
        val (first, variant) = paths.caseVariant(relativePath, out) ?: return
        throw cannotMerge(
            relativePath,
            caseVariantsOf(first, variant),
            firstContributorOf(first),
            owner,
            "  The merged AAR would ship them at one path, for every consumer. $CASE_VARIANT_ADVICE",
        )
    }

    /**
     * A path is a file or a folder, on any disk, so [relativePath] of [owner] cannot go where the
     * other already is. Left to the copy, that ends in an error naming no module.
     */
    private fun failOnFileAndFolder(owner: String, relativePath: String, out: File) {
        val taken = relativePath.andFolders().firstOrNull { path -> File(out, path).run { if (path == relativePath) isDirectory else isFile } } ?: return
        throw cannotMerge(
            relativePath,
            "'$taken' is a file in one module and a folder in the other",
            firstContributorOf(taken),
            owner,
            "  Rename the file or the folder in one of the modules, or exclude(...) one of them from export and publish it on its own.",
        )
    }

    /** The module that brought [path], or the first thing in it where that is a folder. */
    private fun firstContributorOf(path: String) = contributors.entries.find { (entry) -> entry == path || entry.startsWith("$path/") }?.value ?: "unknown"

    private fun reportDuplicateResources() {
        val duplicates = resourceNames.filterValues { it.size > 1 }
        if (duplicates.isEmpty()) return

        logger.warn(
            buildString {
                appendLine(
                    "UmbrellaAar found ${duplicates.size} duplicate resource or asset name(s) across merged modules; the first was kept " +
                        "(the umbrella module's own, otherwise the first module listed), as it would be in a consumer of those modules:",
                )
                duplicates.entries.sortedBy { it.key }.take(MAX_REPORTED).forEach { (key, owners) ->
                    appendLine("  $key <- ${owners.joinToString()}")
                }
                if (duplicates.size > MAX_REPORTED) appendLine("  … and ${duplicates.size - MAX_REPORTED} more")
                append("  Rename it in one of the modules if each is meant to ship.")
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

    /** Where a file every module may ship goes, under a module-prefixed name. */
    private fun File.ofModule(owner: String) = File(parentFile, "$owner-$name").also { it.parentFile.mkdirs() }

    /**
     * Streams [this] into [to] line by line, each with its own line end, so whatever [to] already
     * holds meets the next line with no blank one between: AGP's reader of `public.txt` gives up on
     * a whole file over one. When [keyOf] is given, a line whose key was already written to [to] is
     * skipped; `null` appends everything.
     */
    private fun File.appendLines(to: File, keyOf: ((String) -> String)?) {
        val seen = keyOf?.let { key ->
            seenKeys.getOrPut(to) {
                if (to.exists()) to.useLines { lines -> lines.filter(String::isNotBlank).mapTo(mutableSetOf(), key) } else mutableSetOf()
            }
        }
        val appendToExisting = to.exists() && to.length() > 0L
        to.parentFile?.mkdirs()
        to.ensureTrailingNewline()

        to.bufferedWriter(options = if (appendToExisting) APPEND else CREATE).use { writer ->
            bufferedReader().useLines { lines ->
                lines.filter(String::isNotBlank).forEach { line ->
                    if (seen != null && !seen.add(keyOf(line))) return@forEach
                    writer.append(line).append('\n')
                }
            }
        }
    }

    private fun File.ensureTrailingNewline() {
        if (!exists() || length() == 0L) return
        RandomAccessFile(this, "r").use {
            it.seek(length() - 1)
            if (it.read().toByte() != '\n'.code.toByte()) appendText("\n")
        }
    }

    /** Class [reader] of module [owner] reads [field], as `<type>/<name>`, from the umbrella's `R`. */
    private data class RFieldRead(val field: String, val reader: String, val owner: String)

    private companion object {
        const val ANDROID_MANIFEST = "AndroidManifest.xml"
        const val R_TXT = "R.txt"
        const val PUBLIC_TXT = "public.txt"
        const val BASELINE_PROFILE = "baseline-prof.txt"
        const val ANNOTATIONS_ZIP = "annotations.zip"
        const val LINT_JAR = "lint.jar"
        const val MAX_REPORTED = 20
        val MODULE_DESCRIPTOR = Regex("""classes/(?:META-INF/versions/\d+/)?module-info\.class""")
        val APPEND = arrayOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        val CREATE = arrayOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)

        val WHOLE_LINE: (String) -> String = String::trim
    }
}

private fun File.bufferedWriter(options: Array<java.nio.file.StandardOpenOption>) = java.io.BufferedWriter(
    java.io.OutputStreamWriter(java.nio.file.Files.newOutputStream(toPath(), *options), Charsets.UTF_8),
)
